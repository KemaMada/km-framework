package com.example.keymessage.util

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

object LogBuffer {
    private const val maxLines = 200
    private val lines = mutableListOf<String>()
    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private var logFile: File? = null

    fun init(context: Context) {
        logFile = File(context.filesDir, "km-debug.log")
        synchronized(lines) {
            try {
                logFile?.let { f ->
                    if (f.exists()) {
                        val saved = f.readLines()
                        lines.addAll(saved.takeLast(maxLines))
                    }
                }
            } catch (_: Exception) {}
        }
    }

    fun log(tag: String, msg: String) {
        val ts = dateFormat.format(Date())
        val line = "[$ts] [$tag] $msg"
        synchronized(lines) {
            lines.add(line)
            if (lines.size > maxLines) lines.removeAt(0)
        }
        try {
            logFile?.appendText(line + "\n")
        } catch (_: Exception) {}
    }

    fun getLines(): List<String> = synchronized(lines) { lines.toList() }
    fun clear() { synchronized(lines) { lines.clear() } }
}
