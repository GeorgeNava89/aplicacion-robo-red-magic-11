package com.george.antirrobo.fakeoff

import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.accessibilityservice.AccessibilityService
import com.george.antirrobo.data.AppPreferences
import com.george.antirrobo.data.AppStatus
import com.george.antirrobo.unlock.AutoUnlockWatchdog
import com.george.antirrobo.unlock.UnlockComboDetector
import com.george.antirrobo.util.EventLog
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Coordinador del modo "apagado falso":
 *  - overlay negro TYPE_ACCESSIBILITY_OVERLAY
 *  - brillo mínimo, audio en silencio, vibración cancelada
 *  - intento no verificado de apagar RGB/ventilador Nubia
 *  - red de seguridad de auto-desbloqueo (Handler + watchdog + FGS)
 *
 * [letPowerDialogThrough] permite que el gesto oculto abra el diálogo real
 * de apagado sin que el servicio lo cierre de inmediato.
 *
 * [emergencyExit] es idempotente y se llama desde onDestroy / onInterrupt
 * del AccessibilityService, del watchdog y del desbloqueo.
 */
object FakeOffController {

    private const val TAG = "FakeOff"
    private const val POWER_DIALOG_GRACE_MS = 8_000L

    private val main = Handler(Looper.getMainLooper())
    private val active = AtomicBoolean(false)
    private val powerDialogThrough = AtomicBoolean(false)
    private var a11yRef: WeakReference<AccessibilityService>? = null
    private var overlay: FakeOffOverlay? = null
    private var silencer: AudioAndLightsSilencer? = null
    private var watchdog: AutoUnlockWatchdog? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastEnterReason: String = "—"

    val isActive: Boolean
        get() = active.get()

    val letPowerDialogThrough: Boolean
        get() = powerDialogThrough.get()

    fun bind(service: AccessibilityService) {
        a11yRef = WeakReference(service)
    }

    fun unbind(service: AccessibilityService) {
        val current = a11yRef?.get()
        if (current === service) {
            emergencyExit("unbind del servicio de accesibilidad")
            a11yRef = null
        }
    }

    fun boundService(): AccessibilityService? = a11yRef?.get()

    /**
     * Entra al apagado falso. Seguro llamarlo varias veces (idempotente).
     * Debe ejecutarse en el hilo principal.
     */
    fun enter(reason: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { enter(reason) }
            return
        }
        if (powerDialogThrough.get()) {
            EventLog.i(TAG, "enter() ignorado: se está dejando pasar el diálogo de apagado")
            return
        }
        val service = a11yRef?.get()
        if (service == null) {
            EventLog.w(TAG, "enter($reason): no hay AccessibilityService; no se puede mostrar el overlay")
            return
        }
        val prefs = AppPreferences.get(service)
        if (!prefs.armed) {
            EventLog.i(TAG, "enter($reason): la app no está armada, se ignora")
            return
        }
        lastEnterReason = reason
        if (active.get() && overlay?.isShowing() == true) {
            EventLog.i(TAG, "enter($reason): ya estaba activo")
            return
        }
        EventLog.i(TAG, "Entrando a apagado falso. Motivo: $reason")
        AppStatus.setLastEvent("Apagado falso: $reason")
        val sil = AudioAndLightsSilencer(service)
        silencer = sil
        sil.silence()
        acquireWakeLock(service)
        val view = FakeOffOverlay(
            service = service,
            silencer = sil,
            onUnlockGesture = { exit("gesto de desbloqueo (volumen + swipe)") },
            onShutdownGesture = { requestTrueShutdown() },
            onFailedUnlockAttempt = {
                EventLog.i(TAG, "Intento de desbloqueo fallido (falta volumen). El auto-desbloqueo sigue corriendo.")
                AppStatus.setLastEvent("Desbloqueo fallido; espera el auto-desbloqueo")
            },
        )
        overlay = view
        view.show()
        if (!view.isShowing() && !view.isAttachedToWindow) {
            // show() puede haber fallado
            EventLog.e(TAG, "El overlay no se mostró; se restauran audio/brillo")
            sil.restoreIfNeeded()
            releaseWakeLock()
            return
        }
        active.set(true)
        AppStatus.setFakeOff(true)
        UnlockComboDetector.reset()
        val timeoutMs = prefs.autoUnlockSeconds * 1_000L
        if (watchdog == null) {
            watchdog = AutoUnlockWatchdog {
                exit("auto-desbloqueo (red de seguridad, ${prefs.autoUnlockSeconds}s)")
            }
        }
        watchdog?.start(timeoutMs)
    }

    fun exit(reason: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { exit(reason) }
            return
        }
        val was = active.getAndSet(false)
        watchdog?.cancel()
        UnlockComboDetector.reset()
        try {
            overlay?.remove()
        } catch (t: Throwable) {
            EventLog.w(TAG, "Error al quitar overlay en exit", t)
        }
        overlay = null
        try {
            silencer?.restoreIfNeeded()
        } catch (t: Throwable) {
            EventLog.w(TAG, "Error al restaurar audio en exit", t)
        }
        releaseWakeLock()
        AppStatus.setFakeOff(false)
        if (was) {
            EventLog.i(TAG, "Saliendo de apagado falso. Motivo: $reason")
            AppStatus.setLastEvent("Salida: $reason")
        }
    }

    /** Llamar desde onDestroy / onInterrupt / onUnbind. Nunca deja el overlay puesto. */
    fun emergencyExit(reason: String) {
        EventLog.w(TAG, "Salida de emergencia: $reason")
        exit("emergencia: $reason")
    }

    /**
     * Gesto oculto: deja pasar el diálogo de apagado real y lo abre.
     * La bandera se limpia sola a los pocos segundos por si el usuario cancela.
     */
    fun requestTrueShutdown() {
        val service = a11yRef?.get()
        if (service == null) {
            EventLog.w(TAG, "No hay servicio para abrir el diálogo de apagado")
            return
        }
        EventLog.i(TAG, "Gesto de apagado real: se deja pasar GLOBAL_ACTION_POWER_DIALOG")
        powerDialogThrough.set(true)
        AppStatus.setLetPowerDialogThrough(true)
        // Salimos del overlay para que el usuario vea y confirme el diálogo real.
        exit("gesto de apagado real (se abre el diálogo del sistema)")
        val ok = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_POWER_DIALOG)
        EventLog.i(TAG, "GLOBAL_ACTION_POWER_DIALOG resultado=$ok")
        AppStatus.setLastEvent("Diálogo real de apagado (gesto oculto), ok=$ok")
        main.postDelayed({
            powerDialogThrough.set(false)
            AppStatus.setLetPowerDialogThrough(false)
            EventLog.i(TAG, "Fin de la ventana para dejar pasar el diálogo de apagado")
        }, POWER_DIALOG_GRACE_MS)
    }

    fun checkWatchdogFromService() {
        watchdog?.checkFromService()
    }

    fun lastReason(): String = lastEnterReason

    private fun acquireWakeLock(service: AccessibilityService) {
        releaseWakeLock()
        try {
            val pm = service.getSystemService(PowerManager::class.java) ?: return
            val wl = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "antirrobo:fakeoff",
            )
            wl.setReferenceCounted(false)
            wl.acquire(30 * 60 * 1000L) // tope de 30 min por seguridad
            wakeLock = wl
        } catch (t: Throwable) {
            EventLog.w(TAG, "No se obtuvo WakeLock", t)
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (t: Throwable) {
            EventLog.w(TAG, "Error al liberar WakeLock", t)
        }
        wakeLock = null
    }
}
