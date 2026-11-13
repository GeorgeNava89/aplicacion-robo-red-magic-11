package com.george.antirrobo.unlock

import android.view.KeyEvent
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Combinación de desbloqueo: tecla de volumen mantenida (onKeyEvent del
 * AccessibilityService, que SÍ recibe volumen) + gesto en la pantalla
 * (overlay) al mismo tiempo.
 *
 * La ventana de "mantenida" considera presionado desde ACTION_DOWN hasta
 * ACTION_UP, más un margen breve (80 ms) por si el UP llega un instante
 * antes de terminar el swipe.
 */
object UnlockComboDetector {

    private val volumeUpHeld = AtomicBoolean(false)
    private val volumeDownHeld = AtomicBoolean(false)
    private val lastVolumeDownAt = AtomicLong(0L)
    private val lastVolumeUpAt = AtomicLong(0L)

    const val HOLD_GRACE_MS = 120L

    fun onVolumeKey(event: KeyEvent) {
        val held = event.action == KeyEvent.ACTION_DOWN || event.action == KeyEvent.ACTION_MULTIPLE
        val released = event.action == KeyEvent.ACTION_UP
        when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> {
                if (held) {
                    volumeUpHeld.set(true)
                    lastVolumeUpAt.set(System.currentTimeMillis())
                } else if (released) {
                    volumeUpHeld.set(false)
                    lastVolumeUpAt.set(System.currentTimeMillis())
                }
            }
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                if (held) {
                    volumeDownHeld.set(true)
                    lastVolumeDownAt.set(System.currentTimeMillis())
                } else if (released) {
                    volumeDownHeld.set(false)
                    lastVolumeDownAt.set(System.currentTimeMillis())
                }
            }
        }
    }

    /** Volumen arriba o abajo cuenta, siempre que esté (o acaba de estar) presionado. */
    fun isVolumeHeld(now: Long = System.currentTimeMillis()): Boolean {
        if (volumeUpHeld.get() || volumeDownHeld.get()) return true
        val graceUp = now - lastVolumeUpAt.get() <= HOLD_GRACE_MS
        val graceDown = now - lastVolumeDownAt.get() <= HOLD_GRACE_MS
        return graceUp || graceDown
    }

    fun reset() {
        volumeUpHeld.set(false)
        volumeDownHeld.set(false)
        lastVolumeUpAt.set(0L)
        lastVolumeDownAt.set(0L)
    }
}
