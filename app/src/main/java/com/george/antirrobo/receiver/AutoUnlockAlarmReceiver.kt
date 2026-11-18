package com.george.antirrobo.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.george.antirrobo.fakeoff.AudioAndLightsSilencer
import com.george.antirrobo.fakeoff.FakeOffController
import com.george.antirrobo.util.EventLog

/**
 * Capa 4 del auto-desbloqueo: la alarma ELAPSED_REALTIME_WAKEUP despierta
 * la CPU y llega aquí aunque el teléfono esté en Doze. Sale del apagado
 * falso y, si el proceso se había reiniciado, al menos restaura audio/brillo/hápticos.
 */
class AutoUnlockAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_AUTO_UNLOCK) return
        EventLog.init(context)
        EventLog.i(TAG, "Alarma de auto-desbloqueo recibida")
        val pending = goAsync()
        try {
            FakeOffController.exit("auto-desbloqueo (alarma)")
            AudioAndLightsSilencer(context).restoreIfNeeded()
        } catch (t: Throwable) {
            EventLog.e(TAG, "Fallo en la alarma de auto-desbloqueo", t)
        } finally {
            pending.finish()
        }
    }

    companion object {
        private const val TAG = "AutoUnlockAlarm"
        const val ACTION_AUTO_UNLOCK = "com.george.antirrobo.action.AUTO_UNLOCK"
    }
}
