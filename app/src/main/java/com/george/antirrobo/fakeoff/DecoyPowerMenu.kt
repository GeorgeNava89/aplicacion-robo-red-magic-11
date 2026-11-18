package com.george.antirrobo.fakeoff

import android.accessibilityservice.AccessibilityService
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.george.antirrobo.util.EventLog

/**
 * v0.1.3 — Menú de apagado FALSO (señuelo).
 *
 * Referencia de diseño: el manual del Red Magic 10 Pro indica "mantén
 * presionado Encendido y toca 'Deslizar para apagar'", y usuarios del 11 Pro
 * describen un menú con "Apagar" y "Reiniciar"; el menú del panel rápido
 * incluye "Emergencia". No encontramos capturas fiables del menú del Red
 * Magic OS 10, así que el aspecto es una aproximación (fondo oscuro
 * translúcido, control "Desliza para apagar", Reiniciar y Emergencia),
 * dibujado con vistas propias, sin imágenes del fabricante.
 *
 * Tocar fuera del panel o "Atrás" lo cierra (la protección sigue armada).
 * El botón de Emergencia SIEMPRE abre el marcador de emergencia real.
 */
@SuppressLint("ViewConstructor", "SetTextI18n")
class DecoyPowerMenu(
    private val service: AccessibilityService,
    private val onPowerOff: () -> Unit,
    private val onRestart: () -> Unit,
    private val onEmergency: () -> Unit,
    private val onDismiss: () -> Unit,
) : FrameLayout(service) {

    private var wm: WindowManager? = null
    var shownAt: Long = 0L
        private set
    private var done = false

    init {
        setBackgroundColor(Color.argb(200, 0, 0, 0))
        isHapticFeedbackEnabled = false
        isSoundEffectsEnabled = false
        val dp = resources.displayMetrics.density

        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            isClickable = true // los toques dentro del panel no cierran el menú
        }

        column.addView(SlideToPowerOff(context) { finish(onPowerOff, "deslizar para apagar") },
            LinearLayout.LayoutParams((300 * dp).toInt(), (72 * dp).toInt()))

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        row.addView(roundButton("⟳", "Reiniciar") { finish(onRestart, "reiniciar") })
        row.addView(View(context), LinearLayout.LayoutParams((56 * dp).toInt(), 1))
        row.addView(roundButton("✆", "Emergencia", emergency = true) { finish(onEmergency, "emergencia") })
        column.addView(row, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = (40 * dp).toInt() })

        addView(column, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        alpha = 0f
    }

    fun show(): Boolean {
        val m = service.getSystemService(WindowManager::class.java) ?: return false
        return try {
            m.addView(this, fullScreenOverlayParams("AntirroboDecoy", opaque = false))
            wm = m
            shownAt = android.os.SystemClock.uptimeMillis()
            animate().alpha(1f).setDuration(160).start()
            EventLog.i(TAG, "Menú falso mostrado")
            true
        } catch (t: Throwable) {
            EventLog.e(TAG, "No se pudo mostrar el menú falso", t)
            false
        }
    }

    fun remove() {
        wm?.safeRemove(this)
        wm = null
    }

    /** Cerrar como el menú real (tocar fuera / Atrás). */
    fun dismiss(reason: String) {
        finish(onDismiss, reason)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Toque en el fondo (fuera del panel) = cerrar.
        if (event.actionMasked == MotionEvent.ACTION_UP) dismiss("toque fuera del menú")
        return true
    }

    private fun finish(action: () -> Unit, reason: String) {
        if (done) return
        done = true
        EventLog.i(TAG, "Menú falso: $reason")
        remove()
        action()
    }

    private fun roundButton(glyph: String, label: String, emergency: Boolean = false, onClick: () -> Unit): View {
        val dp = resources.displayMetrics.density
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            isHapticFeedbackEnabled = false
            isSoundEffectsEnabled = false
            setOnClickListener { onClick() }
        }
        val circle = TextView(context).apply {
            text = glyph
            textSize = 26f
            gravity = Gravity.CENTER
            setTextColor(if (emergency) Color.WHITE else Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (emergency) Color.rgb(200, 30, 30) else Color.argb(70, 255, 255, 255))
            }
        }
        box.addView(circle, LinearLayout.LayoutParams((68 * dp).toInt(), (68 * dp).toInt()))
        box.addView(TextView(context).apply {
            text = label
            textSize = 14f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, (8 * dp).toInt(), 0, 0)
        })
        return box
    }

    /** Control "Desliza para apagar": píldora con un pulgar que se arrastra a la derecha. */
    private class SlideToPowerOff(context: Context, private val onComplete: () -> Unit) : View(context) {
        private val dp = resources.displayMetrics.density
        private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(70, 255, 255, 255) }
        private val thumb = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private val icon = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(225, 6, 0); style = Paint.Style.STROKE; strokeWidth = 3 * dp
            strokeCap = Paint.Cap.ROUND
        }
        private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = 16 * dp; textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        }
        private var offset = 0f
        private var dragging = false
        private var downX = 0f
        private var completed = false

        init {
            isHapticFeedbackEnabled = false
            isSoundEffectsEnabled = false
        }

        private val radius get() = height / 2f
        private val maxOffset get() = width - height.toFloat()

        override fun onDraw(canvas: Canvas) {
            val r = radius
            canvas.drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), r, r, track)
            label.alpha = (255 * (1f - (offset / maxOffset.coerceAtLeast(1f)))).toInt().coerceIn(0, 255)
            canvas.drawText("Desliza para apagar", width / 2f + r / 2, height / 2f + label.textSize / 3, label)
            val cx = r + offset
            canvas.drawCircle(cx, r, r - 6 * dp, thumb)
            // Ícono de encendido dibujado (arco + línea).
            val ir = r * 0.38f
            canvas.drawArc(RectF(cx - ir, r - ir, cx + ir, r + ir), -60f, 300f, false, icon)
            canvas.drawLine(cx, r - ir * 1.25f, cx, r - ir * 0.15f, icon)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (completed) return true
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragging = e.x <= radius * 2 + offset
                    downX = e.x - offset
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                MotionEvent.ACTION_MOVE -> if (dragging) {
                    offset = (e.x - downX).coerceIn(0f, maxOffset)
                    invalidate()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging && offset >= maxOffset * 0.85f) {
                        completed = true
                        offset = maxOffset
                        invalidate()
                        onComplete()
                    } else {
                        ValueAnimator.ofFloat(offset, 0f).apply {
                            duration = 200
                            interpolator = DecelerateInterpolator()
                            addUpdateListener { offset = it.animatedValue as Float; invalidate() }
                        }.start()
                    }
                    dragging = false
                }
            }
            return true
        }
    }

    companion object {
        private const val TAG = "DecoyMenu"
    }
}
