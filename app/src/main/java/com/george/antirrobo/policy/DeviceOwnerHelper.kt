package com.george.antirrobo.policy

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.UserManager
import com.george.antirrobo.data.AppStatus
import com.george.antirrobo.util.EventLog

/**
 * Ruta Device Owner (opcional). Se aprovisiona así, en UN DISPOSITIVO DE TU
 * PROPIEDAD, normalmente tras un restablecimiento de fábrica si ya hay cuenta:
 *
 *   adb shell dpm set-device-owner com.george.antirrobo/.policy.AntirroboDeviceAdminReceiver
 *
 * LÍMITES HONESTOS:
 *  - Device Owner NO intercepta KEYCODE_POWER antes que el sistema.
 *  - No existe una API pública fiable tipo "DISALLOW_SHUTDOWN" para una app
 *    de terceros en Android 14/15 de consumo.
 *  - setKeyguardDisabledFeatures, setStatusBarDisabled y lock-task pueden
 *    limitar el keyguard o la barra, y en ALGUNOS firmwares el menú de
 *    apagado se restringe en lock-task. Debe verificarse en el Red Magic.
 *  - El reinicio forzado por hardware (encendido + volumen ~10 s) es de
 *    nivel de hardware/bootloader y nadie (ni Device Owner) lo bloquea.
 *
 * setGlobalSetting está restringido / deprecado para casi todas las claves.
 */
class DeviceOwnerHelper(private val context: Context) {

    private val dpm = context.getSystemService(DevicePolicyManager::class.java)
    val admin: ComponentName = ComponentName(context, AntirroboDeviceAdminReceiver::class.java)

    fun isDeviceOwner(): Boolean {
        val ok = dpm?.isDeviceOwnerApp(context.packageName) == true
        AppStatus.setDeviceOwner(ok)
        return ok
    }

    fun isAdminActive(): Boolean = dpm?.isAdminActive(admin) == true

    /**
     * Intenta aplicar restricciones útiles durante el modo armado.
     * Solo tiene efecto si somos Device Owner. Devuelve un resumen para la UI.
     */
    fun applyArmedRestrictions(): String {
        if (!isDeviceOwner()) {
            return "No somos Device Owner. Nada que aplicar. Ver README para el aprovisionamiento con adb."
        }
        val notes = mutableListOf<String>()
        try {
            dpm?.setKeyguardDisabledFeatures(
                admin,
                DevicePolicyManager.KEYGUARD_DISABLE_FEATURES_NONE,
            )
            notes += "setKeyguardDisabledFeatures llamado (FEATURES_NONE: no bloqueamos el keyguard de forma agresiva por defecto)"
        } catch (t: Throwable) {
            notes += "setKeyguardDisabledFeatures: ${t.message}"
            EventLog.w(TAG, "setKeyguardDisabledFeatures", t)
        }
        try {
            if (Build.VERSION.SDK_INT >= 28) {
                // Desactivar la barra de estado puede ayudar a que no aparezcan paneles.
                // Se deja documentado; no se activa por defecto porque afecta el uso normal
                // del teléfono mientras está armado pero no en apagado falso.
                notes += "setStatusBarDisabled está disponible (API 28+) y NO se activa por defecto (afectaría el uso normal). Se puede llamar desde una prueba manual."
            }
        } catch (t: Throwable) {
            notes += "statusBar: ${t.message}"
        }
        try {
            dpm?.addUserRestriction(admin, UserManager.DISALLOW_SAFE_BOOT)
            dpm?.addUserRestriction(admin, UserManager.DISALLOW_FACTORY_RESET)
            notes += "Restricciones añadidas: DISALLOW_SAFE_BOOT, DISALLOW_FACTORY_RESET"
        } catch (t: Throwable) {
            notes += "user restrictions: ${t.message}"
            EventLog.w(TAG, "addUserRestriction", t)
        }
        try {
            if (Build.VERSION.SDK_INT >= 28) {
                dpm?.setLockTaskPackages(admin, arrayOf(context.packageName))
                notes += "setLockTaskPackages configurado. Lock-task se inicia desde una Activity, no desde aquí. En algunos firmwares limita el menú de apagado; hay que verificarlo."
            }
        } catch (t: Throwable) {
            notes += "lock task: ${t.message}"
            EventLog.w(TAG, "setLockTaskPackages", t)
        }
        EventLog.i(TAG, "applyArmedRestrictions: ${notes.joinToString(" | ")}")
        return notes.joinToString("\n")
    }

    fun clearRestrictions(): String {
        if (!isDeviceOwner()) return "No somos Device Owner."
        val notes = mutableListOf<String>()
        try {
            dpm?.clearUserRestriction(admin, UserManager.DISALLOW_SAFE_BOOT)
            dpm?.clearUserRestriction(admin, UserManager.DISALLOW_FACTORY_RESET)
            notes += "Restricciones de usuario limpiadas"
        } catch (t: Throwable) {
            notes += t.message ?: "error"
        }
        try {
            dpm?.setLockTaskPackages(admin, emptyArray())
            notes += "Lock-task packages vacíos"
        } catch (t: Throwable) {
            notes += t.message ?: "error"
        }
        return notes.joinToString("\n")
    }

    fun statusText(): String {
        return buildString {
            append("Device Owner: "); append(if (isDeviceOwner()) "sí" else "no")
            append("  |  Admin activo: "); append(if (isAdminActive()) "sí" else "no")
        }
    }

    companion object {
        private const val TAG = "DeviceOwner"
    }
}
