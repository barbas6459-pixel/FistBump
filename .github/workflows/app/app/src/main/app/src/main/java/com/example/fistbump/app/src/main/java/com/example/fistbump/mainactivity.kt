package com.example.fistbump

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView

class MainActivity : Activity() {
    private val h = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var fileLabel: TextView
    private lateinit var pickBtn: Button
    private lateinit var toggleBtn: Button
    private lateinit var adapter: ArrayAdapter<String>

    private val PICK_REQUEST = 42

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun queryName(uri: Uri): String {
        var name = "archivo"
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) name = c.getString(idx)
        }
        return name
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(32), dp(16), dp(16))
        }
        root.addView(TextView(this).apply { text = "FistBump"; textSize = 24f })
        status = TextView(this).apply { textSize = 14f; text = "Detenido" }
        root.addView(status)

        fileLabel = TextView(this).apply { textSize = 14f; text = "Ningún archivo elegido" }
        pickBtn = Button(this).apply {
            text = "Elegir imagen o video"
            setOnClickListener {
                val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*"))
                }
                startActivityForResult(i, PICK_REQUEST)
            }
        }
        root.addView(pickBtn); root.addView(fileLabel)

        toggleBtn = Button(this).apply { setOnClickListener { toggle() } }
        root.addView(toggleBtn)

        root.addView(TextView(this).apply { text = "Actividad reciente"; textSize = 16f; setPadding(0, dp(16), 0, 0) })
        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf<String>())
        val list = ListView(this).apply { this.adapter = this@MainActivity.adapter }
        root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        refresh()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PICK_REQUEST && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val name = queryName(uri)
            val mime = contentResolver.getType(uri) ?: "application/octet-stream"
            Store.pending = PendingFile(uri, name, mime)
            fileLabel.text = "Archivo listo: $name"
        }
    }

    private fun needed(): Array<String> {
        val p = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 31) {
            p.add(Manifest.permission.BLUETOOTH_SCAN)
            p.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            p.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= 33) p.add(Manifest.permission.POST_NOTIFICATIONS)
        return p.toTypedArray()
    }

    private fun toggle() {
        if (Store.running) {
            stopService(Intent(this, BumpService::class.java))
            Store.running = false
            return
        }
        val missing = needed().filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1) else startBump()
    }

    private fun startBump() {
        startForegroundService(Intent(this, BumpService::class.java))
        Store.running = true
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        val ok = needed().all {
            it == Manifest.permission.POST_NOTIFICATIONS ||
                checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }
        if (ok) startBump() else status.text = "Faltan permisos de ubicación y Bluetooth."
    }

    private fun refresh() {
        toggleBtn.text = if (Store.running) "Detener" else "Activar FistBump"
        status.text = Store.statusText
        adapter.clear()
        adapter.addAll(Store.log.toList())
        adapter.notifyDataSetChanged()
        h.postDelayed({ refresh() }, 800)
    }

    override fun onDestroy() {
        h.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
