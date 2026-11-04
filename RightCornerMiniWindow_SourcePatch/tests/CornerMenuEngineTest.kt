package com.lsp.hypersidebar.hook

import org.junit.Assert.*
import org.junit.Test

class CornerMenuEngineTest {
    private fun engine(w: Float = 2136f, h: Float = 3200f) =
        CornerMenuEngine(450f, 70f, w, h)
    private fun move(e: CornerMenuEngine, index: Int) = e.center(index).let { e.move(it.first, it.second) }
    private fun release(e: CornerMenuEngine, index: Int) = e.center(index).let { e.release(it.first, it.second) }

    @Test fun eachSuggestionAndFavoriteCanBeSelected() {
        for (index in 0..4) {
            val e = engine()
            move(e, index)
            assertEquals(CornerMenuEngine.Chosen(CornerMenuEngine.Page.SUGGESTIONS, index), release(e, index))
            move(e, 5)
            assertEquals(CornerMenuEngine.Page.FAVORITES, e.page)
            move(e, index)
            assertEquals(CornerMenuEngine.Chosen(CornerMenuEngine.Page.FAVORITES, index), release(e, index))
        }
    }
    @Test fun folderDoesNotBounceUntilFingerLeavesAndReenters() {
        val e = engine()
        move(e, 5)
        repeat(10) { move(e, 5) }
        assertEquals(CornerMenuEngine.Page.FAVORITES, e.page)
        assertEquals(1, e.pageVersion)
        assertNull(release(e, 5))
        move(e, 0)
        move(e, 5)
        assertEquals(CornerMenuEngine.Page.SUGGESTIONS, e.page)
        assertEquals(2, e.pageVersion)
    }
    @Test fun leavingArcCancelsStaleSelection() {
        val e = engine()
        move(e, 1)
        assertNull(e.release(2136f, 3200f))
        assertNull(release(e, 4))
        e.move(0f, 0f)
        assertEquals(-1, e.highlighted)
    }
    @Test fun landscapeUsesSameSixPositionsWithoutClipping() {
        val e = engine(3200f, 2136f)
        for (i in 0..5) {
            val p = e.center(i)
            assertTrue(p.first in 0f..3200f && p.second in 0f..2136f)
        }
    }
}
