package com.george.antirrobo.fakeoff

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import com.george.antirrobo.data.AppPreferences
import com.george.antirrobo.data.AppStatus
import com.george.antirrobo.unlock.AutoUnlockWatchdog
import com.george.antirrobo.unlock.FingerprintUnlockActivity
import com.george.antirrobo.unlock.GestureMapping
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
    private const val POWER_DIALOG_GRACE_MS = 15_000L

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

    /** [demo] = lanzado desde "Probar apagado falso" / "Probar menú de apagado". */
    fun enter(reason: String, demo: Boolean = false) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { enter(reason, demo) }
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
            onCornerSequence = { handleGesture(GestureMapping.Screen.FAKE_OFF, GestureMapping.Gesture.CORNER_SEQUENCE) },
            onVolumeSwipe = { handleGesture(GestureMapping.Screen.FAKE_OFF, GestureMapping.Gesture.VOLUME_SWIPE_UP) },
            onSwipeWithoutVolume = {
                EventLog.i(TAG, "Deslizamiento sin volumen: no hace nada. El auto-desbloqueo sigue corriendo.")
                AppStatus.setLastEvent("Deslizamiento sin volumen; espera el auto-desbloqueo")
            },
            debugHints = prefs.ghostGuides && (demo || prefs.ghostGuidesReal),
        )
        val shown = view.show()
        if (!shown) {
            EventLog.e(TAG, "addView() falló; se sale y se restaura todo")
            exit("no se pudo mostrar el overlay")
            return
        }
        overlay = view

        // v0.1.5: Franja de luz — DESACTIVADA en v0.1.6 (FeatureFlags.LIGHT_STRIP = false).
        if (com.george.antirrobo.FeatureFlags.LIGHT_STRIP && prefs.stripOffOnFakeOff) {
            main.postDelayed({
                if (active.get()) com.george.antirrobo.lightstrip.LightStripToggler.turnOffIfOn("apagado falso")
            }, 800L)
        }

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
        // v0.1.3: también se quitan el menú falso y cualquier animación.
        safe("quitar menú falso") { decoy?.remove() }
        decoy = null
        safe("quitar animación") { anim?.remove() }
        anim = null
        sequenceRunning.set(false)
        main.removeCallbacks(sequenceSafety)
        main.removeCallbacks(decoyTimeout)
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

    /**
     * Gesto oculto: deja pasar el diálogo REAL de apagado y lo abre.
     *
     * v0.1.3: antes se llamaba GLOBAL_ACTION_POWER_DIALOG en el mismo instante
     * en que se quitaba el overlay; ahora se espera 300 ms a que la ventana se
     * retire, se reintenta si el sistema responde false y la ventana para dejar
     * pasar el diálogo dura 15 s (antes 8 s).
     */
    /** v0.1.6: único punto donde se decide qué hace cada gesto (ver GestureMapping). */
    fun handleGesture(screen: GestureMapping.Screen, gesture: GestureMapping.Gesture) {
        val action = GestureMapping.resolve(screen, gesture)
        EventLog.i(TAG, "Gesto $gesture en $screen → $action")
        when (action) {
            GestureMapping.Action.UNLOCK -> exit("5 toques en las esquinas (desbloqueo)")
            GestureMapping.Action.REAL_POWER_DIALOG -> {
                decoy?.let { d -> decoy = null; main.removeCallbacks(decoyTimeout); d.remove() }
                requestTrueShutdown()
            }
            else -> Unit // los botones del menú falso se manejan en sus propios callbacks
        }
    }

    fun requestTrueShutdown() {
        val service = a11yRef?.get()
        if (service == null) {
            EventLog.w(TAG, "Apagado real: no hay servicio de accesibilidad")
            return
        }
        EventLog.i(TAG, "Apagado real: se deja pasar GLOBAL_ACTION_POWER_DIALOG")
        powerDialogThrough.set(true)
        AppStatus.setLetPowerDialogThrough(true)
        exit("volumen + deslizar (se abre el menú real del sistema)")
        main.postDelayed({
            val ok = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_POWER_DIALOG)
            EventLog.i(TAG, "GLOBAL_ACTION_POWER_DIALOG intento 1 → $ok")
            AppStatus.setLastEvent("Diálogo real de apagado, ok=$ok")
            if (!ok) {
                main.postDelayed({
                    val ok2 = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_POWER_DIALOG)
                    EventLog.i(TAG, "GLOBAL_ACTION_POWER_DIALOG intento 2 → $ok2")
                }, 700L)
            }
        }, 300L)
        main.postDelayed({
            powerDialogThrough.set(false)
            AppStatus.setLetPowerDialogThrough(false)
            EventLog.i(TAG, "Fin de la ventana para dejar pasar el diálogo de apagado")
        }, POWER_DIALOG_GRACE_MS)
    }

    // ---------- v0.1.3: menú de apagado falso + animaciones ----------

    private var decoy: DecoyPowerMenu? = null
    private var anim: ShutdownAnimation? = null
    private val sequenceRunning = AtomicBoolean(false)

    /** Menú falso visible o animación en curso. */
    val isDecoyBusy: Boolean
        get() = decoy != null || sequenceRunning.get()

    /** Durante la animación se consumen las teclas de volumen (no hay panel ni vibración). */
    val isAnimating: Boolean
        get() = sequenceRunning.get()

    private val decoyTimeout = Runnable {
        decoy?.dismiss("tiempo máximo del menú falso (60 s)")
    }

    private val sequenceSafety = Runnable {
        if (sequenceRunning.get()) {
            EventLog.w(TAG, "La animación no terminó a tiempo; se entra directo a apagado falso")
            safe("quitar animación") { anim?.remove() }
            anim = null
            sequenceRunning.set(false)
            enter("respaldo de animación")
        }
    }

    /** Lo llama el servicio de accesibilidad al detectar (y cerrar) el menú real. */
    fun onPowerMenuDetected(reason: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { onPowerMenuDetected(reason) }
            return
        }
        if (powerDialogThrough.get() || active.get() || overlay != null || isDecoyBusy) return
        val service = a11yRef?.get() ?: return
        val prefs = AppPreferences.get(service)
        if (!prefs.armed) return
        if (prefs.decoyPowerMenu) showDecoy(reason) else enter(reason)
    }

    fun showDecoy(reason: String, demo: Boolean = false): Boolean {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { showDecoy(reason, demo) }
            return true
        }
        val service = a11yRef?.get() ?: return false
        if (!AppPreferences.get(service).armed || isDecoyBusy || active.get()) return false
        val menu = DecoyPowerMenu(
            service = service,
            onPowerOff = { decoy = null; runSequence(ShutdownAnimation.Kind.SHUTDOWN, demo) },
            onRestart = { decoy = null; runSequence(ShutdownAnimation.Kind.RESTART, demo) },
            onEmergency = { decoy = null; openEmergencyDialer(service) },
            onVolumeSwipe = { handleGesture(GestureMapping.Screen.DECOY_MENU, GestureMapping.Gesture.VOLUME_SWIPE_UP) },
            ghost = AppPreferences.get(service).let { p -> p.ghostGuides && (demo || p.ghostGuidesReal) },
            onDismiss = {
                decoy = null
                main.removeCallbacks(decoyTimeout)
                AppStatus.setLastEvent("Menú falso cerrado (sigue armado)")
            },
            style = if (AppPreferences.get(service).decoyStyle == "classic")
                DecoyPowerMenu.Style.CLASSIC else DecoyPowerMenu.Style.SYSTEM,
        )
        decoy = menu
        if (!menu.show()) {
            decoy = null
            enter("$reason (menú falso no disponible)", demo)
            return false
        }
        AppStatus.setLastEvent("Menú falso: $reason")
        main.removeCallbacks(decoyTimeout)
        main.postDelayed(decoyTimeout, 60_000L)
        return true
    }

    fun dismissDecoy(reason: String) {
        decoy?.dismiss(reason)
    }

    fun decoyShownAt(): Long = decoy?.shownAt ?: 0L

    private fun runSequence(kind: ShutdownAnimation.Kind, demo: Boolean) {
        val service = a11yRef?.get() ?: return
        main.removeCallbacks(decoyTimeout)
        sequenceRunning.set(true)
        main.removeCallbacks(sequenceSafety)
        main.postDelayed(sequenceSafety, 20_000L)
        UnlockComboDetector.reset()
        val label = if (kind == ShutdownAnimation.Kind.SHUTDOWN) "apagar" else "reiniciar"
        val first = ShutdownAnimation(service, kind)
        anim = first
        // v0.1.4: "Apagando…" dura el doble (2.8 s → 5.6 s). El auto-desbloqueo
        // arranca en enter(), es decir, cuando YA empieza el negro.
        val shutdown = kind == ShutdownAnimation.Kind.SHUTDOWN
        val ok = first.play(
            holdMs = if (shutdown) ShutdownAnimation.SHUTDOWN_HOLD_MS else 1_800L,
            fadeInMs = if (shutdown) ShutdownAnimation.SHUTDOWN_FADE_IN_MS else 400L,
            fadeOutMs = if (shutdown) ShutdownAnimation.SHUTDOWN_FADE_OUT_MS else 600L,
        ) {
            if (kind == ShutdownAnimation.Kind.SHUTDOWN) {
                finishSequence()
                enter("menú falso: $label", demo)
            } else {
                // Reinicio: negro ~3 s y luego un arranque falso "RED MAGIC".
                val boot = ShutdownAnimation(service, ShutdownAnimation.Kind.BOOT)
                anim = boot
                val ok2 = boot.play(holdMs = 2_500L, startDelayMs = 3_000L) {
                    finishSequence()
                    enter("menú falso: $label (tras arranque falso)", demo)
                }
                if (!ok2) {
                    finishSequence()
                    enter("menú falso: $label", demo)
                }
            }
        }
        if (!ok) {
            finishSequence()
            enter("menú falso: $label (sin animación)", demo)
        }
    }

    private fun finishSequence() {
        sequenceRunning.set(false)
        main.removeCallbacks(sequenceSafety)
    }

    /** NUNCA se bloquean las llamadas de emergencia. */
    private fun openEmergencyDialer(service: AccessibilityService) {
        exit("botón de emergencia")
        val candidates = listOf(
            Intent("com.android.phone.EmergencyDialer.DIAL"),
            Intent(Intent.ACTION_DIAL, Uri.parse("tel:911")),
            Intent(Intent.ACTION_DIAL),
        )
        for (i in candidates) {
            try {
                service.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                EventLog.i(TAG, "Marcador de emergencia abierto: ${i.action}")
                return
            } catch (t: Throwable) {
                EventLog.w(TAG, "No abrió ${i.action}", t)
            }
        }
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
