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

    var snapshotHaptic: Int
        get() = prefs.getInt(KEY_HAPTIC, -1)
        set(value) = prefs.edit { putInt(KEY_HAPTIC, value) }

    var snapshotSoundEffects: Int
        get() = prefs.getInt(KEY_SOUND_FX, -1)
        set(value) = prefs.edit { putInt(KEY_SOUND_FX, value) }

    /**
     * v0.1.1: SCREEN_OFF (bloqueo normal / tiempo de pantalla) ya NO entra a
     * apagado falso. Queda como opción, apagada por defecto.
     */
    var screenOffTrigger: Boolean
        get() = prefs.getBoolean(KEY_SCREEN_OFF_TRIGGER, false)
        set(value) = prefs.edit { putBoolean(KEY_SCREEN_OFF_TRIGGER, value) }

    /** v0.1.1: huella silenciosa EXPERIMENTAL (FingerprintManager sin UI propia). Apagada por defecto. */
    var fingerprintSilentAuth: Boolean
        get() = prefs.getBoolean(KEY_FP_SILENT, false)
        set(value) = prefs.edit { putBoolean(KEY_FP_SILENT, value) }

    /** v0.1.2: apagar luces RGB al bloquear (encendido por defecto). */
    var rgbOffOnLock: Boolean
        get() = prefs.getBoolean("rgb_off_on_lock", true)
        set(value) = prefs.edit { putBoolean("rgb_off_on_lock", value) }

    var rgbKeyTable: String
        get() = prefs.getString("rgb_key_table", "") ?: ""
        set(value) = prefs.edit { putString("rgb_key_table", value) }

    var rgbKeyName: String
        get() = prefs.getString("rgb_key_name", "") ?: ""
        set(value) = prefs.edit { putString("rgb_key_name", value) }

    var rgbOnValue: String
        get() = prefs.getString("rgb_on_value", "1") ?: "1"
        set(value) = prefs.edit { putString("rgb_on_value", value) }

    var rgbOffValue: String
        get() = prefs.getString("rgb_off_value", "0") ?: "0"
        set(value) = prefs.edit { putString("rgb_off_value", value) }

    /** Valor real antes de apagarlas por bloqueo, para devolverlo igual. */
    var rgbSavedValue: String
        get() = prefs.getString("rgb_saved_value", "") ?: ""
        set(value) = prefs.edit { putString("rgb_saved_value", value) }

    var rgbOffByLock: Boolean
        get() = prefs.getBoolean("rgb_off_by_lock", false)
        set(value) = prefs.edit { putBoolean("rgb_off_by_lock", value) }

    val lightsFeatureActive: Boolean
        get() = rgbOffOnLock && rgbKeyName.isNotBlank()

    /** v0.1.3: true = menú falso + animación (default); false = directo a negro. */
    var decoyPowerMenu: Boolean
        get() = prefs.getBoolean("decoy_power_menu", true)
        set(value) = prefs.edit { putBoolean("decoy_power_menu", value) }

    /** v0.1.3: "Mostrar gestos fantasma" (solo en pruebas) y sub-opción "también en modo real". */
    /** v0.1.4: estilo del menú falso: "system" (igual al Red Magic, por defecto) o "classic" (v0.1.3). */
    var decoyStyle: String
        get() = prefs.getString("decoy_style", "system") ?: "system"
        set(value) = prefs.edit { putString("decoy_style", value) }

    /** v0.1.4: "Bloqueo con luces" activo (sobrevive reinicios del servicio). */
    var lockModeActive: Boolean
        get() = prefs.getBoolean("lock_mode_active", false)
        set(value) = prefs.edit { putBoolean("lock_mode_active", value) }

    /** v0.1.4: permitir el PIN/patrón del sistema como respaldo de la huella. */
    var lockModeAllowPin: Boolean
        get() = prefs.getBoolean("lock_mode_allow_pin", true)
        set(value) = prefs.edit { putBoolean("lock_mode_allow_pin", value) }

    /** v0.1.5: "Franja de luz" — apagar (solo si está encendida). Sin auto-restaurar. */
    var stripOffOnFakeOff: Boolean
        get() = prefs.getBoolean("strip_off_fakeoff", false)
        set(value) = prefs.edit { putBoolean("strip_off_fakeoff", value) }

    var stripOffOnLockMode: Boolean
        get() = prefs.getBoolean("strip_off_lockmode", false)
        set(value) = prefs.edit { putBoolean("strip_off_lockmode", value) }

    var stripLabels: String
        get() = prefs.getString("strip_labels", null)
            ?: com.george.antirrobo.lightstrip.LightStripLogic.DEFAULT_LABELS
        set(value) = prefs.edit { putString("strip_labels", value) }

    var ghostGuides: Boolean
        get() = prefs.getBoolean("ghost_guides", false)
        set(value) = prefs.edit { putBoolean("ghost_guides", value) }

    var ghostGuidesReal: Boolean
        get() = prefs.getBoolean("ghost_guides_real", false)
        set(value) = prefs.edit { putBoolean("ghost_guides_real", value) }

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
        private const val KEY_HAPTIC = "snap_haptic"
        private const val KEY_SOUND_FX = "snap_sound_fx"
        private const val KEY_SCREEN_OFF_TRIGGER = "screen_off_trigger"
        private const val KEY_FP_SILENT = "fingerprint_silent_auth"
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
