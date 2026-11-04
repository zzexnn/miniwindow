package com.lsp.hypersidebar.hook

import org.junit.Assert.*
import org.junit.Test

class CornerTabletTriggerTest {
    private fun start(t: CornerTrigger, w: Float, h: Float, allowLandscape: Boolean = true): Boolean =
        t.onDown(w - 4f, h - 4f, w, h, 75f, w * 0.12f, 36f, 36f,
            15f, 75f, 3f, 1, allowLandscape)

    @Test fun tabletPortraitAndLandscapeTriggerFromRightCorner() {
        for ((w, h) in listOf(2136f to 3200f, 3200f to 2136f)) {
            val t = CornerTrigger()
            assertTrue(start(t, w, h))
            assertEquals(CornerTrigger.Action.SHOW, t.onMove(w - 70f, h - 70f))
        }
    }
    @Test fun legacyLandscapeBehaviorRemainsOptIn() {
        assertFalse(start(CornerTrigger(), 3200f, 2136f, false))
    }
    @Test fun crossingMidpointAfterRecognitionDoesNotInvalidateStream() {
        val t = CornerTrigger()
        start(t, 1080f, 2400f)
        assertEquals(CornerTrigger.Action.SHOW, t.onMove(1000f, 2320f))
        assertEquals(CornerTrigger.Action.CONSUME, t.onMove(300f, 1700f))
        assertTrue(t.claimed)
    }
    @Test fun verticalHomeAndMultitouchDoNotOpenMenu() {
        val t = CornerTrigger()
        start(t, 2136f, 3200f)
        assertNotEquals(CornerTrigger.Action.SHOW, t.onMove(2130f, 2950f))
        t.reset()
        start(t, 2136f, 3200f)
        t.onPointerDown()
        assertNotEquals(CornerTrigger.Action.SHOW, t.onMove(2000f, 3050f))
    }
}
