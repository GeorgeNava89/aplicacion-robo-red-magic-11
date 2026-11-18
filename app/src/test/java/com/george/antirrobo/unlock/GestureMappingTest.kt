package com.george.antirrobo.unlock

import com.george.antirrobo.unlock.GestureMapping.Action
import com.george.antirrobo.unlock.GestureMapping.Gesture
import com.george.antirrobo.unlock.GestureMapping.Screen
import org.junit.Assert.assertEquals
import org.junit.Test

class GestureMappingTest {
    @Test fun fiveCornersOnlyUnlock() {
        assertEquals(Action.UNLOCK, GestureMapping.resolve(Screen.FAKE_OFF, Gesture.CORNER_SEQUENCE))
        // Ya NO abre el menú real.
        assertEquals(Action.NOTHING, GestureMapping.resolve(Screen.DECOY_MENU, Gesture.CORNER_SEQUENCE))
    }

    @Test fun volumeSwipeOpensRealDialog() {
        assertEquals(Action.REAL_POWER_DIALOG, GestureMapping.resolve(Screen.DECOY_MENU, Gesture.VOLUME_SWIPE_UP))
        assertEquals(Action.REAL_POWER_DIALOG, GestureMapping.resolve(Screen.FAKE_OFF, Gesture.VOLUME_SWIPE_UP))
    }

    @Test fun swipeWithoutVolumeDoesNothing() {
        assertEquals(Action.NOTHING, GestureMapping.resolve(Screen.FAKE_OFF, Gesture.SWIPE_UP_NO_VOLUME))
        assertEquals(Action.NOTHING, GestureMapping.resolve(Screen.DECOY_MENU, Gesture.SWIPE_UP_NO_VOLUME))
    }

    @Test fun decoyButtonsGoToFakeOffNotRealDialog() {
        assertEquals(Action.SHUTDOWN_ANIM_THEN_FAKE_OFF, GestureMapping.resolve(Screen.DECOY_MENU, Gesture.TAP_POWER_OFF))
        assertEquals(Action.RESTART_ANIM_THEN_FAKE_OFF, GestureMapping.resolve(Screen.DECOY_MENU, Gesture.TAP_RESTART))
        assertEquals(Action.DISMISS_MENU, GestureMapping.resolve(Screen.DECOY_MENU, Gesture.TAP_OUTSIDE))
    }
}
