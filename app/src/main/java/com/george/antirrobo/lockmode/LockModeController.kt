package com.george.antirrobo.lockmode

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.george.antirrobo.data.AppPreferences
import com.george.antirrobo.data.AppStatus
import com.george.antirrobo.util.EventLog
import java.lang.ref.WeakReference

/**
 * v0.1.4 — "Bloqueo con luces".
 *
 * Un NO-root no puede reemplazar ni modificar el keyguard del sistema. Lo que
 * sí se puede: una ventana TYPE_ACCESSIBILITY_OVERLAY a pantalla completa que
 * tapa todo (pantalla encendida y atenuada, FLAG_KEEP_SCREEN_ON), así el
 * sistema NO entra a su bloqueo y las luces RGB no se apagan por eso.
 *
 * Única salida: secuencia de 5 toques (las mismas zonas que el apagado real)
 * → huella (BiometricPrompt visible, con PIN del sistema opcional como
 * respaldo) → desbloqueo. Si no hay huella ni PIN configurados en el
 * teléfono, el gesto solo desbloquea (para no dejarte afuera).
 */
object LockModeController {

    private const val TAG = "LockMode"
    private const val AUTH_TIMEOUT_MS = 60_000L
    private const val DEMO_MS = 30_000L
    private const val EMERGENCY_RELOCK_MS = 90_000L

    private val main = Handler(Looper.getMainLooper())
    private var serviceRef: WeakReference<AccessibilityService>? = null
    private var overlay: LockOverlay? = null
    private var demo = false

    @Volatile var isActive = false
        private set
    @Volatile var isAuthenticating = false
        private set

    /** Bloqueado y con nuestra ventana encima (no durante la huella ni emergencia). */
    val isCovering: Boolean get() = isActive && overlay != null

    fun bind(service: AccessibilityService) {
        serviceRef = WeakReference(service)
        // Si estaba activo (reinicio del teléfono o del servicio), se vuelve a bloquear.
        if (AppPreferences.get(service).lockModeActive) {
            main.post { activate(service, demo = false, reason = "restaurado al conectar el servicio") }
        }
    }

    fun unbind() {
        removeOverlay()
        serviceRef = null
    }

    fun activate(context: Context, demo: Boolean, reason: String = "ajustes"): Boolean {
        val service = serviceRef?.get()
        if (service == null) {
            AppStatus.setLastEvent("Bloqueo: activa primero el servicio de accesibilidad")
            return false
        }
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { activate(context, demo, reason) }
            return true
        }
        val prefs = AppPreferences.get(service)
        this.demo = demo
        if (!demo) prefs.lockModeActive = true
        isActive = true
        isAuthenticating = false
        main.removeCallbacksAndMessages(null)
        if (demo) main.postDelayed({ deactivate("fin de la prueba (30 s)") }, DEMO_MS)
        showOverlay(service)
        if (prefs.stripOffOnLockMode) {
            main.postDelayed({
                if (isActive) com.george.antirrobo.lightstrip.LightStripToggler.turnOffIfOn("bloqueo con luces")
            }, 800L)
        }
        AppStatus.setLastEvent(if (demo) "Prueba de bloqueo (30 s)" else "Bloqueo con luces activo")
        EventLog.i(TAG, "Activado ($reason, demo=$demo)")
        return true
    }

    private fun showOverlay(service: AccessibilityService) {
        if (overlay != null) return
        val prefs = AppPreferences.get(service)
        val v = LockOverlay(
            service = service,
            ghost = prefs.ghostGuides && (demo || prefs.ghostGuidesReal),
            demo = demo,
            onGesture = { onGestureComplete() },
            onEmergency = { emergency() },
        )
        if (v.show()) overlay = v else EventLog.w(TAG, "No se pudo mostrar el overlay de bloqueo")
    }

    private fun removeOverlay() {
        overlay?.remove()
        overlay = null
    }

    private fun onGestureComplete() {
        val service = serviceRef?.get() ?: return
        val prefs = AppPreferences.get(service)
        val status = LockAuthActivity.availability(service, prefs.lockModeAllowPin)
        EventLog.i(TAG, "Gesto de esquinas completo; autenticación: $status")
        if (status != LockAuthActivity.Availability.OK) {
            // Sin huella ni PIN configurados: no te dejamos afuera.
            deactivate("gesto (sin huella/PIN disponible: $status)")
            return
        }
        isAuthenticating = true
        removeOverlay()
        main.postDelayed(authTimeout, AUTH_TIMEOUT_MS)
        try {
            service.startActivity(
                Intent(service, LockAuthActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION),
            )
        } catch (t: Throwable) {
            EventLog.e(TAG, "No abrió la huella", t)
            onAuthResult(false, "no se pudo abrir")
        }
    }

    private val authTimeout = Runnable {
        if (isAuthenticating) {
            LockAuthActivity.finishCurrent()
            onAuthResult(false, "tiempo agotado")
        }
    }

    /** Lo llama LockAuthActivity. */
    fun onAuthResult(success: Boolean, detail: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { onAuthResult(success, detail) }
            return
        }
        if (!isAuthenticating) return
        isAuthenticating = false
        main.removeCallbacks(authTimeout)
        EventLog.i(TAG, "Huella: ok=$success ($detail)")
        if (success) {
            deactivate("huella correcta")
        } else {
            serviceRef?.get()?.let { showOverlay(it) } // se vuelve a bloquear al instante
        }
    }

    fun deactivate(reason: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { deactivate(reason) }
            return
        }
        serviceRef?.get()?.let { AppPreferences.get(it).lockModeActive = false }
        main.removeCallbacksAndMessages(null)
        isActive = false
        isAuthenticating = false
        removeOverlay()
        AppStatus.setLastEvent("Bloqueo con luces desactivado: $reason")
        EventLog.i(TAG, "Desactivado: $reason")
    }

    /** Emergencia: se quita el overlay, se abre el marcador y se vuelve a bloquear después. */
    private fun emergency() {
        val service = serviceRef?.get() ?: return
        removeOverlay()
        EventLog.i(TAG, "Emergencia: marcador abierto; re-bloqueo en ${EMERGENCY_RELOCK_MS / 1000} s")
        val candidates = listOf(
            Intent("com.android.phone.EmergencyDialer.DIAL"),
            Intent(Intent.ACTION_DIAL, Uri.parse("tel:911")),
        )
        for (i in candidates) {
            try {
                service.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                break
            } catch (_: Throwable) {
            }
        }
        main.postDelayed(object : Runnable {
            override fun run() {
                if (!isActive || isAuthenticating) return
                val am = service.getSystemService(AudioManager::class.java)
                val inCall = am?.mode == AudioManager.MODE_IN_CALL || am?.mode == AudioManager.MODE_IN_COMMUNICATION
                if (inCall) {
                    main.postDelayed(this, 15_000L) // no se interrumpe una llamada
                } else {
                    showOverlay(service)
                }
            }
        }, EMERGENCY_RELOCK_MS)
    }

    @Suppress("unused")
    private val sdk = Build.VERSION.SDK_INT
}
