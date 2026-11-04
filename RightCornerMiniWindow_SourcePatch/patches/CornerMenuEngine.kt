package com.lsp.hypersidebar.hook

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Single-stroke corner menu navigation with no Android dependency.
 * The portal (slot 5) switches pages only when re-entered: the finger must
 * leave its hit band before it can be activated again. This prevents flicker.
 */
class CornerMenuEngine(
    private val radius: Float,
    private val hitRadius: Float,
    private val anchorX: Float,
    private val anchorY: Float
) {
    enum class Page { SUGGESTIONS, FAVORITES }
    data class Chosen(val page: Page, val index: Int)

    var page: Page = Page.SUGGESTIONS
        private set
    var highlighted: Int = -1
        private set
    var pageVersion: Int = 0
        private set
    private var portalReady: Boolean = true
    private val angles = floatArrayOf(78f, 65f, 52f, 39f, 26f, 13f)

    /** Screen-space icon center of one of the six slots. */
    fun center(index: Int): Pair<Float, Float> {
        require(index in 0..5)
        val rad = Math.toRadians(angles[index].toDouble())
        return Pair(anchorX - radius * cos(rad).toFloat(), anchorY - radius * sin(rad).toFloat())
    }

    private fun hitIndex(x: Float, y: Float): Int {
        // The touch must be near the visible icon arc; distant movement cancels.
        val distance = hypot(anchorX - x, anchorY - y)
        if (abs(distance - radius) > hitRadius) return -1
        var chosen = -1
        var best = Float.POSITIVE_INFINITY
        for (i in 0..5) {
            val (cx, cy) = center(i)
            val d = hypot(cx - x, cy - y)
            if (d < hitRadius && d < best) {
                best = d
                chosen = i
            }
        }
        return chosen
    }

    /** Moves the selection. Returns true when any visible state changed. */
    fun move(x: Float, y: Float): Boolean {
        val oldPage = page
        val oldHighlight = highlighted
        val hit = hitIndex(x, y)
        if (hit != 5) portalReady = true
        if (hit == 5 && portalReady) {
            page = if (page == Page.SUGGESTIONS) Page.FAVORITES else Page.SUGGESTIONS
            pageVersion++
            highlighted = -1
            portalReady = false
        } else {
            highlighted = if (hit in 0..4) hit else -1
        }
        return page != oldPage || highlighted != oldHighlight
    }

    fun release(x: Float, y: Float): Chosen? {
        // Never launch a stale highlight when UP has crossed into a dead zone.
        val hit = hitIndex(x, y)
        return if (hit in 0..4 && highlighted == hit) Chosen(page, hit) else null
    }
}
