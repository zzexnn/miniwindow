package com.lsp.hypersidebar.util

import org.junit.Assert.*
import org.junit.Test

class NativeConfigValuesTest {
    @Test fun cornerAndBandUpdatesCoexistRegardlessOfOrder() {
        val corner = mapOf("corner.hot_pct" to "3.1", "corner.supp" to "1")
        val band = mapOf("band.w_px" to "0", "band.h_px" to "800")
        for (updates in listOf(listOf(corner, band), listOf(band, corner))) {
            val values = NativeConfigValues()
            updates.forEach { values.merge(it) }
            assertEquals(corner + band, values.merge(emptyMap()))
        }
    }
    @Test fun rotationReplacesOnlyChangedGeometry() {
        val v=NativeConfigValues()
        v.merge(mapOf("corner.hot_pct" to "3", "band.landscape" to "0"))
        assertEquals(mapOf("corner.hot_pct" to "3", "band.landscape" to "1"),
            v.merge(mapOf("band.landscape" to "1")))
    }
    @Test fun snapshotDoesNotChangeAfterLaterUpdates() {
        val v=NativeConfigValues()
        val first=v.merge(mapOf("corner.hot_pct" to "3"))
        v.merge(mapOf("corner.hot_pct" to "4"))
        assertEquals("3", first["corner.hot_pct"])
    }
}
