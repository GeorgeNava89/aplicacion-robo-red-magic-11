package com.george.antirrobo.fakeoff

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import com.george.antirrobo.data.AppPreferences
import com.george.antirrobo.data.AppStatus
import com.george.antirrobo.unlock.AutoUnlockWatchdog
import com.george.antirrobo.unlock.FingerprintUnlockActivity
import com.george.antirrobo.unlock.UnlockComboDetector
import com.george.antirrobo.util.EventLog
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Coordinador del modo "apagado falso".
 *
 * v0.1.1 — corrección del auto-desbloqueo:
 *  - v0.1.0 comprobaba `!view.isShowing() && !view.isAttachedToWindow` justo
 *    después de addView(). La vista aún no estaba adjunta (eso ocurre en el
 *    siguiente frame), así que la condición era verdadera: se trataba como
 *    error, NO se marcaba active=true, NO se arrancaba el watchdog y el overlay
 *    negro se quedaba puesto para siempre. Por eso el usuario tenía que
 *    forzar el reinicio. Ahora el éxito lo decide el retorno de addView().
 *  - Nunca se apilan overlays: si queda uno viejo, se quita antes.
 *  - El watchdog se arranca SIEMPRE que hay overlay, por cualquier ruta.
 *  - WakeLock parcial durante todo el apagado falso (los ticks corren con la
 *    pantalla apagada) + alarma exacta que despierta la CPU.
 *  - exit() hace cada paso por separado; un fallo no impide los demás, y
 *    enciende la pantalla al salir.
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

    /** True si el apagado falso está activo o si todavía hay un overlay puesto. */
    val isActive: Boolean
        get() = active.get() || overlay != null

    val letPowerDialogThrough: Boolean
        get() = powerDialogThrough.get()

    fun bind(service: AccessibilityService) {
        a11yRef = WeakReference(service)
    }

    fun unbind(service: AccessibilityService) {
        if (a11yRef?.get() === service) {
            emergencyExit("unbind del servicio de accesibilidad")
            a11yRef = null
        }
    }

    fun boundService(): AccessibilityService? = a11yRef?.get()

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
        if (active.get() && overlay != null) {
            EventLog.i(TAG, "enter($reason): ya estaba activo")
            return
        }
        // Limpieza defensiva de un overlay huérfano.
        overlay?.let {
            EventLog.w(TAG, "Había un overlay huérfano; se quita antes de entrar")
            safe("quitar overlay huérfano") { it.remove() }
            overlay = null
        }

        lastEnterReason = reason
        EventLog.i(TAG, "Entrando a apagado falso. Motivo: $reason")
        AppStatus.setLastEvent("Apagado falso: $reason")

        val sil = AudioAndLightsSilencer(service)
        silencer = sil
        UnlockComboDetector.reset()
        acquireWakeLock(service)

        // 1) Arranca el watchdog ANTES de cualquier otra cosa: pase lo que pase
        //    después, el teléfono vuelve solo.
        val timeoutMs = prefs.autoUnlockSeconds * 1_000L
        val wd = watchdog ?: AutoUnlockWatchdog(service.applicationContext) { source ->
            exit("auto-desbloqueo ${prefs.autoUnlockSeconds}s ($source)")
        }.also { watchdog = it }
        wd.start(timeoutMs)
        active.set(true)
        AppStatus.setFakeOff(true)

        // 2) Silencio total (audio, DND, ringer, hápticos del sistema, vibración).
        safe("silenciar") { sil.silence() }

        // 3) Overlay.
        val view = FakeOffOverlay(
            service = service,
            silencer = sil,
            onUnlockGesture = { exit("gesto de desbloqueo (volumen + swipe)") },
            onShutdownGesture = { requestTrueShutdown() },
            onFailedUnlockAttempt = {
                EventLog.i(TAG, "Intento de desbloqueo fallido. El auto-desbloqueo sigue corriendo.")
                AppStatus.setLastEvent("Desbloqueo fallido; espera el auto-desbloqueo")
            },
        )
        val shown = view.show()
        if (!shown) {
            EventLog.e(TAG, "addView() falló; se sale y se restaura todo")
            exit("no se pudo mostrar el overlay")
            return
        }
        overlay = view

        // 4) Huella experimental (apagada por defecto): actividad negra sin UI propia.
        if (prefs.fingerprintSilentAuth) {
            safe("lanzar huella experimental") {
                service.startActivity(
                    Intent(service, FingerprintUnlockActivity::class.java)
                        .addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_NO_ANIMATION or
                                Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS,
                        ),
                )
            }
        }
    }

    fun exit(reason: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { exit(reason) }
            return
        }
        val was = active.getAndSet(false)
        val hadOverlay = overlay != null
        safe("cancelar watchdog") { watchdog?.cancel() }
        UnlockComboDetector.reset()
        safe("quitar overlay") { overlay?.remove() }
        overlay = null
        val ctx: Context? = a11yRef?.get()
        safe("restaurar audio/brillo") {
            (silencer ?: ctx?.let { AudioAndLightsSilencer(it) })?.restoreIfNeeded()
        }
        silencer = null
        FingerprintUnlockActivity.finishIfShowing()
        a11yRef?.get()?.let { svc -> safe("despertar pantalla") { wakeScreen(svc) } }
        releaseWakeLock()
        AppStatus.setFakeOff(false)
        if (was || hadOverlay) {
            EventLog.i(TAG, "Saliendo de apagado falso. Motivo: $reason")
            AppStatus.setLastEvent("Salida: $reason")
        }
    }

    fun emergencyExit(reason: String) {
        EventLog.w(TAG, "Salida de emergencia: $reason")
        exit("emergencia: $reason")
    }

    fun requestTrueShutdown() {
        val service = a11yRef?.get() ?: return
        EventLog.i(TAG, "Gesto de apagado real: se deja pasar GLOBAL_ACTION_POWER_DIALOG")
        powerDialogThrough.set(true)
        AppStatus.setLetPowerDialogThrough(true)
        exit("gesto de apagado real (se abre el diálogo del sistema)")
        val ok = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_POWER_DIALOG)
        AppStatus.setLastEvent("Diálogo real de apagado (gesto oculto), ok=$ok")
        main.postDelayed({
            powerDialogThrough.set(false)
            AppStatus.setLetPowerDialogThrough(false)
        }, POWER_DIALOG_GRACE_MS)
    }

    fun checkWatchdogFromService() {
        watchdog?.checkNow("fgs")
    }

    fun lastReason(): String = lastEnterReason

    private fun acquireWakeLock(context: Context) {
        releaseWakeLock()
        try {
            val pm = context.getSystemService(PowerManager::class.java) ?: return
            val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "antirrobo:fakeoff")
            wl.setReferenceCounted(false)
            wl.acquire(30 * 60 * 1000L) // tope de 30 min por seguridad
            wakeLock = wl
        } catch (t: Throwable) {
            EventLog.w(TAG, "No se obtuvo WakeLock", t)
        }
    }

    private fun releaseWakeLock() {
        safe("liberar WakeLock") { if (wakeLock?.isHeld == true) wakeLock?.release() }
        wakeLock = null
    }

    /** Si la pantalla quedó apagada, la enciende brevemente al salir. */
    @Suppress("DEPRECATION")
    private fun wakeScreen(context: Context) {
        val pm = context.getSystemService(PowerManager::class.java) ?: return
        if (pm.isInteractive) return
        val wl = pm.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "antirrobo:wake",
        )
        wl.acquire(3_000L)
        EventLog.i(TAG, "Pantalla encendida al salir del apagado falso")
    }

    private inline fun safe(what: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            EventLog.w(TAG, "Error al $what", t)
        }
    }
}
