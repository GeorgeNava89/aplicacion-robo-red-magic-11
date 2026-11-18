package com.george.antirrobo.unlock

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.hardware.fingerprint.FingerprintManager
import android.os.Bundle
import android.os.CancellationSignal
import android.view.View
import android.view.WindowManager
import com.george.antirrobo.fakeoff.FakeOffController
import com.george.antirrobo.util.EventLog
import java.lang.ref.WeakReference

/**
 * EXPERIMENTAL — apagado por defecto (Ajustes → "Huella silenciosa").
 *
 * Objetivo: que la huella del dueño saque del apagado falso SIN mostrar
 * BiometricPrompt (que dibuja su propia hoja/animación). Para eso se usa la
 * API vieja FingerprintManager.authenticate(), que no tiene UI propia,
 * desde una actividad negra que queda DEBAJO del overlay de accesibilidad
 * (Android exige que quien autentica sea la actividad en primer plano).
 *
 * LÍMITES HONESTOS (no verificados en el Red Magic 11 Pro):
 *  - FingerprintManager está deprecado desde API 28; algunos firmwares lo
 *    rechazan o lo redirigen.
 *  - En sensores bajo pantalla (UDFPS), SystemUI puede dibujar su propio
 *    círculo/iluminación y vibrar al autenticar, aunque la app no muestre nada.
 *    Eso lo dibuja el sistema por encima o fuera de nuestra ventana y sin root
 *    no se puede ocultar.
 *  - Si no funciona en tu firmware, déjalo apagado: el desbloqueo por gesto,
 *    el auto-desbloqueo y USER_PRESENT siguen funcionando.
 */
@Suppress("DEPRECATION")
class FingerprintUnlockActivity : Activity() {

    private var cancel: CancellationSignal? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        current = WeakReference(this)
        setShowWhenLocked(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.setBackgroundColor(Color.BLACK)
        window.decorView.isHapticFeedbackEnabled = false
        window.decorView.isSoundEffectsEnabled = false
        setContentView(View(this).apply { setBackgroundColor(Color.BLACK) })
        overridePendingTransition(0, 0)
    }

    override fun onResume() {
        super.onResume()
        startAuth()
    }

    override fun onPause() {
        super.onPause()
        cancel?.cancel()
        cancel = null
    }

    override fun onDestroy() {
        cancel?.cancel()
        if (current?.get() === this) current = null
        super.onDestroy()
    }

    private fun startAuth() {
        if (!FakeOffController.isActive) {
            finish()
            return
        }
        try {
            val fm = getSystemService(Context.FINGERPRINT_SERVICE) as? FingerprintManager
            if (fm == null || !fm.isHardwareDetected || !fm.hasEnrolledFingerprints()) {
                EventLog.w(TAG, "FingerprintManager no disponible o sin huellas")
                finish()
                return
            }
            val signal = CancellationSignal()
            cancel = signal
            fm.authenticate(null, signal, 0, object : FingerprintManager.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: FingerprintManager.AuthenticationResult?) {
                    EventLog.i(TAG, "Huella válida: saliendo del apagado falso")
                    FakeOffController.exit("huella (experimental, sin BiometricPrompt)")
                    finish()
                }

                override fun onAuthenticationFailed() {
                    EventLog.i(TAG, "Huella no reconocida (el auto-desbloqueo sigue)")
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                    EventLog.w(TAG, "Error de huella $errorCode: $errString")
                }
            }, null)
            EventLog.i(TAG, "Escucha de huella (experimental) activa")
        } catch (t: Throwable) {
            EventLog.w(TAG, "FingerprintManager.authenticate falló", t)
            finish()
        }
    }

    companion object {
        private const val TAG = "FingerprintExp"
        private var current: WeakReference<FingerprintUnlockActivity>? = null

        fun finishIfShowing() {
            current?.get()?.let {
                it.finish()
                it.overridePendingTransition(0, 0)
            }
            current = null
        }
    }
}
