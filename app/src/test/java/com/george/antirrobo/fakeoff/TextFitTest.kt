package com.george.antirrobo.fakeoff

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextFitTest {
    @Test fun keepsSizeWhenItFits() {
        assertEquals(40f, TextFit.fitSize(40f, 500f, 800f), 0.001f)
    }

    @Test fun shrinksToFitAvailableWidth() {
        val s = TextFit.fitSize(40f, 1000f, 700f)
        // El ancho escala linealmente con el tamaño: 1000 * s/40 debe caber en 700.
        assertTrue(1000f * s / 40f <= 700f)
    }

    @Test fun shutdownDurationIsDoubled() {
        // v0.1.3: 2 200 + 600 = 2 800 ms. v0.1.4: el doble.
        assertEquals(2 * (2_200L + 600L), ShutdownAnimation.SHUTDOWN_TOTAL_MS)
        assertEquals(2 * 400L, ShutdownAnimation.SHUTDOWN_FADE_IN_MS)
    }
}
