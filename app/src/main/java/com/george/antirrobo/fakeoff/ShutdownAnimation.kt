package com.george.antirrobo.fakeoff

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.george.antirrobo.util.EventLog

/**
 * v0.1.3 — Animación de apagado / reinicio / arranque, dibujada con texto
 * (wordmark "RED MAGIC" en letras, sin imágenes del fabricante) + spinner.
 * Fondo negro opaco; consume todos los toques. Siempre termina sola.
 */
@SuppressLint("ViewConstructor", "SetTextI18n")
class ShutdownAnimation(
    private val service: AccessibilityService,
    private val kind: Kind,
) : FrameLayout(service) {

    enum class Kind { SHUTDOWN, RESTART, BOOT }

    private val main = Handler(Looper.getMainLooper())
    private var removed = false

    init {
        setBackgroundColor(Color.BLACK)
        isHapticFeedbackEnabled = false
        isSoundEffectsEnabled = false
        val dp = resources.displayMetrics.density
        val col = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        col.addView(TextView(context).apply {
            text = "RED MAGIC"
            textSize = 34f
            letterSpacing = 0.18f
            setTextColor(if (kind == Kind.BOOT) Color.rgb(225, 6, 0) else Color.WHITE)
            typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD_ITALIC)
            gravity = Gravity.CENTER
        })
        if (kind != Kind.BOOT) {
            col.addView(ProgressBar(context).apply { isIndeterminate = true },
                LinearLayout.LayoutParams((40 * dp).toInt(), (40 * dp).toInt()).apply { topMargin = (36 * dp).toInt() })
            col.addView(TextView(context).apply {
                text = if (kind == Kind.SHUTDOWN) "Apagando…" else "Reiniciando…"
                textSize = 15f
                setTextColor(Color.argb(200, 255, 255, 255))
                gravity = Gravity.CENTER
                setPadding(0, (14 * dp).toInt(), 0, 0)
            })
        }
        addView(col, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
    }

    /** Muestra, espera [holdMs], se desvanece a negro y llama [onDone]. */
    fun play(holdMs: Long, startDelayMs: Long = 0L, onDone: () -> Unit): Boolean {
        val wm = service.getSystemService(android.view.WindowManager::class.java) ?: return false
        return try {
            val content = getChildAt(0)
            content.alpha = 0f
            wm.addView(this, fullScreenOverlayParams("AntirroboAnim", opaque = true))
            content.animate().alpha(1f).setStartDelay(startDelayMs).setDuration(400).start()
            main.postDelayed({
                content.animate().alpha(0f).setDuration(600).withEndAction {
                    onDone()
                    // Se quita DESPUÉS de que el apagado falso ya está encima (sin parpadeo).
                    main.postDelayed({ remove(wm) }, 150)
                }.start()
            }, startDelayMs + holdMs)
            EventLog.i(TAG, "Animación $kind (${holdMs} ms)")
            true
        } catch (t: Throwable) {
            EventLog.e(TAG, "No se pudo mostrar la animación", t)
            false
        }
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
    }
}
