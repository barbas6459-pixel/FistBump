package com.example.fistbump

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import android.util.Log
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.Executors

class BumpService : Service(), SensorEventListener {
    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("7a1b4b2e-2d3c-4a7f-9b1e-8f3c2a9d1001")
        const val TAG = "FistBump"
        const val BUMP_WINDOW_MS = 4000L
        const val BUMP_DEBOUNCE_MS = 3000L
        const val BUMP_THRESHOLD = 28f // m/s^2 de sacudida para contar como choque
    }

    private val pool = Executors.newCachedThreadPool()
    private lateinit var adapter: BluetoothAdapter
    private var advertiser: BluetoothLeAdvertiser? = null
    private var scanner: BluetoothLeScanner? = null
    private var sensorManager: SensorManager? = null
    private var lastBumpAt = 0L
    private var windowOpenUntil = 0L
    private val matched = mutableSetOf<String>()
    @Volatile private var serverSocket: BluetoothServerSocket? = null

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("MissingPermission")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("bump", "FistBump", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel("bump_alert", "Alertas", NotificationManager.IMPORTANCE_HIGH))
        val n = Notification.Builder(this, "bump")
            .setContentTitle("FistBump activo")
            .setContentText("Choca los celulares para enviar el archivo")
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else startForeground(1, n)

        adapter = getSystemService(BluetoothManager::class.java).adapter
        advertiser = adapter.bluetoothLeAdvertiser
        scanner = adapter.bluetoothLeScanner

        sensorManager = getSystemService(SensorManager::class.java)
        sensorManager?.registerListener(
            this, sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER),
            SensorManager.SENSOR_DELAY_GAME
        )

        pool.execute { runServer() }

        Store.running = true
        Store.statusText = "Listo. Choca los celulares."
        return START_STICKY
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onSensorChanged(event: SensorEvent) {
        val x = event.values[0]; val y = event.values[1]; val z = event.values[2]
        val mag = Math.sqrt((x * x + y * y + z * z).toDouble()).toFloat()
        val jerk = Math.abs(mag - SensorManager.GRAVITY_EARTH)
        val now = System.currentTimeMillis()
        if (jerk > BUMP_THRESHOLD && now - lastBumpAt > BUMP_DEBOUNCE_MS) {
            lastBumpAt = now
            onBumpDetected()
        }
    }

    @SuppressLint("MissingPermission")
    private fun onBumpDetected() {
        Store.statusText = "¡Choque detectado! Buscando el otro teléfono…"
        Store.addLog("Choque detectado, buscando teléfono cercano…")
        windowOpenUntil = System.currentTimeMillis() + BUMP_WINDOW_MS
        startAdvertise()
        startScan()
        android.os.Handler(mainLooper).postDelayed({ stopAdvertiseAndScan() }, BUMP_WINDOW_MS)
    }

    @SuppressLint("MissingPermission")
    private fun startAdvertise() {
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .build()
        val data = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuidCompat(SERVICE_UUID))
            .setIncludeDeviceName(false)
            .build()
        try { advertiser?.startAdvertising(settings, data, advertiseCb) } catch (_: Exception) {}
    }

    private val advertiseCb = object : AdvertiseCallback() {}

    @SuppressLint("MissingPermission")
    private fun startScan() {
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuidCompat(SERVICE_UUID)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        try { scanner?.startScan(listOf(filter), settings, scanCb) } catch (_: Exception) {}
    }

    private val scanCb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (System.currentTimeMillis() > windowOpenUntil) return
            val addr = result.device.address
            if (matched.contains(addr)) return
            matched.add(addr)
            Store.addLog("Teléfono encontrado: $addr")
            val pending = Store.pending
            if (pending != null) pool.execute { sendFile(result.device, pending) }
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopAdvertiseAndScan() {
        try { advertiser?.stopAdvertising(advertiseCb) } catch (_: Exception) {}
        try { scanner?.stopScan(scanCb) } catch (_: Exception) {}
        matched.clear()
        Store.statusText = "Listo. Choca los celulares."
    }

    // --- Servidor: siempre escuchando para recibir archivos ---
    @SuppressLint("MissingPermission")
    private fun runServer() {
        try {
            val ss = adapter.listenUsingInsecureRfcommWithServiceRecord("FistBump", SERVICE_UUID)
            serverSocket = ss
            while (true) {
                val socket = ss.accept() ?: break
                pool.execute { receiveFile(socket) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Servidor detenido: ${e.message}")
        }
    }

    private fun receiveFile(socket: BluetoothSocket) {
        try {
            val din = DataInputStream(socket.inputStream)
            val name = din.readUTF()
            val mime = din.readUTF()
            val size = din.readLong()
            val bytes = ByteArray(size.toInt())
            din.readFully(bytes)
            val uri = saveToGallery(name, mime, bytes)
            Store.addLog("Recibido: $name")
            notifyReceived(name, uri)
        } catch (e: Exception) {
            Log.e(TAG, "Error al recibir: ${e.message}")
            Store.addLog("Error al recibir un archivo")
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    @SuppressLint("MissingPermission")
    private fun sendFile(device: BluetoothDevice, file: PendingFile) {
        var socket: BluetoothSocket? = null
        try {
            Store.statusText = "Enviando ${file.name}…"
            socket = device.createInsecureRfcommSocketToServiceRecord(SERVICE_UUID)
            try { scanner?.stopScan(scanCb) } catch (_: Exception) {}
            socket.connect()
            val input: InputStream = contentResolver.openInputStream(file.uri)
                ?: throw IllegalStateException("No se pudo abrir el archivo")
            val bytes = input.use { it.readBytes() }
            val dout = DataOutputStream(socket.outputStream)
            dout.writeUTF(file.name)
            dout.writeUTF(file.mime)
            dout.writeLong(bytes.size.toLong())
            dout.write(bytes)
            dout.flush()
            Store.addLog("Enviado: ${file.name}")
            Store.statusText = "Enviado. Listo para otro choque."
        } catch (e: Exception) {
            Log.e(TAG, "Error al enviar: ${e.message}")
            Store.addLog("No se pudo enviar el archivo")
            Store.statusText = "Error al enviar. Intenta de nuevo."
        } finally {
            try { socket?.close() } catch (_: Exception) {}
        }
    }

    private fun saveToGallery(name: String, mime: String, bytes: ByteArray): Uri? {
        val resolver = contentResolver
        val collection: Uri
        val values = ContentValues()
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, name)
        values.put(MediaStore.MediaColumns.MIME_TYPE, mime)
        when {
            mime.startsWith("image/") -> {
                collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                if (Build.VERSION.SDK_INT >= 29) values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/FistBump")
            }
            mime.startsWith("video/") -> {
                collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                if (Build.VERSION.SDK_INT >= 29) values.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/FistBump")
            }
            Build.VERSION.SDK_INT >= 29 -> {
                collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
                values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/FistBump")
            }
            else -> {
                collection = MediaStore.Files.getContentUri("external")
            }
        }
        val uri = resolver.insert(collection, values) ?: return null
        resolver.openOutputStream(uri)?.use { it.write(bytes) }
        return uri
    }

    private fun notifyReceived(name: String, uri: Uri?) {
        val n = Notification.Builder(this, "bump_alert")
            .setContentTitle("Archivo recibido")
            .setContentText("$name se guardó en tu galería")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(name.hashCode(), n)
    }

    @SuppressLint("MissingPermission")
    override fun onDestroy() {
        sensorManager?.unregisterListener(this)
        try { advertiser?.stopAdvertising(advertiseCb) } catch (_: Exception) {}
        try { scanner?.stopScan(scanCb) } catch (_: Exception) {}
        try { serverSocket?.close() } catch (_: Exception) {}
        Store.running = false
        Store.statusText = "Detenido"
        super.onDestroy()
    }
}

// Pequeña envoltura para evitar importar android.os.ParcelUuid en cada sitio
fun ParcelUuidCompat(uuid: UUID): android.os.ParcelUuid = android.os.ParcelUuid(uuid)
