package com.george.antirrobo.nubia

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.george.antirrobo.data.AppPreferences
import com.george.antirrobo.util.EventLog

/**
 * v0.1.2 — apaga las luces RGB cuando el teléfono se BLOQUEA (bloqueo normal)
 * y las devuelve al estado anterior al desbloquear.
 *
 * Detección de bloqueo (sin root):
 *  - ACTION_SCREEN_OFF → se revisa KeyguardManager.isKeyguardLocked() /
 *    isDeviceLocked() a los 0.5, 3, 10 y 30 s (por el "bloquear después de X s").
 *  - ACTION_USER_PRESENT → desbloqueado (huella, PIN, deslizar) → se restauran.
 *  - ACTION_SCREEN_ON con keyguard NO bloqueado (p. ej. Smart Lock) → se restauran.
 * Los broadcasts se registran en tiempo de ejecución en el servicio en primer plano.
 *
 * El control real de las luces es la clave elegida en "Diagnóstico de luces";
 * sin esa clave no se hace nada (no se escribe a ciegas).
 */
class LockLightsController(private val context: Context) {

    private val main = Handler(Looper.getMainLooper())
    private val prefs = AppPreferences.get(context)
    private val km = context.getSystemService(KeyguardManager::class.java)
    private val nubia = NubiaHardwareController(context)

    fun onBroadcast(action: String) {
        if (!prefs.lightsFeatureActive) return
        when (action) {
            Intent.ACTION_SCREEN_OFF -> {
                main.removeCallbacksAndMessages(null)
                for (delay in CHECK_DELAYS_MS) {
                    main.postDelayed({ checkLocked("SCREEN_OFF+${delay}ms") }, delay)
                }
            }
            Intent.ACTION_USER_PRESENT -> {
                main.removeCallbacksAndMessages(null)
                restore("USER_PRESENT")
            }
            Intent.ACTION_SCREEN_ON -> {
                if (km?.isKeyguardLocked == false) {
                    main.removeCallbacksAndMessages(null)
                    restore("SCREEN_ON sin keyguard")
                }
            }
        }
    }

    fun release() {
        main.removeCallbacksAndMessages(null)
    }

    private fun checkLocked(source: String) {
        if (prefs.rgbOffByLock) return
        val locked = km?.isKeyguardLocked == true || km?.isDeviceLocked == true
        if (locked) {
            val r = nubia.setRgbConfigured(off = true)
            prefs.rgbOffByLock = true
            EventLog.i(TAG, "Bloqueo detectado ($source): $r")
        }
    }

    private fun restore(source: String) {
        if (!prefs.rgbOffByLock) return
        val r = nubia.setRgbConfigured(off = false)
        prefs.rgbOffByLock = false
        EventLog.i(TAG, "Desbloqueo ($source): $r")
    }

    companion object {
        private const val TAG = "LockLights"
        private val CHECK_DELAYS_MS = longArrayOf(500L, 3_000L, 10_000L, 30_000L)
    }
}
