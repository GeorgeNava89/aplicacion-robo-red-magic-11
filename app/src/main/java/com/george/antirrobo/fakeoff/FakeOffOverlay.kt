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
import com.george.antirrobo.unlock.SwipeUnlockDetector
import com.george.antirrobo.unlock.TapSequenceDetector
import com.george.antirrobo.unlock.UnlockComboDetector
import com.george.antirrobo.util.EventLog

/**
 * Overlay negro a pantalla completa con TYPE_ACCESSIBILITY_OVERLAY
 * (no requiere permiso de "mostrar sobre otras apps" ni root).
 *
 * Gestos (v0.1.3, más tolerantes):
 *
 *  DESBLOQUEO: VOLUMEN (arriba o abajo) presionado en algún momento del
 *  deslizamiento + deslizar hacia arriba empezando en la mitad inferior
 *  izquierda, al menos 30 % de la altura. Empieza un poco arriba del borde
 *  inferior (el borde lo usa la navegación por gestos del sistema).
 *
 *  APAGADO REAL: 5 toques, zonas de 25 % en cada esquina, hasta 3 s entre
 *  toques: sup-izq → sup-der → inf-der → inf-izq → centro.
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
    /** v0.1.3: "gestos fantasma" (zonas exactas) — ajuste, apagado por defecto. */
    private val debugHints: Boolean = false,
) : View(service) {

    private var windowManager: WindowManager? = null
    private var attached = false
    private var params: WindowManager.LayoutParams? = null


    init {
        setBackgroundColor(Color.BLACK)
        setWillNotDraw(false)
        isClickable = true
        isFocusable = false
        // v0.1.1: cero hápticos y cero sonidos de toque desde el overlay.
        isHapticFeedbackEnabled = false
        isSoundEffectsEnabled = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        keepScreenOn = true
        contentDescription = null
        if (Build.VERSION.SDK_INT >= 29) {
            isForceDarkAllowed = false
        }
    }

    /** Devuelve true si addView() tuvo éxito (la vista se adjunta en el siguiente frame). */
    fun show(): Boolean {
        if (attached) return true
        val wm = service.getSystemService(WindowManager::class.java) ?: return false
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
            // v0.1.3 — CAUSA PROBABLE de los gestos fallidos: en Android 11+ una
            // ventana nueva trae fitInsetsTypes = barras del sistema, así que el
            // overlay NO cubría la barra de estado ni la de navegación: los
            // toques en las esquinas superiores caían en la barra de estado y el
            // borde inferior en la navegación. Ahora cubre TODA la pantalla.
            if (Build.VERSION.SDK_INT >= 30) {
                fitInsetsTypes = 0
                fitInsetsSides = 0
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
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
        return attached
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

    fun isShowing(): Boolean = attached


    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        // v0.1.3: pide al sistema no tratar los bordes como gesto "atrás"
        // (Android limita la exclusión a ~200 dp por borde).
        if (Build.VERSION.SDK_INT >= 29) {
            systemGestureExclusionRects = listOf(android.graphics.Rect(0, 0, width, height))
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        if (debugHints) UnlockComboDetector.listener = null
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

    private val taps = TapSequenceDetector()
    private val swipe = SwipeUnlockDetector()

    /**
     * v0.1.3: el manejo de toques usa la lógica pura de GestureLogic.kt,
     * registra cada etapa en la bitácora y evalúa también ACTION_CANCEL
     * (la navegación por gestos del sistema puede robar el toque cerca del
     * borde inferior y antes el deslizamiento se perdía).
     */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        // v0.1.3: coordenadas absolutas (rawX/rawY) contra el tamaño REAL de
        // la pantalla, por si la ventana no la cubriera completa.
        val (w, h) = screenSize()
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val idx = event.actionIndex
                val x = if (Build.VERSION.SDK_INT >= 29) event.getRawX(idx) else event.rawX
                val y = if (Build.VERSION.SDK_INT >= 29) event.getRawY(idx) else event.rawY
                EventLog.i(
                    TAG,
                    "TOQUE raw=(${x.toInt()},${y.toInt()}) local=(${event.getX(idx).toInt()},${event.getY(idx).toInt()}) " +
                        "vista=${width}x$height pantalla=${w.toInt()}x${h.toInt()} volumen=${UnlockComboDetector.isVolumeHeld()}",
                )
                val r = taps.onTap(x, y, w, h, event.eventTime)
                if (debugHints) {
                    invalidate()
                    // Para que el reinicio por tiempo (3 s) también se vea.
                    postDelayed({ invalidate() }, 3_100L)
                }
                when (r) {
                    is TapSequenceDetector.Result.Progress ->
                        EventLog.i(TAG, "Apagado real: paso ${r.step}/5 (x=${x.toInt()}, y=${y.toInt()})")
                    TapSequenceDetector.Result.Completed -> {
                        EventLog.i(TAG, "Apagado real: secuencia completa")
                        onShutdownGesture()
                        return true
                    }
                    is TapSequenceDetector.Result.Ignored ->
                        EventLog.i(TAG, "Toque fuera de secuencia (${r.reason})")
                }
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    swipe.onDown(x, y, w, h, event.eventTime)
                }
            }
            MotionEvent.ACTION_MOVE -> swipe.onMove(event.rawX, event.rawY)
            MotionEvent.ACTION_UP -> evaluateSwipe(event.rawX, event.rawY, h, event.eventTime, cancelled = false)
            MotionEvent.ACTION_CANCEL -> evaluateSwipe(null, null, h, event.eventTime, cancelled = true)
        }
        return true
    }

    private fun screenSize(): Pair<Float, Float> {
        return try {
            val wm = windowManager ?: service.getSystemService(WindowManager::class.java)
            if (Build.VERSION.SDK_INT >= 30 && wm != null) {
                val b = wm.currentWindowMetrics.bounds
                b.width().toFloat() to b.height().toFloat()
            } else {
                val dm = resources.displayMetrics
                dm.widthPixels.toFloat() to dm.heightPixels.toFloat()
            }
        } catch (_: Throwable) {
            width.toFloat() to height.toFloat()
        }
    }

    // ---------- v0.1.3: "gestos fantasma" ----------
    private val zoneFill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.FILL
    }
    private val zoneStroke = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = 4f
        color = Color.argb(160, 76, 175, 80)
    }
    private val numberPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 255, 255, 255)
        textAlign = android.graphics.Paint.Align.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    private val labelPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(170, 255, 255, 255)
        textAlign = android.graphics.Paint.Align.CENTER
    }
    private val dashed = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = 4f
        color = Color.argb(140, 100, 181, 246)
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(18f, 14f), 0f)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        hideSystemBars()
        if (debugHints) UnlockComboDetector.listener = { post { invalidate() } }
    }

    /**
     * Dibuja las zonas EXACTAS de la secuencia de 5 toques (misma geometría que
     * TapSequenceDetector.zoneOf) y la zona de inicio del deslizamiento. Solo si
     * [debugHints] (ajuste "Mostrar gestos fantasma").
     */
    override fun onDraw(canvas: android.graphics.Canvas) {
        super.onDraw(canvas)
        if (!debugHints) return
        val loc = IntArray(2).also { getLocationOnScreen(it) }
        val (w, h) = screenSize()
        val d = resources.displayMetrics.density
        numberPaint.textSize = 44 * d
        labelPaint.textSize = 15 * d
        canvas.save()
        canvas.translate(-loc[0].toFloat(), -loc[1].toFloat()) // pantalla → vista

        // Zona del deslizamiento de desbloqueo.
        val sx = w * swipe.startMaxXFraction
        val sy = h * swipe.startMinYFraction
        canvas.drawRect(6f, sy, sx, h - 6f, dashed)
        val ax = sx / 2f
        canvas.drawLine(ax, h * 0.82f, ax, h * 0.58f, dashed)
        canvas.drawLine(ax, h * 0.58f, ax - 24 * d, h * 0.58f + 24 * d, dashed)
        canvas.drawLine(ax, h * 0.58f, ax + 24 * d, h * 0.58f + 24 * d, dashed)
        canvas.drawText("Mantén volumen + desliza", ax, h * 0.55f, labelPaint)

        // Zonas 1–5.
        for (i in 0 until TapSequenceDetector.TOTAL) {
            val g = taps.zoneGeometry(i, w, h)
            val done = i < taps.currentStep(android.os.SystemClock.uptimeMillis())
            zoneFill.color = if (done) Color.argb(200, 76, 175, 80) else Color.argb(55, 76, 175, 80)
            val (cx, cy) = if (i < 4) {
                val r = android.graphics.RectF(g[0], g[1], g[2], g[3])
                canvas.drawRoundRect(r, 24f, 24f, zoneFill)
                canvas.drawRoundRect(r, 24f, 24f, zoneStroke)
                r.centerX() to r.centerY()
            } else {
                canvas.drawCircle(g[0], g[1], g[2], zoneFill)
                canvas.drawCircle(g[0], g[1], g[2], zoneStroke)
                g[0] to g[1]
            }
            val text = if (done) "✓" else "${i + 1}"
            canvas.drawText(text, cx, cy + numberPaint.textSize / 3, numberPaint)
        }

        // Indicador de volumen detectado.
        val held = UnlockComboDetector.isVolumeHeld()
        labelPaint.color = if (held) Color.argb(230, 76, 175, 80) else Color.argb(120, 255, 255, 255)
        canvas.drawText(if (held) "● Volumen detectado" else "○ Volumen", w / 2f, h * 0.33f, labelPaint)
        labelPaint.color = Color.argb(170, 255, 255, 255)
        canvas.restore()
    }

    private fun evaluateSwipe(x: Float?, y: Float?, h: Float, now: Long, cancelled: Boolean) {
        val outcome = swipe.onEnd(x, y, h, now)
        if (outcome == SwipeUnlockDetector.Outcome.NONE) return
        if (outcome != SwipeUnlockDetector.Outcome.SWIPE_OK) {
            if (outcome != SwipeUnlockDetector.Outcome.TOO_SHORT) {
                EventLog.i(TAG, "Deslizamiento descartado: $outcome (cancel=$cancelled)")
            }
            return
        }
        val volume = UnlockComboDetector.volumeActiveDuring(swipe.startTime, now)
        EventLog.i(TAG, "Deslizamiento válido (cancel=$cancelled), volumen activo=$volume")
        if (volume) onUnlockGesture() else onFailedUnlockAttempt()
    }

    companion object {
        private const val TAG = "FakeOffOverlay"
        const val OVERLAY_TITLE = "AntirroboFakeOff"
    }
}
