package com.george.antirrobo.lockmode

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import com.george.antirrobo.fakeoff.fullScreenOverlayParams
import com.george.antirrobo.fakeoff.safeRemove
import com.george.antirrobo.unlock.TapSequenceDetector
import com.george.antirrobo.util.EventLog

/**
 * v0.1.4 — Ventana de "Bloqueo con luces". Pantalla encendida y atenuada,
 * consume TODOS los toques; solo reconoce la secuencia de 5 toques (misma
 * lógica y zonas que el apagado real) y el botón "Emergencia" de abajo.
 */
@SuppressLint("ViewConstructor")
class LockOverlay(
    private val service: AccessibilityService,
    private val ghost: Boolean,
    private val demo: Boolean,
    private val onGesture: () -> Unit,
    private val onEmergency: () -> Unit,
) : View(service) {

    private val dp = resources.displayMetrics.density
    private val sp = resources.displayMetrics.scaledDensity
    private val taps = TapSequenceDetector()
    private var wm: WindowManager? = null
    private var params: WindowManager.LayoutParams? = null
    private var lastTouch = SystemClock.uptimeMillis()

    private val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 255, 255, 255); textAlign = Paint.Align.CENTER; textSize = 20 * sp
    }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(150, 255, 255, 255); textAlign = Paint.Align.CENTER; textSize = 13 * sp
    }
    private val emergencyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 255, 90, 80); textAlign = Paint.Align.CENTER; textSize = 15 * sp
    }
    private val zoneFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val zoneNum = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 255, 255, 255); textAlign = Paint.Align.CENTER; textSize = 40 * sp
        isFakeBoldText = true
    }

    init {
        setBackgroundColor(Color.BLACK)
        isHapticFeedbackEnabled = false
        isSoundEffectsEnabled = false
    }

    fun show(): Boolean {
        val m = service.getSystemService(WindowManager::class.java) ?: return false
        return try {
            val p = fullScreenOverlayParams("AntirroboLock", opaque = true).apply {
                screenBrightness = BRIGHT
            }
            m.addView(this, p)
            wm = m
            params = p
            postDelayed(dimCheck, 1_000L)
            EventLog.i(TAG, "Overlay de bloqueo mostrado (fantasma=$ghost, demo=$demo)")
            true
        } catch (t: Throwable) {
            EventLog.e(TAG, "No se pudo mostrar el bloqueo", t)
            false
        }
    }

    fun remove() {
        removeCallbacks(dimCheck)
        wm?.safeRemove(this)
        wm = null
    }

    /** Se atenúa a los 15 s sin toques; la pantalla sigue ENCENDIDA (luces RGB igual). */
    private val dimCheck = object : Runnable {
        override fun run() {
            val p = params ?: return
            val want = if (SystemClock.uptimeMillis() - lastTouch > 15_000L) DIM else BRIGHT
            if (p.screenBrightness != want) {
                p.screenBrightness = want
                try { wm?.updateViewLayout(this@LockOverlay, p) } catch (_: Throwable) {}
                invalidate()
            }
            postDelayed(this, 1_000L)
        }
    }

    private fun emergencyRect(): RectF {
        val w = width.toFloat()
        val h = height.toFloat()
        return RectF(w * 0.3f, h * 0.86f, w * 0.7f, h * 0.94f)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (params?.screenBrightness == DIM) return // casi negro al atenuarse
        canvas.drawText("Bloqueado", w / 2, h * 0.42f, title)
        if (demo) canvas.drawText("Prueba: se quita sola en 30 s", w / 2, h * 0.42f + 28 * dp, small)
        val e = emergencyRect()
        canvas.drawText("Emergencia", e.centerX(), e.centerY() + emergencyPaint.textSize / 3, emergencyPaint)

        if (!ghost) return
        val loc = IntArray(2).also { getLocationOnScreen(it) }
        val (sw, sh) = screenSize()
        canvas.save()
        canvas.translate(-loc[0].toFloat(), -loc[1].toFloat())
        val step = taps.currentStep(SystemClock.uptimeMillis())
        for (i in 0 until TapSequenceDetector.TOTAL) {
            val g = taps.zoneGeometry(i, sw, sh)
            val done = i < step
            zoneFill.color = if (done) Color.argb(200, 76, 175, 80) else Color.argb(55, 76, 175, 80)
            val (cx, cy) = if (i < 4) {
                val r = RectF(g[0], g[1], g[2], g[3])
                canvas.drawRoundRect(r, 24f, 24f, zoneFill)
                r.centerX() to r.centerY()
            } else {
                canvas.drawCircle(g[0], g[1], g[2], zoneFill)
                g[0] to g[1]
            }
            canvas.drawText(if (done) "✓" else "${i + 1}", cx, cy + zoneNum.textSize / 3, zoneNum)
        }
        canvas.restore()
    }

    private fun screenSize(): Pair<Float, Float> {
        if (Build.VERSION.SDK_INT >= 30) {
            val b = service.getSystemService(WindowManager::class.java)?.currentWindowMetrics?.bounds
            if (b != null) return b.width().toFloat() to b.height().toFloat()
        }
        val dm = resources.displayMetrics
        return dm.widthPixels.toFloat() to dm.heightPixels.toFloat()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val wasDim = params?.screenBrightness == DIM
        lastTouch = SystemClock.uptimeMillis()
        if (wasDim) {
            post(dimCheck)
            return true // el primer toque solo "despierta"
        }
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            if (emergencyRect().contains(event.x, event.y)) {
                onEmergency()
                return true
            }
            val (w, h) = screenSize()
            val r = taps.onTap(event.rawX, event.rawY, w, h, event.eventTime)
            EventLog.i(TAG, "Toque (${event.rawX.toInt()}, ${event.rawY.toInt()}) → $r paso=${taps.step}")
            if (ghost) {
                invalidate()
                postDelayed({ invalidate() }, 3_100L)
            }
            if (r == TapSequenceDetector.Result.Completed) onGesture()
        }
        return true
    }

    companion object {
        private const val TAG = "LockOverlay"
        private const val BRIGHT = 0.25f
        private const val DIM = 0.01f
    }
}
