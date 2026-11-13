package com.george.antirrobo.fakeoff

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.accessibilityservice.AccessibilityService
import com.george.antirrobo.unlock.UnlockComboDetector
import com.george.antirrobo.util.EventLog
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Overlay negro a pantalla completa con TYPE_ACCESSIBILITY_OVERLAY
 * (no requiere permiso de "mostrar sobre otras apps" ni root).
 *
 * Gestos (documentados en el README y en Ajustes):
 *
 *  DESBLOQUEO: mantener VOLUMEN (arriba o abajo) y, al mismo tiempo,
 *  deslizar desde el tercio inferior izquierdo hacia arriba al menos
 *  el 45 % de la altura de la pantalla.
 *
 *  APAGADO REAL (gesto oculto, multi-punto): tocar en secuencia, con
 *  menos de 2 s entre toques, estas 5 zonas:
 *    1. esquina superior izquierda
 *    2. esquina superior derecha
 *    3. esquina inferior derecha
 *    4. esquina inferior izquierda
 *    5. centro
 *  Entonces se abre el diálogo real de apagado (GLOBAL_ACTION_POWER_DIALOG)
 *  con la bandera que lo deja pasar.
 *
 * Un gesto de desbloqueo fallido no alarga el bloqueo: el auto-desbloqueo
 * sigue corriendo.
 */
@SuppressLint("ViewConstructor")
class FakeOffOverlay(
    private val service: AccessibilityService,
    private val silencer: AudioAndLightsSilencer,
    private val onUnlockGesture: () -> Unit,
    private val onShutdownGesture: () -> Unit,
    private val onFailedUnlockAttempt: () -> Unit,
) : View(service) {

    private var windowManager: WindowManager? = null
    private var attached = false
    private var params: WindowManager.LayoutParams? = null

    // Swipe de desbloqueo
    private var swipeStartX = 0f
    private var swipeStartY = 0f
    private var swipeStartTime = 0L
    private var swipeTracking = false
    private var swipeStartedInUnlockZone = false

    // Secuencia de apagado real
    private var shutdownStep = 0
    private var shutdownLastStepAt = 0L

    init {
        setBackgroundColor(Color.BLACK)
        isClickable = true
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        keepScreenOn = true
        contentDescription = null
        if (Build.VERSION.SDK_INT >= 29) {
            isForceDarkAllowed = false
        }
    }

    fun show() {
        if (attached) return
        val wm = service.getSystemService(WindowManager::class.java) ?: return
        windowManager = wm
        val lp = WindowManager.LayoutParams().apply {
            type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            format = PixelFormat.OPAQUE
            width = WindowManager.LayoutParams.MATCH_PARENT
            height = WindowManager.LayoutParams.MATCH_PARENT
            gravity = Gravity.TOP or Gravity.START
            title = OVERLAY_TITLE
            // Recibimos toques (no FLAG_NOT_TOUCHABLE). FLAG_NOT_FOCUSABLE
            // deja que las teclas de volumen sigan llegando al AccessibilityService.
            flags = (WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                or WindowManager.LayoutParams.FLAG_FULLSCREEN
                or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
                or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                or WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                or WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS
                or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH)
            if (Build.VERSION.SDK_INT >= 30) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            } else {
                @Suppress("DEPRECATION")
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            silencer.applyWindowBrightnessOff(this)
        }
        params = lp
        try {
            wm.addView(this, lp)
            attached = true
            hideSystemBars()
            EventLog.i(TAG, "Overlay de apagado falso mostrado (TYPE_ACCESSIBILITY_OVERLAY)")
        } catch (t: Throwable) {
            EventLog.e(TAG, "No se pudo añadir el overlay", t)
            attached = false
        }
    }

    fun remove() {
        if (!attached) return
        try {
            windowManager?.removeViewImmediate(this)
            EventLog.i(TAG, "Overlay eliminado")
        } catch (t: Throwable) {
            try {
                windowManager?.removeView(this)
            } catch (t2: Throwable) {
                EventLog.w(TAG, "No se pudo quitar el overlay", t2)
            }
        } finally {
            attached = false
            windowManager = null
            params = null
        }
    }

    fun isShowing(): Boolean = attached && isAttachedToWindow

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        hideSystemBars()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        attached = false
    }

    private fun hideSystemBars() {
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                windowInsetsController?.hide(
                    WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars(),
                )
            } else {
                @Suppress("DEPRECATION")
                systemUiVisibility = (SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or SYSTEM_UI_FLAG_FULLSCREEN
                    or SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
            }
        } catch (t: Throwable) {
            EventLog.w(TAG, "No se ocultaron las barras de sistema", t)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val idx = event.actionIndex
                val x = event.getX(idx)
                val y = event.getY(idx)
                handleShutdownTap(x, y)
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    beginSwipe(x, y)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (event.actionMasked == MotionEvent.ACTION_UP) {
                    endSwipe(event.x, event.y)
                } else {
                    swipeTracking = false
                }
            }
        }
        // Consumimos todos los toques para que nada "de abajo" reciba eventos.
        return true
    }

    private fun beginSwipe(x: Float, y: Float) {
        swipeStartX = x
        swipeStartY = y
        swipeStartTime = SystemClock.uptimeMillis()
        swipeTracking = true
        val h = height.coerceAtLeast(1)
        val w = width.coerceAtLeast(1)
        swipeStartedInUnlockZone = (y >= h * UNLOCK_START_Y) && (x <= w * UNLOCK_START_X)
    }

    private fun endSwipe(x: Float, y: Float) {
        if (!swipeTracking) return
        swipeTracking = false
        val h = height.coerceAtLeast(1)
        val dy = swipeStartY - y // positivo = hacia arriba
        val dx = x - swipeStartX
        val dt = SystemClock.uptimeMillis() - swipeStartTime
        val longEnough = dy >= h * UNLOCK_MIN_DY
        val mostlyVertical = abs(dy) > abs(dx) * 0.6f
        val notTooSlow = dt in 80..2_500
        val volumeHeld = UnlockComboDetector.isVolumeHeld()

        if (swipeStartedInUnlockZone && longEnough && mostlyVertical && notTooSlow) {
            if (volumeHeld) {
                EventLog.i(TAG, "Gesto de desbloqueo válido (volumen + swipe)")
                onUnlockGesture()
            } else {
                EventLog.i(TAG, "Swipe de desbloqueo sin volumen: no se desbloquea")
                onFailedUnlockAttempt()
            }
        } else if (swipeStartedInUnlockZone && longEnough && !volumeHeld) {
            onFailedUnlockAttempt()
        }
        // Otros swipes se ignoran (no penalizan).
    }

    private fun handleShutdownTap(x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        if (shutdownStep > 0 && now - shutdownLastStepAt > SHUTDOWN_STEP_MAX_GAP_MS) {
            shutdownStep = 0
        }
        val expected = shutdownStep
        val inZone = isInShutdownZone(expected, x, y)
        if (inZone) {
            shutdownStep = expected + 1
            shutdownLastStepAt = now
            EventLog.i(TAG, "Apagado real: paso $shutdownStep/5")
            if (shutdownStep >= 5) {
                shutdownStep = 0
                EventLog.i(TAG, "Gesto oculto de apagado real completado")
                onShutdownGesture()
            }
        } else if (isInShutdownZone(0, x, y)) {
            // Reinicia la secuencia si tocan de nuevo la primera esquina.
            shutdownStep = 1
            shutdownLastStepAt = now
        }
        // Un toque fuera de la zona esperada no reinicia a 0 de inmediato
        // (el timeout de 2 s se encarga), salvo que sea el primer paso.
    }

    private fun isInShutdownZone(step: Int, x: Float, y: Float): Boolean {
        val w = width.toFloat().coerceAtLeast(1f)
        val h = height.toFloat().coerceAtLeast(1f)
        val corner = (w.coerceAtMost(h)) * CORNER_FRACTION
        val centerR = (w.coerceAtMost(h)) * CENTER_FRACTION
        return when (step) {
            0 -> x <= corner && y <= corner
            1 -> x >= w - corner && y <= corner
            2 -> x >= w - corner && y >= h - corner
            3 -> x <= corner && y >= h - corner
            4 -> hypot((x - w / 2f).toDouble(), (y - h / 2f).toDouble()) <= centerR
            else -> false
        }
    }

    companion object {
        private const val TAG = "FakeOffOverlay"
        const val OVERLAY_TITLE = "AntirroboFakeOff"
        private const val UNLOCK_START_Y = 0.72f
        private const val UNLOCK_START_X = 0.45f
        private const val UNLOCK_MIN_DY = 0.45f
        private const val SHUTDOWN_STEP_MAX_GAP_MS = 2_000L
        private const val CORNER_FRACTION = 0.18f
        private const val CENTER_FRACTION = 0.16f
    }
}
