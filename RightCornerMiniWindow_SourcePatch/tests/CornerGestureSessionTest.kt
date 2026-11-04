package com.lsp.hypersidebar.hook

import org.junit.Assert.*
import org.junit.Test

class CornerGestureSessionTest {
    @Test fun backendTrustsOwnershipWithoutSecondAngleGate() {
        val s=CornerGestureSession(); s.begin(100)
        assertFalse(s.shouldShow(100,false))
        assertTrue(s.shouldShow(100,true))
        assertFalse(s.shouldShow(100,true))
    }
    @Test fun lateOldEventsCannotAffectNewGesture() {
        val s=CornerGestureSession(); s.begin(100); s.begin(200)
        assertFalse(s.isCurrent(100)); assertFalse(s.shouldShow(100,true))
        s.finish(100)
        assertTrue(s.shouldShow(200,true))
    }
    @Test fun multitouchCancellationCannotReopenUntilNewDown() {
        val s=CornerGestureSession(); s.begin(100); s.cancel(100)
        assertFalse(s.shouldShow(100,true)); assertFalse(s.isCurrent(100))
        s.begin(200); assertTrue(s.shouldShow(200,true))
    }
    @Test fun upAndCancelLeaveNoLaunchableSession() {
        val s=CornerGestureSession(); s.begin(100); s.finish(100)
        assertFalse(s.isCurrent(100)); assertFalse(s.shouldShow(100,true))
        assertFalse(s.isCurrent(0))
    }
}
