package com.george.antirrobo

import android.app.Application
import android.util.Log
import com.george.antirrobo.data.AppPreferences
import com.george.antirrobo.data.AppStatus
import com.george.antirrobo.fakeoff.AudioAndLightsSilencer
import com.george.antirrobo.util.EventLog

/**
 * Punto de entrada del proceso. Si el proceso murió a mitad de un apagado falso,
 * restauramos audio y brillo para que el teléfono nunca quede silenciado o a oscuras
 * de forma permanente.
 */
class AntirroboApp : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        val prefs = AppPreferences.get(this)
        EventLog.init(this)
        AppStatus.refreshFromPrefs(prefs)
        if (prefs.needsRestore) {
            Log.w(TAG, "Recuperando audio/brillo tras un cierre inesperado")
            AudioAndLightsSilencer(this).restoreIfNeeded()
        }
    }

    companion object {
        private const val TAG = "AntirroboApp"

        @Volatile
        lateinit var instance: AntirroboApp
            private set
    }
}
