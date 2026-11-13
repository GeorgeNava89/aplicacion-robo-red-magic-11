package com.george.antirrobo.policy

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import com.george.antirrobo.util.EventLog

/**
 * Receptor de administrador del dispositivo. Solo tiene efecto útil si la
 * app se aprovisiona como Device Owner con adb (ver README y DeviceOwnerHelper).
 *
 * Esto NO intercepta el botón de encendido. Es una ruta opcional, documentada,
 * que en algunos firmwares puede limitar el menú de apagado o el keyguard.
 */
class AntirroboDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        EventLog.init(context)
        EventLog.i(TAG, "Device admin habilitado")
    }

    override fun onDisabled(context: Context, intent: Intent) {
        EventLog.init(context)
        EventLog.i(TAG, "Device admin deshabilitado")
    }

    companion object {
        private const val TAG = "DeviceAdmin"
    }
}
