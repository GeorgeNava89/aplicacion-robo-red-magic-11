package com.george.antirrobo.unlock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GestureLogicTest {

    private val w = 1216f
    private val h = 2688f

    private fun taps(d: TapSequenceDetector, pts: List<Pair<Float, Float>>, gap: Long = 500L): TapSequenceDetector.Result {
        var t = 1_000L
        var last: TapSequenceDetector.Result = TapSequenceDetector.Result.Ignored("")
        for ((x, y) in pts) {
            last = d.onTap(x, y, w, h, t)
            t += gap
        }
        return last
    }

    private val seq = listOf(
        50f to 80f, w - 60f to 90f, w - 40f to h - 70f, 30f to h - 50f, w / 2 to h / 2,
    )

    @Test fun fiveTapsComplete() {
        assertEquals(TapSequenceDetector.Result.Completed, taps(TapSequenceDetector(), seq))
    }

    @Test fun tapsWithin25PercentCornerCount() {
        // Toques bastante adentro de la esquina (20 % de ancho/alto) siguen contando.
        val loose = listOf(
            w * 0.2f to h * 0.2f, w * 0.8f to h * 0.2f, w * 0.8f to h * 0.8f,
            w * 0.2f to h * 0.8f, w / 2 + 100f to h / 2 - 100f,
        )
        assertEquals(TapSequenceDetector.Result.Completed, taps(TapSequenceDetector(), loose))
    }

    @Test fun gapOver3sResets() {
        val r = taps(TapSequenceDetector(), seq, gap = 3_500L)
        assertTrue(r !is TapSequenceDetector.Result.Completed)
    }

    @Test fun gapUnder3sStillCompletes() {
        assertEquals(TapSequenceDetector.Result.Completed, taps(TapSequenceDetector(), seq, gap = 2_800L))
    }

    @Test fun strayTapDoesNotResetProgress() {
        val withStray = seq.take(2) + listOf(w / 2 to h * 0.3f) + seq.drop(2)
        assertEquals(TapSequenceDetector.Result.Completed, taps(TapSequenceDetector(), withStray))
    }

    @Test fun wrongOrderDoesNotComplete() {
        val wrong = listOf(seq[0], seq[2], seq[1], seq[3], seq[4])
        assertTrue(taps(TapSequenceDetector(), wrong) !is TapSequenceDetector.Result.Completed)
    }

    @Test fun swipeUpFromLowerLeftOk() {
        val s = SwipeUnlockDetector()
        s.onDown(200f, h * 0.85f, w, h, 0L)
        s.onMove(220f, h * 0.6f)
        assertEquals(SwipeUnlockDetector.Outcome.SWIPE_OK, s.onEnd(230f, h * 0.5f, h, 600L))
    }

    @Test fun swipeEvaluatedOnCancelWithLastPosition() {
        val s = SwipeUnlockDetector()
        s.onDown(200f, h * 0.9f, w, h, 0L)
        s.onMove(210f, h * 0.5f)
        assertEquals(SwipeUnlockDetector.Outcome.SWIPE_OK, s.onEnd(null, null, h, 500L))
    }

    @Test fun swipeTooShortRejected() {
        val s = SwipeUnlockDetector()
        s.onDown(200f, h * 0.8f, w, h, 0L)
        assertEquals(SwipeUnlockDetector.Outcome.TOO_SHORT, s.onEnd(200f, h * 0.7f, h, 300L))
    }

    @Test fun swipeFromRightRejected() {
        val s = SwipeUnlockDetector()
        s.onDown(w * 0.8f, h * 0.9f, w, h, 0L)
        assertEquals(SwipeUnlockDetector.Outcome.NOT_IN_ZONE, s.onEnd(w * 0.8f, h * 0.3f, h, 300L))
    }

    @Test fun volumeHeldDuringSwipe() {
        val v = VolumeHoldTracker()
        v.onDown(100L)
        assertTrue(v.activeDuring(200L, 800L))
    }

    @Test fun volumeReleasedSlightlyBeforeFingerUpStillCounts() {
        val v = VolumeHoldTracker()
        v.onDown(100L)
        v.onUp(600L)
        assertTrue(v.activeDuring(200L, 1_000L))
    }

    @Test fun volumePressedJustAfterSwipeWithinGraceCounts() {
        val v = VolumeHoldTracker()
        v.onDown(1_500L)
        v.onUp(1_700L)
        assertTrue(v.activeDuring(200L, 1_000L))
    }

    @Test fun volumeLongBeforeSwipeDoesNotCount() {
        val v = VolumeHoldTracker()
        v.onDown(100L)
        v.onUp(200L)
        assertFalse(v.activeDuring(5_000L, 5_800L))
    }

    @Test fun noVolumeNoCombo() {
        assertFalse(VolumeHoldTracker().activeDuring(0L, 1_000L))
    }

    @Test fun zoneGeometryMatchesHitTest() {
        val d = TapSequenceDetector()
        for (i in 0 until 4) {
            val g = d.zoneGeometry(i, w, h)
            assertEquals(i, d.zoneOf((g[0] + g[2]) / 2, (g[1] + g[3]) / 2, w, h))
        }
        val c = d.zoneGeometry(4, w, h)
        assertEquals(4, d.zoneOf(c[0] + c[2] * 0.9f, c[1], w, h))
    }

    @Test fun currentStepResetsAfterGap() {
        val d = TapSequenceDetector()
        d.onTap(10f, 10f, w, h, 0L)
        assertEquals(1, d.currentStep(1_000L))
        assertEquals(0, d.currentStep(4_000L))
    }
}
