package com.george.antirrobo.unlock

import android.os.SystemClock
import android.view.KeyEvent
import com.george.antirrobo.util.EventLog

/**
 * Combinación de desbloqueo: VOLUMEN (arriba o abajo) + deslizar hacia arriba
 * en el overlay.
 *
 * v0.1.3: antes se exigía que el volumen siguiera presionado EXACTAMENTE al
 * levantar el dedo (margen de 120 ms). Ahora tecla y toque usan el mismo
 * reloj (eventTime = uptimeMillis) y basta con que el volumen haya estado
 * activo en algún momento del deslizamiento (VolumeHoldTracker).
 */
object UnlockComboDetector {

    private val tracker = VolumeHoldTracker()

    /** v0.1.3: para el indicador de "volumen detectado" de los gestos fantasma. */
    @Volatile var listener: (() -> Unit)? = null

    @Synchronized
    fun onVolumeKey(event: KeyEvent) {
        val t = event.eventTime.takeIf { it > 0 } ?: SystemClock.uptimeMillis()
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0) EventLog.i(TAG, "Volumen DOWN (${KeyEvent.keyCodeToString(event.keyCode)})")
                tracker.onDown(t)
                if (event.repeatCount == 0) listener?.invoke()
            }
            KeyEvent.ACTION_UP -> {
                EventLog.i(TAG, "Volumen UP")
                tracker.onUp(t)
                listener?.invoke()
            }
        }
    }

    @Synchronized
    fun volumeActiveDuring(from: Long, to: Long): Boolean = tracker.activeDuring(from, to)

    @Synchronized
    fun isVolumeHeld(): Boolean = tracker.held

    @Synchronized
    fun reset() = tracker.reset()

    private const val TAG = "Combo"
}
