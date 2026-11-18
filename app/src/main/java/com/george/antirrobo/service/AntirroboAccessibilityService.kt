package com.george.antirrobo.service

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import com.george.antirrobo.data.AppPreferences
import com.george.antirrobo.data.AppStatus
import com.george.antirrobo.fakeoff.FakeOffController
import com.george.antirrobo.receiver.ScreenStateReceiver
import com.george.antirrobo.unlock.UnlockComboDetector
import com.george.antirrobo.util.EventLog

/**
 * Servicio de accesibilidad del prototipo.
 *
 * HECHO IMPORTANTE (Android 14/15, sin root):
 * [onKeyEvent] NO recibe KEYCODE_POWER. El sistema consume la tecla en
 * PhoneWindowManager.interceptKeyBeforeQueueing antes de que cualquier app
 * (incluso con FLAG_REQUEST_FILTER_KEY_EVENTS) la vea. Si este método llega
 * a recibirla, se registra porque es inusual y se consume cuando estamos armados.
 *
 * Lo que SÍ se puede hacer sin root:
 *  - Detectar el menú de apagado (TYPE_WINDOW_STATE_CHANGED de systemui /
 *    clases tipo GlobalActionsDialog) y cerrarlo de inmediato con
 *    GLOBAL_ACTION_BACK y GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE, luego
 *    entrar a apagado falso. El menú puede verse un instante; no es 100 %.
 *  - Recibir teclas de VOLUMEN en onKeyEvent para el desbloqueo combinado.
 *  - Mostrar un overlay TYPE_ACCESSIBILITY_OVERLAY.
 *  - Abrir el diálogo real de apagado con GLOBAL_ACTION_POWER_DIALOG
 *    cuando el gesto oculto lo pide.
 */
class AntirroboAccessibilityService : AccessibilityService() {

    private val main = Handler(Looper.getMainLooper())
    private var screenReceiver: ScreenStateReceiver? = null
    private var pendingDismiss: Runnable? = null
    @Volatile private var lastOwnBackAt = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        EventLog.init(this)
        FakeOffController.bind(this)
        AppStatus.setAccessibilityRunning(true)
        AppStatus.setLastEvent("Servicio de accesibilidad conectado")
        EventLog.i(TAG, "AccessibilityService conectado")
        val recv = ScreenStateReceiver { action ->
            onScreenBroadcast(action)
        }
        screenReceiver = recv
        recv.register(this)
        // Si estábamos armados y el servicio se reinició, no reentramos solos
        // al apagado falso (el overlay se perdió; el silencer ya restauró en Application).
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val prefs = AppPreferences.get(this)
        if (prefs.diagnosticWindows && event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            EventLog.i(TAG, "DIAG ventana: ${describe(event)}")
        }
        if (!prefs.armed) return
        if (FakeOffController.letPowerDialogThrough) return

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED
        ) {
            if (looksLikePowerMenu(event) || anyWindowLooksLikePowerMenu()) {
                EventLog.i(TAG, "Menú de apagado detectado: ${describe(event)}")
                dismissPowerMenuThenFakeOff("menú de apagado detectado")
            }
        }
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val prefs = AppPreferences.get(this)
        when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE -> {
                // Primero se registra para la combinación de desbloqueo...
                UnlockComboDetector.onVolumeKey(event)
                // ...y en apagado falso se CONSUME todo (DOWN, repeticiones y UP):
                // no cambia el volumen, no aparece el panel y no hay "tic" háptico
                // del paso de volumen. En v0.1.0 isActive quedaba en false por el
                // bug del overlay, así que el sistema procesaba la tecla y vibraba.
                return FakeOffController.isActive || FakeOffController.isAnimating
            }
            KeyEvent.KEYCODE_BACK -> {
                // v0.1.3: "Atrás" cierra el menú falso como el real. Se ignoran
                // los BACK que nosotros mismos mandamos al cerrar el menú real.
                if (FakeOffController.isAnimating) return true
                val now = SystemClock.uptimeMillis()
                val shownAt = FakeOffController.decoyShownAt()
                if (shownAt > 0L && now - lastOwnBackAt > 800L && now - shownAt > 500L) {
                    if (event.action == KeyEvent.ACTION_UP) FakeOffController.dismissDecoy("Atrás")
                    return true
                }
                return false
            }
            KeyEvent.KEYCODE_POWER, KeyEvent.KEYCODE_SLEEP, KeyEvent.KEYCODE_SOFT_SLEEP -> {
                // En la práctica, sin root esto casi nunca llega.
                EventLog.w(
                    TAG,
                    "Se recibió ${KeyEvent.keyCodeToString(event.keyCode)} en onKeyEvent " +
                        "(es inusual sin root). action=${event.action}",
                )
                if (prefs.armed && !FakeOffController.letPowerDialogThrough) {
                    if (event.action == KeyEvent.ACTION_DOWN) {
                        FakeOffController.enter("KEYCODE_POWER recibido en onKeyEvent (inusual)")
                    }
                    return true
                }
            }
        }
        return false
    }

    override fun onInterrupt() {
        EventLog.w(TAG, "onInterrupt()")
        FakeOffController.emergencyExit("onInterrupt")
    }

    override fun onDestroy() {
        EventLog.w(TAG, "onDestroy()")
        cleanup("onDestroy")
        super.onDestroy()
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        EventLog.w(TAG, "onUnbind()")
        cleanup("onUnbind")
        return super.onUnbind(intent)
    }

    private fun cleanup(reason: String) {
        pendingDismiss?.let { main.removeCallbacks(it) }
        pendingDismiss = null
        try {
            screenReceiver?.unregister(this)
        } catch (_: Throwable) {
        }
        screenReceiver = null
        FakeOffController.unbind(this)
        AppStatus.setAccessibilityRunning(false)
        AppStatus.setLastEvent("Accesibilidad detenida ($reason)")
    }

    private fun onScreenBroadcast(action: String) {
        val prefs = AppPreferences.get(this)
        if (!prefs.armed) return
        if (FakeOffController.letPowerDialogThrough) return
        when (action) {
            android.content.Intent.ACTION_SCREEN_OFF -> {
                // Como el menú real: se cierra si se apaga la pantalla.
                FakeOffController.dismissDecoy("pantalla apagada")
                // v0.1.1: el bloqueo normal (pulsación corta / tiempo de pantalla)
                // ya NO dispara el apagado falso, salvo que actives la opción.
                if (prefs.screenOffTrigger) {
                    EventLog.i(TAG, "SCREEN_OFF + opción activada: entrando a apagado falso")
                    FakeOffController.enter("broadcast SCREEN_OFF (opción)")
                }
            }
            android.content.Intent.ACTION_USER_PRESENT -> {
                // El keyguard del sistema se desbloqueó (huella, PIN, patrón):
                // es el dueño, así que se sale del apagado falso.
                if (FakeOffController.isActive) {
                    FakeOffController.exit("desbloqueo del sistema (USER_PRESENT: huella/PIN)")
                }
            }
        }
    }

    /**
     * Cierra el menú lo más rápido posible y entra a apagado falso.
     * Un segundo BACK diferido 60 ms cubre el caso en que el diálogo aún no era cancelable.
     *
     * LÍMITE: el usuario podría alcanzar a tocar "Apagar" en el destello.
     * Sin root no hay forma de impedirlo al 100 %.
     */
    private fun dismissPowerMenuThenFakeOff(reason: String) {
        lastOwnBackAt = SystemClock.uptimeMillis()
        performGlobalAction(GLOBAL_ACTION_BACK)
        if (Build.VERSION.SDK_INT >= 31) {
            performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
        }
        pendingDismiss?.let { main.removeCallbacks(it) }
        val again = Runnable {
            if (FakeOffController.letPowerDialogThrough) return@Runnable
            if (anyWindowLooksLikePowerMenu()) {
                EventLog.i(TAG, "El menú sigue visible; segundo BACK")
                lastOwnBackAt = SystemClock.uptimeMillis()
                performGlobalAction(GLOBAL_ACTION_BACK)
                if (Build.VERSION.SDK_INT >= 31) {
                    performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
                }
            }
            FakeOffController.onPowerMenuDetected(reason)
        }
        pendingDismiss = again
        main.postDelayed(again, 60L)
        // v0.1.3: según el ajuste, menú falso + animación o directo a negro.
        FakeOffController.onPowerMenuDetected(reason)
    }

    /**
     * Heurística: paquete systemui/android + clase o texto típicos del menú de apagado.
     * Nubia puede usar un nombre de clase propio; por eso existe el modo diagnóstico
     * (registra paquete, clase y texto para completar esta lista en el dispositivo real).
     */
    private fun looksLikePowerMenu(event: AccessibilityEvent): Boolean {
        val pkg = event.packageName?.toString().orEmpty()
        val cls = event.className?.toString().orEmpty()
        val text = event.text?.joinToString(" ").orEmpty()
        val desc = event.contentDescription?.toString().orEmpty()
        return matchesPowerMenu(pkg, cls, "$text $desc")
    }

    private fun anyWindowLooksLikePowerMenu(): Boolean {
        return try {
            windows?.any { w ->
                val pkg = w.root?.packageName?.toString().orEmpty()
                val cls = w.root?.className?.toString().orEmpty()
                val title = if (Build.VERSION.SDK_INT >= 33) {
                    w.title?.toString().orEmpty()
                } else {
                    ""
                }
                val typeOk = w.type == AccessibilityWindowInfo.TYPE_SYSTEM ||
                    w.type == AccessibilityWindowInfo.TYPE_APPLICATION ||
                    (Build.VERSION.SDK_INT >= 33 && w.type == AccessibilityWindowInfo.TYPE_MAGNIFICATION_OVERLAY)
                typeOk && matchesPowerMenu(pkg, cls, title)
            } == true
        } catch (t: Throwable) {
            EventLog.w(TAG, "No se pudieron inspeccionar las ventanas", t)
            false
        }
    }

    private fun describe(event: AccessibilityEvent): String {
        return "pkg=${event.packageName} class=${event.className} text=${event.text} " +
            "desc=${event.contentDescription} type=${event.eventType}"
    }

    companion object {
        private const val TAG = "AntirroboA11y"

        /**
         * Paquetes donde suele vivir el menú de apagado. Se incluye
         * com.android.systemui y android. Nubia puede usar un paquete
         * propio: agrégalo cuando el diagnóstico lo muestre. Las entradas
         * con "nubia"/"zte"/"redmagic" son hipótesis, no hechos verificados.
         */
        private val SYSTEM_PACKAGES = setOf(
            "com.android.systemui",
            "android",
            "com.android.server.policy",
            // Hipótesis OEM (NO VERIFICADAS en Red Magic 11 Pro):
            "com.nubia.systemui",
            "cn.nubia.systemui",
            "com.zte.systemui",
        )

        private val CLASS_HINTS = listOf(
            "GlobalActions",
            "GlobalActionsDialog",
            "GlobalActionsDialogLite",
            "GlobalActionsPopup",
            "MiuiGlobalActions",
            "ShutdownUi",
            "ShutdownDialog",
            "PowerOff",
            "PowerMenu",
            "PowerDialog",
            "EmergencyPanel",
            "EmergencyAffordance",
            "AlertController",
            // Hipótesis OEM, NO VERIFICADAS:
            "NubiaGlobalActions",
            "NubiaPowerMenu",
            "RedMagicGlobalActions",
        )

        private val TEXT_HINTS = listOf(
            "apag",          // Apagar, Apagado
            "reinici",       // Reiniciar
            "power off",
            "shut down",
            "shutdown",
            "restart",
            "reboot",
            "emergencia",
            "emergency",
            "apagar el dispositivo",
        )

        fun matchesPowerMenu(pkg: String, cls: String, text: String): Boolean {
            val pkgL = pkg.lowercase()
            // Nunca confundir nuestras propias ventanas (menú falso) con el menú real.
            if (pkgL.startsWith("com.george.antirrobo")) return false
            val clsSimple = cls.substringAfterLast('.')
            val classHit = CLASS_HINTS.any { hint ->
                cls.contains(hint, ignoreCase = true) || clsSimple.equals(hint, ignoreCase = true)
            }
            val textHit = TEXT_HINTS.any { text.contains(it, ignoreCase = true) }
            val systemPkg = SYSTEM_PACKAGES.any { pkgL == it || pkgL.startsWith("$it.") } ||
                pkgL.contains("systemui")
            if (classHit && (systemPkg || pkg.isEmpty())) return true
            // Texto típico + paquete de sistema (evita falsos positivos en apps de usuario).
            if (textHit && systemPkg) return true
            return false
        }
    }
}
