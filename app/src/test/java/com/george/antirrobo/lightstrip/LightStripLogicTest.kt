package com.george.antirrobo.lightstrip

import com.george.antirrobo.lightstrip.LightStripLogic.Action
import com.george.antirrobo.lightstrip.LightStripLogic.Outcome
import com.george.antirrobo.lightstrip.LightStripLogic.State
import org.junit.Assert.assertEquals
import org.junit.Test

class LightStripLogicTest {
    private val L = "Franja de luz"

    @Test fun onlyTurnsOffWhenOn() {
        assertEquals(Action.TURN_OFF, LightStripLogic.decide(State.ON))
        assertEquals(Action.NOTHING, LightStripLogic.decide(State.OFF))
        assertEquals(Action.NOTHING, LightStripLogic.decide(State.UNKNOWN))
    }

    @Test fun verifyNeverRetries() {
        assertEquals(Outcome.TURNED_OFF, LightStripLogic.verify(State.OFF))
        assertEquals(Outcome.STILL_ON_NO_RETRY, LightStripLogic.verify(State.ON))
        assertEquals(Outcome.UNVERIFIED, LightStripLogic.verify(State.UNKNOWN))
        assertEquals(Outcome.NOT_NEEDED_OFF, LightStripLogic.skipped(State.OFF))
        assertEquals(Outcome.SKIPPED_UNKNOWN, LightStripLogic.skipped(State.UNKNOWN))
    }

    @Test fun checkableWins() {
        assertEquals(State.ON, LightStripLogic.parseTileState(true, true, "Desactivado", null, null, L))
        assertEquals(State.OFF, LightStripLogic.parseTileState(true, false, null, null, null, L))
    }

    @Test fun stateDescriptionSpanishAndEnglish() {
        assertEquals(State.ON, LightStripLogic.parseTileState(false, false, "Activado", null, null, L))
        assertEquals(State.OFF, LightStripLogic.parseTileState(false, false, "Desactivado", null, null, L))
        assertEquals(State.ON, LightStripLogic.parseTileState(false, false, "On", null, null, L))
        assertEquals(State.OFF, LightStripLogic.parseTileState(false, false, "Off", null, null, L))
        assertEquals(State.OFF, LightStripLogic.parseTileState(false, false, "Inactive", null, null, L))
    }

    @Test fun contentDescriptionWithLabel() {
        assertEquals(State.OFF, LightStripLogic.parseTileState(false, false, null, "Franja de luz, desactivada", null, L))
        assertEquals(State.ON, LightStripLogic.parseTileState(false, false, null, "Franja de luz, encendida", null, L))
        // Solo la etiqueta: no se sabe → no se toca.
        assertEquals(State.UNKNOWN, LightStripLogic.parseTileState(false, false, null, "Franja de luz", "Franja de luz", L))
        assertEquals(State.UNKNOWN, LightStripLogic.parseTileState(false, false, null, null, null, L))
    }

    @Test fun labelWordsDoNotFakeState() {
        // Una etiqueta que contiene "on" no debe leerse como encendido.
        assertEquals(State.UNKNOWN, LightStripLogic.parseTileState(false, false, null, "Light on strip", null, "Light on strip"))
        assertEquals(State.UNKNOWN, LightStripLogic.parseTileState(false, false, null, "Iconos", null, L))
    }

    @Test fun keyState() {
        assertEquals(State.ON, LightStripLogic.parseKeyState("1", "1", "0"))
        assertEquals(State.OFF, LightStripLogic.parseKeyState("0", "1", "0"))
        assertEquals(State.UNKNOWN, LightStripLogic.parseKeyState("2", "1", "0"))
        assertEquals(State.UNKNOWN, LightStripLogic.parseKeyState(null, "1", "0"))
    }

    @Test fun labelsParsing() {
        assertEquals(listOf("Franja de luz", "Light strip"), LightStripLogic.labels(" Franja de luz , Light strip ,"))
    }
}
