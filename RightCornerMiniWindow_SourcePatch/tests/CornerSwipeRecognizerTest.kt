package com.lsp.hypersidebar.hook

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class CornerSwipeRecognizerTest {
    private fun recognizer(setting: Int = 12, w: Float = 2136f, h: Float = 3200f, density: Float = 2f) =
        CornerSwipeRecognizer().also { assertTrue(it.onDown(w - 4, h - 4, w, h, density, setting)) }

    @Test fun naturalAnglesClaimAtSmallMovementBeforeOldTwelveDpInwardGate() {
        for (angle in listOf(15.0, 30.0, 45.0, 60.0, 75.0, 80.0)) {
            val r=recognizer()
            val dx=6f*cos(Math.toRadians(angle)).toFloat()
            val up=6f*sin(Math.toRadians(angle)).toFloat()
            assertEquals("angle=$angle", CornerSwipeRecognizer.Decision.CLAIM,
                r.onMove(2132f-dx*2,3196f-up*2))
        }
    }
    @Test fun exactlyVerticalHomeNeverClaims() {
        val r=recognizer()
        for (up in listOf(2f,8f,20f,100f,400f)) {
            assertNotEquals(CornerSwipeRecognizer.Decision.CLAIM,r.onMove(2132f,3196f-up*2))
        }
    }
    @Test fun tapsAndJitterRemainUnclaimed() {
        val r=recognizer()
        assertEquals(CornerSwipeRecognizer.Decision.WAIT,r.onMove(2130f,3194f))
        assertFalse(r.activated)
    }
    @Test fun initiallyVerticalSampleCanBecomeDiagonalWithoutPermanentRejection() {
        val r=recognizer()
        assertEquals(CornerSwipeRecognizer.Decision.WAIT,r.onMove(2132f,3184f))
        assertEquals(CornerSwipeRecognizer.Decision.CLAIM,r.onMove(2120f,3176f))
    }
    @Test fun outsideCornerAndMiddleHomeDoNotEngage() {
        for ((x,y) in listOf(1068f to 3198f, 1800f to 3198f, 2132f to 3000f, -1f to 3199f)) {
            assertFalse(CornerSwipeRecognizer().onDown(x,y,2136f,3200f,2f,12))
        }
    }
    @Test fun expandedBandIncludesStartAboveNavigationArea() {
        assertTrue(CornerSwipeRecognizer().onDown(2100f,3130f,2136f,3200f,2f,12))
    }
    @Test fun landscapeAndDifferentDensityUseDpGeometry() {
        val r=recognizer(w=3200f,h=2136f,density=3f)
        assertEquals(CornerSwipeRecognizer.Decision.CLAIM,r.onMove(3184f,2120f))
    }
    @Test fun downRightAndHorizontalMovementDoNotClaim() {
        for ((x,y) in listOf(2140f to 3180f,2110f to 3196f,2110f to 3205f)) {
            assertNotEquals(CornerSwipeRecognizer.Decision.CLAIM,recognizer().onMove(x,y))
        }
    }
    @Test fun multitouchCancelsEvenAfterRecognition() {
        val r=recognizer()
        r.onMove(2120f,3180f)
        r.onPointerDown()
        assertEquals(CornerSwipeRecognizer.Decision.CANCEL,r.onMove(2100f,3160f))
    }
    @Test fun newDownResetsCancelledAndClaimedStream() {
        val r=recognizer()
        r.onPointerDown()
        assertTrue(r.onDown(2132f,3196f,2136f,3200f,2f,12))
        assertEquals(CornerSwipeRecognizer.Decision.CLAIM,r.onMove(2120f,3180f))
    }
    @Test fun sensitivityProfilesRemainOrdered() {
        assertTrue(CornerSwipeRecognizer.profile(8).distanceDp < CornerSwipeRecognizer.profile(12).distanceDp)
        assertTrue(CornerSwipeRecognizer.profile(12).distanceDp < CornerSwipeRecognizer.profile(18).distanceDp)
    }
}
