package com.george.antirrobo.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Preferencias del prototipo. Valores por defecto acordes al brief:
 * red de seguridad de 10 segundos, intervalo de ubicación de 60 s.
 */
class AppPreferences private constructor(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var armed: Boolean
        get() = prefs.getBoolean(KEY_ARMED, false)
        set(value) = prefs.edit { putBoolean(KEY_ARMED, value) }

    /** Timeout de auto-desbloqueo, en segundos. Default 10. Rango 5–1800 (30 min). */
    var autoUnlockSeconds: Int
        get() = prefs.getInt(KEY_TIMEOUT, DEFAULT_TIMEOUT_SECONDS).coerceIn(MIN_TIMEOUT, MAX_TIMEOUT)
        set(value) = prefs.edit { putInt(KEY_TIMEOUT, value.coerceIn(MIN_TIMEOUT, MAX_TIMEOUT)) }

    var webhookUrl: String
        get() = prefs.getString(KEY_WEBHOOK, "") ?: ""
        set(value) = prefs.edit { putString(KEY_WEBHOOK, value.trim()) }

    var locationIntervalSeconds: Int
        get() = prefs.getInt(KEY_LOC_INTERVAL, DEFAULT_LOC_INTERVAL).coerceIn(MIN_LOC, MAX_LOC)
        set(value) = prefs.edit { putInt(KEY_LOC_INTERVAL, value.coerceIn(MIN_LOC, MAX_LOC)) }

    var lastLocationText: String
        get() = prefs.getString(KEY_LAST_LOC, "—") ?: "—"
        set(value) = prefs.edit { putString(KEY_LAST_LOC, value) }

    var lastLocationTimeMs: Long
        get() = prefs.getLong(KEY_LAST_LOC_TIME, 0L)
        set(value) = prefs.edit { putLong(KEY_LAST_LOC_TIME, value) }

    /** Si está activo, se registran todos los TYPE_WINDOW_STATE_CHANGED para identificar el menú de apagado de Nubia. */
    var diagnosticWindows: Boolean
        get() = prefs.getBoolean(KEY_DIAG, false)
        set(value) = prefs.edit { putBoolean(KEY_DIAG, value) }

    var needsRestore: Boolean
        get() = prefs.getBoolean(KEY_NEEDS_RESTORE, false)
        set(value) = prefs.edit { putBoolean(KEY_NEEDS_RESTORE, value) }

    var snapshotRingerMode: Int
        get() = prefs.getInt(KEY_RINGER, -1)
        set(value) = prefs.edit { putInt(KEY_RINGER, value) }

    var snapshotDndFilter: Int
        get() = prefs.getInt(KEY_DND, -1)
        set(value) = prefs.edit { putInt(KEY_DND, value) }

    var snapshotBrightness: Int
        get() = prefs.getInt(KEY_BRIGHTNESS, -1)
        set(value) = prefs.edit { putInt(KEY_BRIGHTNESS, value) }

    var snapshotBrightnessMode: Int
        get() = prefs.getInt(KEY_BRIGHTNESS_MODE, -1)
        set(value) = prefs.edit { putInt(KEY_BRIGHTNESS_MODE, value) }

    fun streamVolumeKey(stream: Int): String = "vol_stream_$stream"

    fun getStreamVolume(stream: Int): Int = prefs.getInt(streamVolumeKey(stream), -1)

    fun setStreamVolume(stream: Int, volume: Int) {
        prefs.edit { putInt(streamVolumeKey(stream), volume) }
    }

    fun register(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregister(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
    }

    companion object {
        const val DEFAULT_TIMEOUT_SECONDS = 10
        const val MIN_TIMEOUT = 5
        const val MAX_TIMEOUT = 1800
        const val DEFAULT_LOC_INTERVAL = 60
        const val MIN_LOC = 15
        const val MAX_LOC = 3600

        private const val PREFS = "antirrobo_prefs"
        private const val KEY_ARMED = "armed"
        private const val KEY_TIMEOUT = "auto_unlock_seconds"
        private const val KEY_WEBHOOK = "webhook_url"
        private const val KEY_LOC_INTERVAL = "location_interval_seconds"
        private const val KEY_LAST_LOC = "last_location_text"
        private const val KEY_LAST_LOC_TIME = "last_location_time"
        private const val KEY_DIAG = "diagnostic_windows"
        private const val KEY_NEEDS_RESTORE = "needs_restore"
        private const val KEY_RINGER = "snap_ringer"
        private const val KEY_DND = "snap_dnd"
        private const val KEY_BRIGHTNESS = "snap_brightness"
        private const val KEY_BRIGHTNESS_MODE = "snap_brightness_mode"

        @Volatile
        private var instance: AppPreferences? = null

        fun get(context: Context): AppPreferences {
            return instance ?: synchronized(this) {
                instance ?: AppPreferences(context.applicationContext).also { instance = it }
            }
        }
    }
}
