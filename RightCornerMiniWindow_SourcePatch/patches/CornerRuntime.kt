package com.lsp.hypersidebar.hook

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Resources
import android.util.DisplayMetrics
import android.view.WindowManager
import com.lsp.hypersidebar.prefs.LayoutDefaults
import com.lsp.hypersidebar.prefs.PrefKeys

/** Both input owners and the drawing window use the same physical display geometry. */
object CornerRuntime {
    @Suppress("DEPRECATION")
    fun metrics(context: Context?): DisplayMetrics = DisplayMetrics().also { dm ->
        dm.setTo(context?.resources?.displayMetrics ?: Resources.getSystem().displayMetrics)
        if (context != null) runCatching {
            context.getSystemService(android.hardware.display.DisplayManager::class.java)
                ?.getDisplay(android.view.Display.DEFAULT_DISPLAY)?.getRealMetrics(dm)
        }
    }

    fun inwardDp(prefs: SharedPreferences?): Float = runCatching {
        (prefs?.getInt(PrefKeys.CORNER_SENSITIVITY_DP, LayoutDefaults.CORNER_SENSITIVITY_DP)
            ?: LayoutDefaults.CORNER_SENSITIVITY_DP).coerceIn(
                LayoutDefaults.CORNER_SENSITIVITY_MIN_DP, LayoutDefaults.CORNER_SENSITIVITY_MAX_DP
            ).toFloat()
    }.getOrDefault(LayoutDefaults.CORNER_SENSITIVITY_DP.toFloat())
}
