package com.lsp.hypersidebar.hook

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.hypot

class CornerOrbitGeometryTest {
    @Test fun sixFloatingIconsStayOnScreenInBothOrientations() {
        for ((w,h,d) in listOf(Triple(2136f,3200f,2f),Triple(3200f,2136f,2f),Triple(1080f,2400f,3f),Triple(2400f,1080f,3f))) {
            val r=CornerOrbitGeometry.radius(w,h,d)
            val icon=CornerOrbitGeometry.iconRadius(r,d)
            val e=CornerMenuEngine(r,CornerOrbitGeometry.hitRadius(r,d),w,h)
            for (i in 0..5) {
                val (x,y)=e.center(i)
                assertTrue(x-icon>=0 && x+icon<=w && y-icon>=0 && y+icon<=h)
                if (i>0) {
                    val (px,py)=e.center(i-1)
                    assertTrue("icons overlap",hypot(x-px,y-py)>icon*2)
                }
            }
        }
    }
    @Test fun folderAtLowLeftEndMatchesReferenceArc() {
        val e=CornerMenuEngine(600f,70f,2136f,3200f)
        val first=e.center(0); val folder=e.center(5)
        assertTrue(folder.first<first.first)
        assertTrue(folder.second>first.second)
    }
    @Test fun portalAndFavoritesUseSamePositionsAndDoNotBounce() {
        val e=CornerMenuEngine(600f,70f,2136f,3200f)
        val positions=(0..5).map(e::center)
        e.center(5).let { e.move(it.first,it.second) }
        assertEquals(CornerMenuEngine.Page.FAVORITES,e.page)
        assertEquals(positions,(0..5).map(e::center))
        e.center(5).let { e.move(it.first,it.second) }
        assertEquals(CornerMenuEngine.Page.FAVORITES,e.page)
    }
}
