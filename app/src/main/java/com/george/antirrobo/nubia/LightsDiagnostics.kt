package com.george.antirrobo.nubia

import android.content.Context
import android.net.Uri
import android.provider.Settings
import com.george.antirrobo.util.EventLog
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v0.1.2 — "Diagnóstico de luces".
 *
 * Nubia no publica qué clave de Settings controla las luces RGB laterales.
 * Esta herramienta lee las tablas system / secure / global, se queda con las
 * claves cuyo nombre contiene light/led/rgb/breath/ambient/fan/nubia/redmagic,
 * y permite comparar dos escaneos:
 *   A) con las luces ENCENDIDAS en Game Space
 *   B) después de APAGARLAS en Game Space
 * La clave que cambió es candidata a controlar las luces.
 *
 * LÍMITES (no verificados en el Red Magic 11 Pro):
 *  - Android 12+ puede ocultar a apps de terceros algunas claves @hide.
 *    Si el escaneo sale vacío, usa adb (ver README):
 *      adb shell settings list system | grep -iE 'light|led|rgb|breath|ambient|nubia|redmagic'
 *  - Puede que Game Space guarde el estado fuera de Settings (en su propia
 *    base de datos) y que no cambie ninguna clave. Entonces este método no sirve.
 */
object LightsDiagnostics {

    private const val TAG = "LightsDiag"
    val KEY_PATTERN = Regex("light|led|rgb|breath|ambient|nubia|redmagic|nb_|zte|lamp|malp|strip|atmosphere", RegexOption.IGNORE_CASE)

    data class Entry(val table: String, val key: String, val value: String?)
    data class Diff(val table: String, val key: String, val before: String?, val after: String?)
    data class ScanResult(val entries: List<Entry>, val errors: List<String>, val file: String)

    @Volatile var scanA: ScanResult? = null
        private set
    @Volatile var scanB: ScanResult? = null
        private set

    fun scan(context: Context, label: String): ScanResult {
        val entries = mutableListOf<Entry>()
        val errors = mutableListOf<String>()
        val tables = listOf(
            "system" to Settings.System.CONTENT_URI,
            "secure" to Settings.Secure.CONTENT_URI,
            "global" to Settings.Global.CONTENT_URI,
        )
        for ((name, uri) in tables) {
            try {
                entries += queryTable(context, name, uri)
            } catch (t: Throwable) {
                errors += "$name: ${t.javaClass.simpleName}: ${t.message}"
            }
        }
        // Claves conjeturadas que quizá no aparecen en el listado completo.
        for (key in NubiaHardwareController.GUESSED_KEYS) {
            for (table in listOf("system", "secure", "global")) {
                if (entries.any { it.table == table && it.key == key }) continue
                val v = try {
                    NubiaHardwareController.readSetting(context, table, key)
                } catch (t: Throwable) {
                    null
                }
                if (v != null) entries += Entry(table, key, v)
            }
        }
        val file = save(context, label, entries, errors)
        val result = ScanResult(entries.sortedWith(compareBy({ it.table }, { it.key })), errors, file)
        if (label == "A") scanA = result else scanB = result
        EventLog.i(TAG, "Escaneo $label: ${entries.size} claves, ${errors.size} errores → $file")
        return result
    }

    fun diff(): List<Diff> {
        val a = scanA?.entries ?: return emptyList()
        val b = scanB?.entries ?: return emptyList()
        val mapA = a.associateBy { "${it.table}/${it.key}" }
        val mapB = b.associateBy { "${it.table}/${it.key}" }
        return (mapA.keys + mapB.keys).sorted().mapNotNull { id ->
            val before = mapA[id]?.value
            val after = mapB[id]?.value
            if (before != after) {
                val (table, key) = id.split("/", limit = 2)
                Diff(table, key, before, after)
            } else {
                null
            }
        }
    }

    private fun queryTable(context: Context, table: String, uri: Uri): List<Entry> {
        val out = mutableListOf<Entry>()
        context.contentResolver.query(uri, arrayOf("name", "value"), null, null, null)?.use { c ->
            val iName = c.getColumnIndex("name")
            val iValue = c.getColumnIndex("value")
            while (c.moveToNext()) {
                val key = if (iName >= 0) c.getString(iName) else continue
                if (key != null && KEY_PATTERN.containsMatchIn(key)) {
                    out += Entry(table, key, if (iValue >= 0) c.getString(iValue) else null)
                }
            }
        } ?: throw IllegalStateException("query devolvió null")
        return out
    }

    private fun save(context: Context, label: String, entries: List<Entry>, errors: List<String>): String {
        val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val f = File(context.filesDir, "lights_scan_${label}_$ts.txt")
        try {
            f.writeText(
                buildString {
                    appendLine("# Escaneo $label  $ts  (${entries.size} claves)")
                    errors.forEach { appendLine("# ERROR $it") }
                    entries.forEach { appendLine("${it.table}\t${it.key}\t${it.value}") }
                },
            )
        } catch (t: Throwable) {
            EventLog.w(TAG, "No se guardó el escaneo", t)
        }
        return f.absolutePath
    }
}
