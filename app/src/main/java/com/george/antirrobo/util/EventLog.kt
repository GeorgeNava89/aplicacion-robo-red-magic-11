package com.george.antirrobo.util

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Bitácora local en filesDir/antirrobo_events.log (y una copia rotada).
 * Sirve para diagnosticar el nombre de clase del menú de apagado en Nubia.
 */
object EventLog {
    private const val TAG = "AntirroboLog"
    private const val FILE = "antirrobo_events.log"
    private const val MAX_BYTES = 512 * 1024
    private val executor = Executors.newSingleThreadExecutor()
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    @Volatile
    private var file: File? = null

    fun init(context: Context) {
        file = File(context.applicationContext.filesDir, FILE)
    }

    fun i(tag: String, message: String) {
        Log.i(tag, message)
        write("I", tag, message)
    }

    fun w(tag: String, message: String, error: Throwable? = null) {
        Log.w(tag, message, error)
        write("W", tag, message + (error?.let { " | ${it.javaClass.simpleName}: ${it.message}" } ?: ""))
    }

    fun e(tag: String, message: String, error: Throwable? = null) {
        Log.e(tag, message, error)
        write("E", tag, message + (error?.let { " | ${it.javaClass.simpleName}: ${it.message}" } ?: ""))
    }

    fun filePath(): String = file?.absolutePath ?: "(aún no inicializado)"

    private fun write(level: String, tag: String, message: String) {
        val target = file ?: return
        val line = "${fmt.format(Date())} $level/$tag: $message\n"
        executor.execute {
            try {
                if (target.exists() && target.length() > MAX_BYTES) {
                    val bak = File(target.parentFile, "$FILE.1")
                    if (bak.exists()) bak.delete()
                    target.renameTo(bak)
                }
                target.appendText(line, Charsets.UTF_8)
            } catch (t: Throwable) {
                Log.w(TAG, "No se pudo escribir la bitácora", t)
            }
        }
    }
}
