package com.george.antirrobo.fakeoff

import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.view.WindowManager
import com.george.antirrobo.data.AppPreferences
import com.george.antirrobo.nubia.NubiaHardwareController
import com.george.antirrobo.util.EventLog

/**
 * Silencia streams de audio, cancela vibración y baja el brillo.
 * El acceso a No Molestar (DND) es necesario para silenciar de verdad;
 * si no está concedido, se hace un mejor esfuerzo con AudioManager.
 *
 * El estado previo se persiste para poder restaurarlo aunque el proceso muera.
 */
class AudioAndLightsSilencer(private val context: Context) {

    private val prefs = AppPreferences.get(context)
    private val audio = context.getSystemService(AudioManager::class.java)
    private val notifications = context.getSystemService(NotificationManager::class.java)
    private val nubia = NubiaHardwareController(context)

    /**
     * [screenAndLights] = false (v0.1.6, "Bloqueo con luces"): solo No Molestar,
     * timbre en silencio, volúmenes y vibración; NO toca brillo ni luces.
     */
    fun silence(screenAndLights: Boolean = true) {
        // v0.1.1: si ya estábamos silenciados (re-entrada), NO se vuelve a tomar
        // la foto del estado: se sobrescribiría con "todo en silencio" y al
        // restaurar el teléfono quedaría mudo.
        if (!prefs.needsRestore) snapshot()
        prefs.needsRestore = true
        try {
            if (notifications?.isNotificationPolicyAccessGranted == true) {
                notifications.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE)
            } else {
                EventLog.w(TAG, "Sin acceso a No Molestar: el silencio será parcial")
            }
        } catch (t: Throwable) {
            EventLog.w(TAG, "No se pudo activar DND", t)
        }
        try {
            audio?.ringerMode = AudioManager.RINGER_MODE_SILENT
        } catch (t: Throwable) {
            EventLog.w(TAG, "No se pudo poner ringer silent (a menudo requiere DND)", t)
        }
        for (stream in MUTE_STREAMS) {
            try {
                audio?.adjustStreamVolume(
                    stream,
                    AudioManager.ADJUST_MUTE,
                    AudioManager.FLAG_REMOVE_SOUND_AND_VIBRATE,
                )
                audio?.setStreamVolume(stream, 0, AudioManager.FLAG_REMOVE_SOUND_AND_VIBRATE)
            } catch (t: Throwable) {
                EventLog.w(TAG, "No se silenció el stream $stream", t)
            }
        }
        cancelVibration()
        // v0.1.1: apaga hápticos de toque y sonidos de toque del sistema
        // (requiere "Modificar ajustes del sistema"). NO cubre la vibración
        // del sensor de huella ni la háptica que el OEM dispare a nivel HAL.
        trySetSystemInt(Settings.System.HAPTIC_FEEDBACK_ENABLED, 0)
        trySetSystemInt(Settings.System.SOUND_EFFECTS_ENABLED, 0)
        if (!screenAndLights) {
            EventLog.i(TAG, "Silencio para Bloqueo con luces (DND + timbre; brillo y luces intactos)")
            return
        }
        trySetSystemBrightness(0)
        // NO VERIFICADO en Red Magic 11 Pro: luces RGB (el ventilador ya no se toca desde v0.1.6).
        nubia.tryTurnOffRgbAndFan()
    }

    /**
     * v0.1.6: si "Bloqueo con luces" sigue activo (y no es [force]), solo se
     * regresan brillo/luces del apagado falso; el silencio (DND) se mantiene
     * hasta que se quite el bloqueo.
     */
    fun restoreIfNeeded(force: Boolean = false) {
        if (!prefs.needsRestore) return
        if (!force && com.george.antirrobo.lockmode.LockModeController.isActive) {
            val brightness = prefs.snapshotBrightness
            val mode = prefs.snapshotBrightnessMode
            if (mode >= 0) trySetBrightnessMode(mode)
            if (brightness >= 0) trySetSystemBrightness(brightness)
            nubia.tryRestoreRgbAndFan()
            EventLog.i(TAG, "Bloqueo con luces activo: se restauró brillo; No Molestar sigue")
            return
        }
        try {
            val dnd = prefs.snapshotDndFilter
            if (dnd >= 0 && notifications?.isNotificationPolicyAccessGranted == true) {
                notifications.setInterruptionFilter(dnd)
            }
        } catch (t: Throwable) {
            EventLog.w(TAG, "No se restauró DND", t)
        }
        try {
            val ringer = prefs.snapshotRingerMode
            if (ringer >= 0) audio?.ringerMode = ringer
        } catch (t: Throwable) {
            EventLog.w(TAG, "No se restauró ringer", t)
        }
        for (stream in MUTE_STREAMS) {
            val vol = prefs.getStreamVolume(stream)
            if (vol >= 0) {
                try {
                    audio?.adjustStreamVolume(
                        stream,
                        AudioManager.ADJUST_UNMUTE,
                        AudioManager.FLAG_REMOVE_SOUND_AND_VIBRATE,
                    )
                    audio?.setStreamVolume(stream, vol, AudioManager.FLAG_REMOVE_SOUND_AND_VIBRATE)
                } catch (t: Throwable) {
                    EventLog.w(TAG, "No se restauró el stream $stream", t)
                }
            }
        }
        if (prefs.snapshotHaptic >= 0) trySetSystemInt(Settings.System.HAPTIC_FEEDBACK_ENABLED, prefs.snapshotHaptic)
        if (prefs.snapshotSoundEffects >= 0) trySetSystemInt(Settings.System.SOUND_EFFECTS_ENABLED, prefs.snapshotSoundEffects)
        val brightness = prefs.snapshotBrightness
        val mode = prefs.snapshotBrightnessMode
        if (mode >= 0) trySetBrightnessMode(mode)
        if (brightness >= 0) trySetSystemBrightness(brightness)
        nubia.tryRestoreRgbAndFan()
        prefs.needsRestore = false
        EventLog.i(TAG, "Audio, vibración y brillo restaurados")
    }

    /** Brillo de la ventana del overlay (no requiere WRITE_SETTINGS). */
    fun applyWindowBrightnessOff(params: WindowManager.LayoutParams) {
        params.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_OFF
        if (Build.VERSION.SDK_INT >= 31) {
            @Suppress("DEPRECATION")
            params.buttonBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_OFF
        } else {
            @Suppress("DEPRECATION")
            params.buttonBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_OFF
        }
    }

    private fun snapshot() {
        try {
            prefs.snapshotRingerMode = audio?.ringerMode ?: -1
        } catch (_: Throwable) {
        }
        try {
            prefs.snapshotDndFilter = notifications?.currentInterruptionFilter ?: -1
        } catch (_: Throwable) {
        }
        for (stream in MUTE_STREAMS) {
            try {
                prefs.setStreamVolume(stream, audio?.getStreamVolume(stream) ?: -1)
            } catch (_: Throwable) {
            }
        }
        try {
            prefs.snapshotBrightness = Settings.System.getInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
                -1,
            )
            prefs.snapshotHaptic = Settings.System.getInt(
                context.contentResolver,
                Settings.System.HAPTIC_FEEDBACK_ENABLED,
                -1,
            )
            prefs.snapshotSoundEffects = Settings.System.getInt(
                context.contentResolver,
                Settings.System.SOUND_EFFECTS_ENABLED,
                -1,
            )
            prefs.snapshotBrightnessMode = Settings.System.getInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                -1,
            )
        } catch (_: Throwable) {
        }
    }

    private fun cancelVibration() {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= 31) {
                context.getSystemService(VibratorManager::class.java)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Vibrator::class.java)
            }
            vibrator?.cancel()
            if (Build.VERSION.SDK_INT >= 26) {
                // Un pulso de amplitud 0 no es necesario; cancel() basta.
                vibrator?.cancel()
            }
        } catch (t: Throwable) {
            EventLog.w(TAG, "No se canceló la vibración", t)
        }
    }

    private fun trySetSystemBrightness(value: Int) {
        if (!Settings.System.canWrite(context)) {
            EventLog.i(TAG, "Sin WRITE_SETTINGS: el brillo del sistema no se toca (sí el de la ventana)")
            return
        }
        try {
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
            )
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
                value.coerceIn(0, 255),
            )
        } catch (t: Throwable) {
            EventLog.w(TAG, "No se pudo escribir el brillo del sistema", t)
        }
    }

    private fun trySetSystemInt(key: String, value: Int) {
        if (!Settings.System.canWrite(context)) {
            EventLog.i(TAG, "Sin WRITE_SETTINGS: no se cambia $key")
            return
        }
        try {
            Settings.System.putInt(context.contentResolver, key, value)
        } catch (t: Throwable) {
            EventLog.w(TAG, "No se pudo escribir $key", t)
        }
    }

    private fun trySetBrightnessMode(mode: Int) {
        if (!Settings.System.canWrite(context)) return
        try {
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                mode,
            )
        } catch (t: Throwable) {
            EventLog.w(TAG, "No se restauró el modo de brillo", t)
        }
    }

    companion object {
        private const val TAG = "Silencer"

        val MUTE_STREAMS = intArrayOf(
            AudioManager.STREAM_MUSIC,
            AudioManager.STREAM_RING,
            AudioManager.STREAM_NOTIFICATION,
            AudioManager.STREAM_SYSTEM,
            AudioManager.STREAM_ALARM,
            AudioManager.STREAM_DTMF,
            AudioManager.STREAM_VOICE_CALL,
            AudioManager.STREAM_ACCESSIBILITY,
        )
    }
}
