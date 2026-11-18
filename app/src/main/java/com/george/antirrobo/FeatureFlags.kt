package com.george.antirrobo

/**
 * v0.1.6 — Interruptores de funciones a nivel de compilación.
 * Apagados a petición del dueño; el código queda inerte.
 */
object FeatureFlags {
    /** Control del ventilador (claves/sysfs conjeturadas). APAGADO desde v0.1.6. */
    const val FAN_CONTROL = false

    /** "Franja de luz" (v0.1.5). APAGADO desde v0.1.6: no corre, sin UI ni notificación. */
    const val LIGHT_STRIP = false
}
