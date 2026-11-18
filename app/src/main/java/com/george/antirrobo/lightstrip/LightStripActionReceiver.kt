package com.george.antirrobo.lightstrip

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.george.antirrobo.util.EventLog

/** Acción "Apagar luces" de la notificación del servicio (v0.1.5). */
class LightStripActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        EventLog.init(context)
        // Se espera un poco a que se cierre el panel de notificaciones del toque.
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            LightStripToggler.turnOffIfOn("notificación")
        }, 600L)
    }

    companion object {
        const val ACTION = "com.george.antirrobo.action.LIGHT_STRIP_OFF"
    }
}
