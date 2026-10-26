package com.example.fistbump

import android.net.Uri
import java.util.Collections

data class PendingFile(val uri: Uri, val name: String, val mime: String)

object Store {
    @Volatile var running = false
    @Volatile var pending: PendingFile? = null
    @Volatile var statusText: String = "Detenido"
    val log: MutableList<String> = Collections.synchronizedList(mutableListOf())

    fun addLog(line: String) {
        log.add(0, line)
        synchronized(log) { while (log.size > 50) log.removeAt(log.size - 1) }
    }
}
