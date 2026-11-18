package com.george.antirrobo.nubia

import android.content.Context
import android.provider.Settings
import com.george.antirrobo.util.EventLog
import java.io.File

/**
 * INTENTOS NO VERIFICADOS sobre el Red Magic 11 Pro (Nubia).
 *
 * Las luces RGB laterales y el ventilador trasero los controla el software de
 * Game Space / Game Genie / ajustes del sistema de Nubia. No hay API pública
 * documentada por el fabricante para apps de terceros.
 *
 * Este código es de MEJOR ESFUERZO. Las claves de Settings y las rutas sysfs
 * son CONJETURAS basadas en patrones habituales de Android y en nombres que
 * a veces aparecen en ROMs Nubia; NO afirmamos que existan en el Red Magic 11 Pro
 * ni que funcionen. Deben verificarse en el dispositivo real, por ejemplo:
 *
 *   adb shell settings list system
 *   adb shell settings list global
 *   adb shell settings list secure
 *   adb shell ls -l /sys/class/leds/
 *
 * Escribir en sysfs normalmente requiere root. Settings.System/Global puede
 * requerir WRITE_SETTINGS, permisos de firma o root, y muchas claves de OEM
 * están protegidas.
 *
 * No se inventan "hechos verificados" sobre el hardware interno de Nubia.
 */
class NubiaHardwareController(private val context: Context) {

    data class Attempt(val where: String, val keyOrPath: String, val result: String)

    private val attempts = mutableListOf<Attempt>()

    /**
     * Intenta apagar RGB y ventilador. Todos los caminos están etiquetados
     * como no verificados. Falla en silencio (queda en la bitácora).
     */
    fun tryTurnOffRgbAndFan() {
        attempts.clear()
        val prefs = com.george.antirrobo.data.AppPreferences.get(context)
        if (prefs.rgbKeyName.isNotBlank()) {
            attempts += Attempt("clave elegida en diagnóstico", prefs.rgbKeyName, setRgbConfigured(off = true))
        }
        EventLog.w(TAG, "INICIO de intentos NO VERIFICADOS de RGB/ventilador Nubia")
        tryGuessedSettings(off = true)
        tryGuessedSysfs(off = true)
        tryGuessedIntents(off = true)
        EventLog.w(TAG, "FIN intentos Nubia. Resultados: ${attempts.joinToString(" || ")}")
    }

    fun tryRestoreRgbAndFan() {
        val prefs = com.george.antirrobo.data.AppPreferences.get(context)
        if (prefs.rgbKeyName.isNotBlank()) setRgbConfigured(off = false)
        EventLog.w(TAG, "Restaurar RGB/ventilador: no hay snapshot fiable (API no pública). No se revierten claves OEM.")
    }

    fun lastAttempts(): List<Attempt> = attempts.toList()

    private fun tryGuessedSettings(off: Boolean) {
        // CONJETURAS. Nombres típicos que una ROM podría usar; pueden no existir.
        val guessedKeys = listOf(
            "nubia_led_on",
            "nubia_rgb_switch",
            "redmagic_rgb",
            "redmagic_led",
            "game_led_enable",
            "game_fan_enable",
            "nubia_fan_switch",
            "redmagic_fan",
            "led_side_enable",
            "breath_led",
            "notification_light",
        )
        val cr = context.contentResolver
        val tables = listOf("system", "global", "secure")
        for (key in guessedKeys) {
            for (table in tables) {
                val result = writeGuessedSetting(cr, table, key, if (off) "0" else "1")
                attempts += Attempt("Settings.$table (NO VERIFICADO)", key, result)
            }
        }
    }

    private fun writeGuessedSetting(
        cr: android.content.ContentResolver,
        table: String,
        key: String,
        value: String,
    ): String {
        return try {
            val existing = when (table) {
                "system" -> Settings.System.getString(cr, key)
                "global" -> Settings.Global.getString(cr, key)
                "secure" -> Settings.Secure.getString(cr, key)
                else -> null
            }
            if (existing == null) {
                "clave no presente (o sin permiso de lectura) — no se escribe a ciegas"
            } else {
                val written = when (table) {
                    "system" -> Settings.System.putString(cr, key, value)
                    "global" -> Settings.Global.putString(cr, key, value)
                    "secure" -> Settings.Secure.putString(cr, key, value)
                    else -> false
                }
                "existía='$existing' put='$value' ok=$written  [NO VERIFICADO: el efecto real se desconoce]"
            }
        } catch (t: Throwable) {
            "excepción: ${t.javaClass.simpleName}: ${t.message}"
        }
    }

    /**
     * Rutas sysfs CONJETURADAS. Sin root, open() casi siempre falla con EACCES.
     * No se afirma que estas rutas existan en el Red Magic 11 Pro.
     */
    private fun tryGuessedSysfs(off: Boolean) {
        val guessed = listOf(
            "/sys/class/leds/red/brightness",
            "/sys/class/leds/green/brightness",
            "/sys/class/leds/blue/brightness",
            "/sys/class/leds/nubia_led/brightness",
            "/sys/class/leds/redmagic/brightness",
            "/sys/class/leds/side_led/brightness",
            "/sys/kernel/fan/fan_speed",
            "/sys/class/thermal/cooling_device0/cur_state",
            "/sys/devices/platform/fan/fan_speed",
        )
        val value = if (off) "0" else "0"
        for (path in guessed) {
            val f = File(path)
            val result = try {
                if (!f.exists()) {
                    "no existe en este firmware"
                } else if (!f.canWrite()) {
                    "existe pero no se puede escribir (casi seguro hace falta root)"
                } else {
                    f.writeText(value)
                    "escritura de '$value' no lanzó excepción — EFECTO NO VERIFICADO"
                }
            } catch (t: Throwable) {
                "excepción: ${t.javaClass.simpleName}: ${t.message}"
            }
            attempts += Attempt("sysfs (NO VERIFICADO, suele requerir root)", path, result)
        }
    }

    /**
     * Intents CONJETURADOS hacia Game Space. Los action strings son inventados
     * como hipótesis y es muy probable que no existan. Se envían de forma
     * protegida (si no hay receptor, no pasa nada).
     */
    private fun tryGuessedIntents(off: Boolean) {
        EventLog.w(
            TAG,
            "No se envían intents a Game Space desde este prototipo: los action strings " +
                "serían conjeturas y podrían tener efectos no deseados. " +
                "Si en el dispositivo encuentras el componente real (adb shell dumpsys package), " +
                "puedes documentarlo y añadirlo de forma explícita. off=$off",
        )
        attempts += Attempt(
            "Intent Game Space (NO VERIFICADO)",
            "(no enviado a propósito)",
            "se evitó disparar intents con acciones inventadas",
        )
    }

    /**
     * v0.1.2: control de luces con la clave elegida en "Diagnóstico de luces".
     * Solo escribe una clave que el usuario confirmó con el escaneo A/B.
     * Devuelve un texto con el resultado para la bitácora / UI.
     */
    fun setRgbConfigured(off: Boolean): String {
        val prefs = com.george.antirrobo.data.AppPreferences.get(context)
        val table = prefs.rgbKeyTable
        val key = prefs.rgbKeyName
        if (table.isBlank() || key.isBlank()) {
            return "Sin clave de luces configurada (usa Diagnóstico de luces)"
        }
        return try {
            if (off) {
                val current = readSetting(context, table, key)
                // Guarda el valor actual para devolverlo al desbloquear.
                if (current != null && current != prefs.rgbOffValue) prefs.rgbSavedValue = current
                val ok = writeSetting(context, table, key, prefs.rgbOffValue)
                "Luces OFF: $table/$key '$current' → '${prefs.rgbOffValue}' ok=$ok"
            } else {
                val restore = prefs.rgbSavedValue.ifBlank { prefs.rgbOnValue }
                val ok = writeSetting(context, table, key, restore)
                "Luces restauradas: $table/$key → '$restore' ok=$ok"
            }
        } catch (t: Throwable) {
            "Error con $table/$key: ${t.javaClass.simpleName}: ${t.message} " +
                "(probablemente falta WRITE_SECURE_SETTINGS vía adb)"
        }.also { EventLog.i(TAG, it) }
    }

    companion object {
        private const val TAG = "NubiaHW"

        /** Nombres CONJETURADOS (no verificados). */
        val GUESSED_KEYS = listOf(
            "nubia_led_on", "nubia_rgb_switch", "redmagic_rgb", "redmagic_led",
            "game_led_enable", "game_fan_enable", "nubia_fan_switch", "redmagic_fan",
            "led_side_enable", "breath_led", "notification_light",
        )

        fun readSetting(context: Context, table: String, key: String): String? {
            val cr = context.contentResolver
            return when (table) {
                "system" -> Settings.System.getString(cr, key)
                "secure" -> Settings.Secure.getString(cr, key)
                "global" -> Settings.Global.getString(cr, key)
                else -> null
            }
        }

        /**
         * Settings.System: claves de OEM normalmente NO se pueden escribir solo con
         * WRITE_SETTINGS (Android solo deja las públicas). Secure/Global exigen
         * WRITE_SECURE_SETTINGS, que se concede con adb:
         *   adb shell pm grant com.george.antirrobo android.permission.WRITE_SECURE_SETTINGS
         */
        fun writeSetting(context: Context, table: String, key: String, value: String): Boolean {
            val cr = context.contentResolver
            return when (table) {
                "system" -> Settings.System.putString(cr, key, value)
                "secure" -> Settings.Secure.putString(cr, key, value)
                "global" -> Settings.Global.putString(cr, key, value)
                else -> false
            }
        }
    }
}
