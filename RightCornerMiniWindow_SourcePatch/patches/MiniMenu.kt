package com.lsp.hypersidebar.ui.fan

import android.content.Context
import android.content.SharedPreferences
import com.lsp.hypersidebar.BuildConfig
import com.lsp.hypersidebar.prefs.PrefKeys
import com.lsp.hypersidebar.util.AppMetaCache
import org.json.JSONArray

/** Menu data only. Input, geometry, windows and launching belong to upstream. */
internal object MiniMenu {
    const val FOLDER = "mini:folder"
    const val BACK = "mini:back"
    fun isControl(app: FanAppInfo) = app.packageName.startsWith("mini:")
    fun isMenu(apps: List<FanAppInfo>) = apps.lastOrNull()?.packageName in setOf(FOLDER, BACK)

    private fun page(context: Context, packages: List<String>, favorites: Boolean): List<FanAppInfo> {
        val apps = packages.distinct().filter { pkg ->
            pkg != BuildConfig.APPLICATION_ID &&
                runCatching { context.packageManager.getLaunchIntentForPackage(pkg) != null }.getOrDefault(false)
        }.take(5).map { FanAppInfo(it, AppMetaCache.label(context, it)) }
        return apps + List(5 - apps.size) { FanAppInfo("mini:empty:$it", "未设置") } +
            FanAppInfo(if (favorites) BACK else FOLDER, if (favorites) "返回推荐" else "收藏应用")
    }

    fun recommendations(context: Context, ranked: List<String>) = page(context, ranked, false)

    fun favorites(context: Context, prefs: SharedPreferences): List<FanAppInfo> {
        val selected = prefs.getStringSet(PrefKeys.CUSTOM_APPS, emptySet()).orEmpty()
        val order = runCatching {
            val a = JSONArray(prefs.getString(PrefKeys.CUSTOM_APPS_ORDER, "[]"))
            List(a.length()) { a.optString(it) }.filter { it in selected }
        }.getOrDefault(emptyList())
        return page(context, order + selected.filter { it !in order }.sorted(), true)
    }
}
