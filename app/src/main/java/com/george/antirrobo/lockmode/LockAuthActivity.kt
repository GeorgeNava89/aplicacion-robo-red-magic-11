package com.george.antirrobo.lockmode

import android.app.Activity
import android.app.KeyguardManager
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import com.george.antirrobo.data.AppPreferences
import com.george.antirrobo.util.EventLog
import java.lang.ref.WeakReference

/**
 * v0.1.4 — Huella para salir de "Bloqueo con luces". Aquí SÍ se usa un
 * BiometricPrompt visible (lo pidió el dueño). Pantalla negra completa detrás,
 * para que no se vea lo que hay debajo mientras aparece el diálogo.
 * Cualquier error / cancelación / tecla Inicio = se vuelve a bloquear.
 */
class LockAuthActivity : Activity() {

    enum class Availability { OK, NO_HARDWARE, NONE_ENROLLED, ERROR }

    private var cancel: CancellationSignal? = null
    private var reported = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        current = WeakReference(this)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        val allowPin = AppPreferences.get(this).lockModeAllowPin
        val b = BiometricPrompt.Builder(this)
            .setTitle("Desbloquear")
            .setSubtitle("Pon tu huella")
        if (Build.VERSION.SDK_INT >= 30) {
            var auth = BiometricManager.Authenticators.BIOMETRIC_WEAK
            if (allowPin) auth = auth or BiometricManager.Authenticators.DEVICE_CREDENTIAL
            b.setAllowedAuthenticators(auth)
            if (!allowPin) b.setNegativeButton("Cancelar", mainExecutor) { _, _ -> report(false, "cancelado") }
        } else {
            @Suppress("DEPRECATION")
            if (allowPin) b.setDeviceCredentialAllowed(true)
            else b.setNegativeButton("Cancelar", mainExecutor) { _, _ -> report(false, "cancelado") }
        }
        val c = CancellationSignal()
        cancel = c
        try {
            b.build().authenticate(c, mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) {
                    report(true, "ok")
                }
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                    report(false, "error $errorCode: $errString")
                }
                override fun onAuthenticationFailed() {
                    EventLog.i(TAG, "Huella no reconocida (el diálogo sigue abierto)")
                }
            })
        } catch (t: Throwable) {
            EventLog.e(TAG, "BiometricPrompt falló", t)
            report(false, "excepción ${t.javaClass.simpleName}")
        }
    }

    private fun report(ok: Boolean, detail: String) {
        if (reported) return
        reported = true
        LockModeController.onAuthResult(ok, detail)
        finish()
        overridePendingTransition(0, 0)
    }

    override fun onDestroy() {
        if (!reported) {
            cancel?.cancel()
            reported = true
            LockModeController.onAuthResult(false, "actividad cerrada")
        }
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        report(false, "atrás")
    }

    companion object {
        private const val TAG = "LockAuth"
        private var current: WeakReference<LockAuthActivity>? = null

        fun finishCurrent() {
            current?.get()?.let { it.cancel?.cancel(); it.report(false, "tiempo agotado") }
        }

        fun availability(ctx: android.content.Context, allowPin: Boolean): Availability {
            return try {
                val bm = ctx.getSystemService(BiometricManager::class.java) ?: return Availability.ERROR
                val code = if (Build.VERSION.SDK_INT >= 30) {
                    var auth = BiometricManager.Authenticators.BIOMETRIC_WEAK
                    if (allowPin) auth = auth or BiometricManager.Authenticators.DEVICE_CREDENTIAL
                    bm.canAuthenticate(auth)
                } else {
                    @Suppress("DEPRECATION")
                    bm.canAuthenticate()
                }
                when (code) {
                    BiometricManager.BIOMETRIC_SUCCESS -> Availability.OK
                    BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> {
                        val km = ctx.getSystemService(KeyguardManager::class.java)
                        if (allowPin && km?.isDeviceSecure == true) Availability.OK else Availability.NONE_ENROLLED
                    }
                    BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> Availability.NO_HARDWARE
                    else -> Availability.ERROR
                }
            } catch (_: Throwable) {
                Availability.ERROR
            }
        }
    }
}
