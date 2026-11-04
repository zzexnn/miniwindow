package com.lsp.hypersidebar.hook

import kotlin.math.min

/** One source for drawing size, arc hit targets and responsive tablet/phone geometry. */
object CornerOrbitGeometry {
    const val RADIUS_DP = 292f
    const val ICON_RADIUS_DP = 24f
    fun radius(w: Float, h: Float, density: Float): Float =
        min(RADIUS_DP*density, min(w*0.74f, h*0.78f))
    fun iconRadius(radius: Float, density: Float): Float = min(ICON_RADIUS_DP*density, radius*0.09f)
    fun hitRadius(radius: Float, density: Float): Float = min(36f*density, radius*0.115f)
}
