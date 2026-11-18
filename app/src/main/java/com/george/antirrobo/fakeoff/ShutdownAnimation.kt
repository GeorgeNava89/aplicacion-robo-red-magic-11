package com.george.antirrobo.fakeoff

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import com.george.antirrobo.R
import com.george.antirrobo.util.EventLog

/**
 * Animación de apagado / reinicio / arranque.
 *
 * v0.1.4: se dibuja TODO en un Canvas (logo + spinner + texto) en lugar de
 * TextViews dentro de LinearLayouts con WRAP_CONTENT. En v0.1.3 el wordmark
 * se veía solo como "R" y "Apagando…" se cortaba en la "g": el texto se
 * cortaba al medir el layout (fuente condensada en cursiva + letterSpacing,
 * dentro de una columna WRAP_CONTENT sobre una ventana de accesibilidad).
 * Ahora el texto se MIDE con Paint.measureText y se reduce si no cabe
 * ([TextFit]), y el logo es un bitmap escalado a ~60 % del ancho.
 *
 * Logo: "Redmagic official logo.png" de Wikimedia Commons (CC BY-SA 4.0,
 * usuario Albert12mfjfr), con el fondo vuelto transparente y las letras en
 * blanco. Si no carga, se dibuja el texto "RED MAGIC" medido para caber.
 */
@SuppressLint("ViewConstructor")
class ShutdownAnimation(
    private val service: AccessibilityService,
    private val kind: Kind,
) : View(service) {

    enum class Kind { SHUTDOWN, RESTART, BOOT }

    private val main = Handler(Looper.getMainLooper())
    private var removed = false
    private val dp = resources.displayMetrics.density
    private val sp = resources.displayMetrics.scaledDensity

    private val logo: Bitmap? = try {
        BitmapFactory.decodeResource(resources, R.drawable.redmagic_logo)
    } catch (_: Throwable) { null }

    private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val wordPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
        letterSpacing = 0.12f
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(210, 255, 255, 255)
        textAlign = Paint.Align.CENTER
    }
    private val spinPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Color.WHITE
    }
    private val startedAt = SystemClock.uptimeMillis()

    val label: String? = when (kind) {
        Kind.SHUTDOWN -> "Apagando…"
        Kind.RESTART -> "Reiniciando…"
        Kind.BOOT -> null
    }

    init {
        setBackgroundColor(Color.BLACK)
        isHapticFeedbackEnabled = false
        isSoundEffectsEnabled = false
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val a = contentAlpha
        var y = h * 0.36f

        // Logo (bitmap) o wordmark medido.
        val bmp = logo
        if (bmp != null) {
            val targetW = w * 0.6f
            val targetH = targetW * bmp.height / bmp.width
            val dst = RectF(w / 2 - targetW / 2, y - targetH / 2, w / 2 + targetW / 2, y + targetH / 2)
            bmpPaint.alpha = (255 * a).toInt()
            canvas.drawBitmap(bmp, Rect(0, 0, bmp.width, bmp.height), dst, bmpPaint)
            y = dst.bottom
        } else {
            wordPaint.textSize = 34 * sp
            wordPaint.textSize = TextFit.fitSize(wordPaint.textSize, wordPaint.measureText("RED MAGIC"), w * 0.7f)
            wordPaint.alpha = (255 * a).toInt()
            canvas.drawText("RED MAGIC", w / 2, y, wordPaint)
            y += wordPaint.textSize / 2
        }
        if (label == null) return

        // Spinner propio (arco que gira).
        val r = 18 * dp
        val cy = y + 56 * dp
        spinPaint.strokeWidth = 3 * dp
        spinPaint.alpha = (230 * a).toInt()
        val angle = ((SystemClock.uptimeMillis() - startedAt) % 1000L) * 0.36f
        canvas.drawArc(RectF(w / 2 - r, cy - r, w / 2 + r, cy + r), angle, 270f, false, spinPaint)

        // Texto medido: nunca se corta.
        textPaint.textSize = TextFit.fitSize(16 * sp, run { textPaint.textSize = 16 * sp; textPaint.measureText(label) }, w * 0.9f)
        textPaint.alpha = (210 * a).toInt()
        canvas.drawText(label, w / 2, cy + r + 36 * dp, textPaint)
        postInvalidateOnAnimation()
    }

    private var contentAlpha = 0f
        set(v) { field = v; invalidate() }

    /**
     * Muestra: aparece en [fadeInMs], se mantiene hasta [holdMs] (contado desde
     * que empieza a aparecer), se desvanece en [fadeOutMs] y llama [onDone].
     */
    fun play(
        holdMs: Long,
        startDelayMs: Long = 0L,
        fadeInMs: Long = 400L,
        fadeOutMs: Long = 600L,
        onDone: () -> Unit,
    ): Boolean {
        val wm = service.getSystemService(android.view.WindowManager::class.java) ?: return false
        return try {
            wm.addView(this, fullScreenOverlayParams("AntirroboAnim", opaque = true))
            fade(0f, 1f, fadeInMs, startDelayMs) {}
            main.postDelayed({
                fade(1f, 0f, fadeOutMs, 0L) {
                    onDone()
                    // Se quita DESPUÉS de que el apagado falso ya está encima (sin parpadeo).
                    main.postDelayed({ remove(wm) }, 150)
                }
            }, startDelayMs + holdMs)
            EventLog.i(TAG, "Animación $kind (entrada $fadeInMs, total ${holdMs + fadeOutMs} ms)")
            true
        } catch (t: Throwable) {
            EventLog.e(TAG, "No se pudo mostrar la animación", t)
            false
        }
    }

    private fun fade(from: Float, to: Float, ms: Long, delay: Long, end: () -> Unit) {
        android.animation.ValueAnimator.ofFloat(from, to).apply {
            duration = ms
            startDelay = delay
            addUpdateListener { contentAlpha = it.animatedValue as Float }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) = end()
            })
        }.start()
    }

    fun remove(wm: android.view.WindowManager? = service.getSystemService(android.view.WindowManager::class.java)) {
        if (removed) return
        removed = true
        main.removeCallbacksAndMessages(null)
        wm?.safeRemove(this)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean = true

    companion object {
        private const val TAG = "ShutdownAnim"

        /**
         * Tiempos de "Apagando…". v0.1.3: entrada 400 ms, visible 2 200 ms +
         * salida 600 ms = 2.8 s. v0.1.4: el DOBLE = 5.6 s (800 / 4 400 / 1 200).
         */
        const val SHUTDOWN_FADE_IN_MS = 800L
        const val SHUTDOWN_HOLD_MS = 4_400L
        const val SHUTDOWN_FADE_OUT_MS = 1_200L
        const val SHUTDOWN_TOTAL_MS = SHUTDOWN_HOLD_MS + SHUTDOWN_FADE_OUT_MS
    }
}

/** Ajuste de texto puro (probado en unidad): reduce el tamaño para que quepa. */
object TextFit {
    fun fitSize(size: Float, measuredWidth: Float, available: Float): Float =
        if (measuredWidth <= available || measuredWidth <= 0f) size else size * (available / measuredWidth) * 0.98f
}
