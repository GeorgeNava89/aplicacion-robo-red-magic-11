package com.george.antirrobo.unlock

/**
 * v0.1.6 — Mapa de gestos (lógica pura, con pruebas unitarias).
 *
 * Con el modo ARMADO:
 *  - Botón de encendido (menú real detectado) → menú FALSO.
 *  - En el menú falso: Volumen (+ o −) + deslizar hacia arriba → menú REAL del sistema.
 *  - En el menú falso: Apagar / Reiniciar → animación → apagado falso.
 *  - En el apagado falso: 5 toques en las esquinas → DESBLOQUEAR (salir).
 *  - En el apagado falso: Volumen + deslizar hacia arriba → menú REAL del sistema.
 */
object GestureMapping {

    enum class Screen { DECOY_MENU, FAKE_OFF }

    enum class Gesture { CORNER_SEQUENCE, VOLUME_SWIPE_UP, SWIPE_UP_NO_VOLUME, TAP_POWER_OFF, TAP_RESTART, TAP_OUTSIDE }

    enum class Action { UNLOCK, REAL_POWER_DIALOG, SHUTDOWN_ANIM_THEN_FAKE_OFF, RESTART_ANIM_THEN_FAKE_OFF, DISMISS_MENU, NOTHING }

    fun resolve(screen: Screen, gesture: Gesture): Action = when (screen) {
        Screen.DECOY_MENU -> when (gesture) {
            Gesture.VOLUME_SWIPE_UP -> Action.REAL_POWER_DIALOG
            Gesture.TAP_POWER_OFF -> Action.SHUTDOWN_ANIM_THEN_FAKE_OFF
            Gesture.TAP_RESTART -> Action.RESTART_ANIM_THEN_FAKE_OFF
            Gesture.TAP_OUTSIDE -> Action.DISMISS_MENU
            Gesture.CORNER_SEQUENCE, Gesture.SWIPE_UP_NO_VOLUME -> Action.NOTHING
        }
        Screen.FAKE_OFF -> when (gesture) {
            Gesture.CORNER_SEQUENCE -> Action.UNLOCK
            Gesture.VOLUME_SWIPE_UP -> Action.REAL_POWER_DIALOG
            else -> Action.NOTHING
        }
    }
}
