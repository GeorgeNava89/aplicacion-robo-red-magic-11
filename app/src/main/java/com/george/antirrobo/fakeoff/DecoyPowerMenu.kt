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
    private val style: Style = Style.SYSTEM,
) : FrameLayout(service) {

    /** v0.1.4: SYSTEM = igual al menú real del Red Magic 11 Pro; CLASSIC = diseño v0.1.3. */
    enum class Style { SYSTEM, CLASSIC }

    private var blurListener: java.util.function.Consumer<Boolean>? = null

    private var wm: WindowManager? = null
    var shownAt: Long = 0L
        private set
    private var done = false

    init {
        setBackgroundColor(SCRIM_NO_BLUR)
        isHapticFeedbackEnabled = false
        isSoundEffectsEnabled = false
        alpha = 0f
        if (style == Style.SYSTEM) {
            addView(
                SystemStyleMenu(context,
                    onPowerOff = { finish(onPowerOff, "apagar") },
                    onRestart = { finish(onRestart, "reiniciar") },
                    onOutside = { dismiss("toque fuera del menú") }),
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
            )
        } else {
            buildClassic()
        }
    }

    private fun buildClassic() {
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
    }

    fun show(): Boolean {
        val m = service.getSystemService(WindowManager::class.java) ?: return false
        return try {
            val params = fullScreenOverlayParams("AntirroboDecoy", opaque = false)
            applyHeavyBlur(m, params)
            m.addView(this, params)
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

    /**
     * v0.1.4: desenfoque MUCHO más fuerte. En Android 12+ con desenfoque entre
     * ventanas activo: FLAG_BLUR_BEHIND con radio [BLUR_RADIUS_PX] + velo oscuro
     * de ~60 %. Sin desenfoque (o si el sistema lo apaga, p. ej. ahorro de
     * batería): velo de ~85 % para que el fondo casi no se vea.
     * Nota: backgroundBlurRadius solo existe en Window (actividades), no en
     * ventanas agregadas con WindowManager.addView, así que aquí no aplica.
     */
    private fun applyHeavyBlur(m: WindowManager, params: WindowManager.LayoutParams) {
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
            params.blurBehindRadius = BLUR_RADIUS_PX
            val enabled = try { m.isCrossWindowBlurEnabled } catch (_: Throwable) { false }
            setBackgroundColor(if (enabled) SCRIM_WITH_BLUR else SCRIM_NO_BLUR)
            EventLog.i(TAG, "Desenfoque entre ventanas: $enabled (radio $BLUR_RADIUS_PX px)")
            val l = java.util.function.Consumer<Boolean> { on ->
                post { setBackgroundColor(if (on) SCRIM_WITH_BLUR else SCRIM_NO_BLUR) }
            }
            try {
                m.addCrossWindowBlurEnabledListener(l)
                blurListener = l
            } catch (_: Throwable) {
            }
        } else {
            setBackgroundColor(SCRIM_NO_BLUR)
        }
    }

    fun remove() {
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            blurListener?.let { l -> try { wm?.removeCrossWindowBlurEnabledListener(l) } catch (_: Throwable) {} }
            blurListener = null
        }
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

    /**
     * v0.1.4 — Estilo "Igual al sistema (Red Magic)", según la captura real del
     * Red Magic 11 Pro: fondo desenfocado; dos botones circulares (~74 dp,
     * relleno blanco ~25 %) a 33 % y 67 % del ancho y ~45 % del alto; ícono de
     * encendido (círculo abierto arriba + línea) "Apagar" y ícono de reinicio
     * (8 rayitas en estrella) "Reiniciar"; etiquetas blancas 14 sp; abajo
     * (~92 %) texto gris 12 sp: "Mantenga presionado el botón de encendido por
     * 10 segundos para reiniciar el teléfono.". Sin título, sin deslizador,
     * sin botón de emergencia. Un toque en Apagar/Reiniciar actúa.
     */
    private class SystemStyleMenu(
        context: Context,
        private val onPowerOff: () -> Unit,
        private val onRestart: () -> Unit,
        private val onOutside: () -> Unit,
    ) : View(context) {
        private val dp = resources.displayMetrics.density
        private val sp = resources.displayMetrics.scaledDensity
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(64, 255, 255, 255) }
        private val fillPressed = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(110, 255, 255, 255) }
        private val icon = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
            strokeWidth = 2.4f * dp
        }
        private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textAlign = Paint.Align.CENTER; textSize = 14 * sp
        }
        private val hint = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(190, 220, 220, 220); textSize = 12 * sp
        }
        private var pressed = -1 // 0 = Apagar, 1 = Reiniciar
        private var downAt = -1

        init {
            isHapticFeedbackEnabled = false
            isSoundEffectsEnabled = false
        }

        private val radius get() = 37 * dp
        private fun cx(i: Int) = width * (if (i == 0) 0.33f else 0.67f)
        private val cy get() = height * 0.45f

        private fun hit(x: Float, y: Float): Int {
            // Zona táctil: el círculo y su etiqueta.
            for (i in 0..1) {
                val dx = x - cx(i)
                if (kotlin.math.abs(dx) <= radius * 1.15f && y >= cy - radius * 1.15f && y <= cy + radius + 34 * dp) return i
            }
            return -1
        }

        override fun onDraw(canvas: Canvas) {
            val r = radius
            for (i in 0..1) {
                val x = cx(i)
                canvas.drawCircle(x, cy, r, if (pressed == i) fillPressed else fill)
                if (i == 0) drawPowerIcon(canvas, x, cy) else drawRestartIcon(canvas, x, cy)
                canvas.drawText(if (i == 0) "Apagar" else "Reiniciar", x, cy + r + 24 * dp, label)
            }
            val text = "Mantenga presionado el botón de encendido por 10 segundos para reiniciar el teléfono."
            val w = (width * 0.86f).toInt().coerceAtLeast(1)
            val layout = android.text.StaticLayout.Builder.obtain(text, 0, text.length, hint, w)
                .setAlignment(android.text.Layout.Alignment.ALIGN_CENTER)
                .setMaxLines(3)
                .build()
            canvas.save()
            canvas.translate((width - w) / 2f, height * 0.92f - layout.height / 2f)
            layout.draw(canvas)
            canvas.restore()
        }

        private fun drawPowerIcon(c: Canvas, x: Float, y: Float) {
            val ir = 11 * dp
            // Círculo abierto arriba (hueco de 60° centrado en 270°) + línea vertical.
            c.drawArc(RectF(x - ir, y - ir, x + ir, y + ir), -60f, 300f, false, icon)
            c.drawLine(x, y - ir - 2 * dp, x, y - 1 * dp, icon)
        }

        private fun drawRestartIcon(c: Canvas, x: Float, y: Float) {
            // "Sol" de 8 rayitas cortas.
            val r0 = 5.5f * dp
            val r1 = 12f * dp
            for (k in 0 until 8) {
                val a = Math.toRadians(k * 45.0 - 90.0)
                val cs = Math.cos(a).toFloat()
                val sn = Math.sin(a).toFloat()
                c.drawLine(x + cs * r0, y + sn * r0, x + cs * r1, y + sn * r1, icon)
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downAt = hit(e.x, e.y)
                    pressed = downAt
                    invalidate()
                }
                MotionEvent.ACTION_MOVE -> {
                    val now = if (hit(e.x, e.y) == downAt) downAt else -1
                    if (now != pressed) { pressed = now; invalidate() }
                }
                MotionEvent.ACTION_UP -> {
                    val up = hit(e.x, e.y)
                    pressed = -1
                    invalidate()
                    when {
                        downAt == -1 && up == -1 -> onOutside()
                        up == downAt && up == 0 -> onPowerOff()
                        up == downAt && up == 1 -> onRestart()
                    }
                }
                MotionEvent.ACTION_CANCEL -> { pressed = -1; invalidate() }
            }
            return true
        }
    }

    companion object {
        private const val TAG = "DecoyMenu"
        const val BLUR_RADIUS_PX = 250
        /** Velo con desenfoque (~60 %, gris oscuro) y sin desenfoque (~85 %). */
        val SCRIM_WITH_BLUR = Color.argb(150, 16, 16, 20)
        val SCRIM_NO_BLUR = Color.argb(218, 12, 12, 16)
    }
}
