package com.george.antirrobo.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.george.antirrobo.data.AppPreferences
import com.george.antirrobo.fakeoff.AudioAndLightsSilencer
import com.george.antirrobo.service.AntirroboForegroundService
import com.george.antirrobo.util.EventLog

/**
 * Tras un reinicio, restaura audio si hacía falta y, si el modo seguía
 * armado, vuelve a arrancar el servicio en primer plano.
 *
 * En Nubia esto suele fallar si la app no está en la lista de inicio
 * automático (ver README). El reinicio forzado por hardware (power+volumen
 * ~10 s) no se puede bloquear; después de ese reinicio, este receiver es
 * el que intenta rearmar.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        EventLog.init(context)
        EventLog.i(TAG, "BootReceiver: ${intent.action}")
        val prefs = AppPreferences.get(context)
        if (prefs.needsRestore) {
            AudioAndLightsSilencer(context).restoreIfNeeded()
        }
        if (prefs.armed) {
            AntirroboForegroundService.start(context)
        }
    }

    companion object {
        private const val TAG = "BootRx"
    }
}
