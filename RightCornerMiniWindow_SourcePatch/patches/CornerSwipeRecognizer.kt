package com.lsp.hypersidebar.hook

import kotlin.math.atan2
import kotlin.math.hypot

/** SystemUI is the only direction judge. Never reserve input on DOWN or a tap. */
class CornerSwipeRecognizer {
    enum class Decision { PASS, WAIT, CLAIM, CANCEL }
    data class Profile(val distanceDp: Float, val inwardDp: Float, val upDp: Float)

    companion object {
        const val HOT_WIDTH_DP = 80f
        const val HOT_HEIGHT_DP = 44f
        const val MIN_ANGLE_DEG = 8f
        const val MAX_ANGLE_DEG = 83f
        fun profile(setting: Int): Profile = when {
            setting <= 8 -> Profile(3.5f, 0.6f, 1f)
            setting >= 18 -> Profile(8f, 1.5f, 2f)
            else -> Profile(5f, 0.8f, 1.5f)
        }
        fun inHotspot(x: Float, y: Float, w: Float, h: Float, density: Float): Boolean =
            w > 0 && h > 0 && density > 0 && x.isFinite() && y.isFinite() &&
                x in (w - HOT_WIDTH_DP * density).coerceAtLeast(0f)..w &&
                y in (h - HOT_HEIGHT_DP * density).coerceAtLeast(0f)..h
    }

    var claimed = false
        private set
    var activated = false
        private set
    var lastReject = "IDLE"
        private set
    private var cancelled = false
    private var x0 = 0f
    private var y0 = 0f
    private var density = 1f
    private var p = profile(12)
    private var moves = 0
    private var dxMax = 0f
    private var lastDeg = Float.NaN

    fun reset() {
        claimed = false; activated = false; cancelled = false
        moves = 0; dxMax = 0f; lastDeg = Float.NaN; lastReject = "IDLE"
    }

    fun onDown(x: Float, y: Float, w: Float, h: Float, density: Float, setting: Int): Boolean {
        reset()
        this.density = density
        p = profile(setting)
        x0 = x; y0 = y
        claimed = inHotspot(x, y, w, h, density)
        lastReject = if (claimed) "WAIT_DIRECTION" else "OUTSIDE"
        return claimed
    }

    fun onPointerDown(): Decision {
        cancelled = true
        lastReject = "MULTI"
        return Decision.CANCEL
    }

    fun onMove(x: Float, y: Float): Decision {
        if (!claimed) return Decision.PASS
        if (cancelled) return Decision.CANCEL
        if (!x.isFinite() || !y.isFinite()) return Decision.WAIT
        if (activated) return Decision.CLAIM
        val dx = (x0 - x) / density
        val up = (y0 - y) / density
        moves++
        dxMax = maxOf(dxMax, dx)
        if (hypot(dx, up) < p.distanceDp) {
            lastReject = "SHORT"
            return Decision.WAIT
        }
        lastDeg = Math.toDegrees(atan2(up.toDouble(), dx.toDouble())).toFloat()
        if (dx >= p.inwardDp && up >= p.upDp && lastDeg in MIN_ANGLE_DEG..MAX_ANGLE_DEG) {
            activated = true
            lastReject = "CLAIM"
            return Decision.CLAIM
        }
        // A slightly vertical first sample must not permanently reject the rest of a diagonal.
        // A real vertical Home swipe remains the system's because we never pilfer this branch.
        lastReject = when {
            dx < p.inwardDp -> "INWARD_SHORT"
            up < p.upDp -> "UP_SHORT"
            else -> "ANGLE"
        }
        return Decision.WAIT
    }

    fun diag(): String = "samples=$moves maxInDp=$dxMax angle=$lastDeg reason=$lastReject"
}
