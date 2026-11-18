package com.george.antirrobo.unlock

import kotlin.math.abs
import kotlin.math.hypot

/**
 * v0.1.3 — Lógica PURA (sin Android) de los gestos del apagado falso, para
 * poder probarla con pruebas unitarias. Los tiempos son en milisegundos de un
 * mismo reloj (en la app: MotionEvent.eventTime y KeyEvent.eventTime, ambos
 * SystemClock.uptimeMillis()).
 */

/** Secuencia oculta de 5 toques: sup-izq → sup-der → inf-der → inf-izq → centro. */
class TapSequenceDetector(
    val cornerFraction: Float = 0.25f,
    val centerFraction: Float = 0.25f,
    private val maxGapMs: Long = 3_000L,
) {
    sealed class Result {
        data class Progress(val step: Int) : Result()
        object Completed : Result()
        data class Ignored(val reason: String) : Result()
    }

    var step: Int = 0
        private set
    private var lastTapAt: Long = Long.MIN_VALUE

    fun reset() {
        step = 0
        lastTapAt = Long.MIN_VALUE
    }

    /** Paso vigente a la hora [now] (0 si ya venció el tiempo entre toques). */
    fun currentStep(now: Long): Int = if (step > 0 && now - lastTapAt > maxGapMs) 0 else step

    fun onTap(x: Float, y: Float, w: Float, h: Float, now: Long): Result {
        if (w <= 0f || h <= 0f) return Result.Ignored("tamaño inválido")
        if (step > 0 && now - lastTapAt > maxGapMs) {
            step = 0
        }
        val zone = zoneOf(x, y, w, h)
        if (zone == step) {
            step++
            lastTapAt = now
            if (step >= TOTAL) {
                reset()
                return Result.Completed
            }
            return Result.Progress(step)
        }
        // Tocar de nuevo la primera esquina reinicia en el paso 1.
        if (zone == 0) {
            step = 1
            lastTapAt = now
            return Result.Progress(1)
        }
        // Un toque fuera de lugar NO borra el progreso (tolerancia a toques
        // accidentales); solo el tiempo máximo entre toques lo reinicia.
        return Result.Ignored("zona=$zone esperada=$step")
    }

    /**
     * Geometría EXACTA de la zona [i] (la misma que usa [zoneOf]), para dibujar
     * los "gestos fantasma". Esquinas: [l, t, r, b]. Centro (i=4): [cx, cy, radio, 0].
     */
    fun zoneGeometry(i: Int, w: Float, h: Float): FloatArray {
        val cw = w * cornerFraction
        val ch = h * cornerFraction
        return when (i) {
            0 -> floatArrayOf(0f, 0f, cw, ch)
            1 -> floatArrayOf(w - cw, 0f, w, ch)
            2 -> floatArrayOf(w - cw, h - ch, w, h)
            3 -> floatArrayOf(0f, h - ch, cw, h)
            else -> floatArrayOf(w / 2f, h / 2f, minOf(w, h) * centerFraction, 0f)
        }
    }

    /** 0..3 = esquinas en orden, 4 = centro, -1 = ninguna. */
    fun zoneOf(x: Float, y: Float, w: Float, h: Float): Int {
        val cw = w * cornerFraction
        val ch = h * cornerFraction
        val left = x <= cw
        val right = x >= w - cw
        val top = y <= ch
        val bottom = y >= h - ch
        return when {
            top && left -> 0
            top && right -> 1
            bottom && right -> 2
            bottom && left -> 3
            hypot((x - w / 2f).toDouble(), (y - h / 2f).toDouble()) <= minOf(w, h) * centerFraction -> 4
            else -> -1
        }
    }

    companion object {
        const val TOTAL = 5
    }
}

/**
 * Deslizar hacia arriba desde la mitad inferior izquierda. Se evalúa también
 * en ACTION_CANCEL con la última posición conocida: con navegación por gestos,
 * el sistema puede "robar" el toque cerca del borde inferior y nos manda CANCEL.
 */
class SwipeUnlockDetector(
    val startMaxXFraction: Float = 0.5f,
    val startMinYFraction: Float = 0.45f,
    private val minDyFraction: Float = 0.30f,
    private val maxDurationMs: Long = 4_000L,
) {
    private var tracking = false
    private var startX = 0f
    private var startY = 0f
    private var lastX = 0f
    private var lastY = 0f
    var startTime = 0L
        private set
    private var startedInZone = false

    fun onDown(x: Float, y: Float, w: Float, h: Float, now: Long) {
        tracking = true
        startX = x; startY = y; lastX = x; lastY = y
        startTime = now
        startedInZone = x <= w * startMaxXFraction && y >= h * startMinYFraction
    }

    fun onMove(x: Float, y: Float) {
        if (!tracking) return
        lastX = x; lastY = y
    }

    enum class Outcome { NONE, SWIPE_OK, NOT_IN_ZONE, TOO_SHORT, TOO_SLOW, NOT_VERTICAL }

    /** Llamar en UP o CANCEL. [x]/[y] null = usar la última posición. */
    fun onEnd(x: Float?, y: Float?, h: Float, now: Long): Outcome {
        if (!tracking) return Outcome.NONE
        tracking = false
        val ex = x ?: lastX
        val ey = y ?: lastY
        val dy = startY - ey
        val dx = ex - startX
        return when {
            !startedInZone -> Outcome.NOT_IN_ZONE
            dy < h * minDyFraction -> Outcome.TOO_SHORT
            abs(dy) <= abs(dx) * 0.6f -> Outcome.NOT_VERTICAL
            now - startTime > maxDurationMs -> Outcome.TOO_SLOW
            else -> Outcome.SWIPE_OK
        }
    }
}

/**
 * Volumen "mantenido" de forma tolerante: cuenta si está presionado ahora, o
 * si se presionó/soltó dentro de la ventana del deslizamiento (con margen).
 */
class VolumeHoldTracker(private val graceMs: Long = 800L) {
    var held: Boolean = false
        private set
    var lastDownAt: Long = Long.MIN_VALUE
        private set
    var lastUpAt: Long = Long.MIN_VALUE
        private set

    fun onDown(now: Long) {
        held = true
        lastDownAt = now
    }

    fun onUp(now: Long) {
        held = false
        lastUpAt = now
    }

    fun reset() {
        held = false
        lastDownAt = Long.MIN_VALUE
        lastUpAt = Long.MIN_VALUE
    }

    /** ¿Hubo volumen activo en algún momento entre [from] y [to]? */
    fun activeDuring(from: Long, to: Long): Boolean {
        if (held) return true
        if (lastDownAt == Long.MIN_VALUE) return false
        // Presionado antes de terminar y soltado después de empezar (con margen).
        val pressedBeforeEnd = lastDownAt <= to + graceMs
        val releasedAfterStart = lastUpAt == Long.MIN_VALUE || lastUpAt >= from - graceMs
        return pressedBeforeEnd && releasedAfterStart && lastDownAt >= from - 30_000L
    }
}
