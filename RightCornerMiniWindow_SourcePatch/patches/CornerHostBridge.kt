package com.lsp.hypersidebar.hook

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Bundle
import com.lsp.hypersidebar.BuildConfig
import com.lsp.hypersidebar.prefs.PrefKeys
import com.lsp.hypersidebar.util.HLog
import com.lsp.hypersidebar.util.RelayToken

/** Readiness is proven by a reply from this APK, never inferred from scope selection. */
object CornerHostBridge {
    private var receiver: BroadcastReceiver? = null
    private var context: Context? = null

    fun register(ctx: Context, prefs: SharedPreferences, status: () -> Bundle,
                 test: (() -> String)? = null, recover: (() -> String)? = null) {
        if (receiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                if (!isOrderedBroadcast) return
                val expected = RelayToken.read(prefs)
                val supplied = intent.getStringExtra(PrefKeys.RELAY_LAUNCH_EXTRA_TOKEN)
                // Strict for a window test; read-only readiness probes also require the token.
                if (expected.isNullOrEmpty() || supplied != expected) {
                    resultCode = PrefKeys.PROBE_CODE_TOKEN_MISMATCH
                    resultData = "配置令牌尚未同步；打开模块设置后重新检测"
                    return
                }
                runCatching {
                    val recovery = if (intent.getBooleanExtra(PrefKeys.CORNER_DIAG_RECOVER, false)) recover?.invoke() else null
                    val out = status()
                    recovery?.let { out.putString("recovery", it) }
                    out.putInt("versionCode", BuildConfig.VERSION_CODE)
                    out.putString("version", BuildConfig.VERSION_NAME)
                    out.putString("process", android.app.Application.getProcessName())
                    out.putBoolean("enabled", prefs.getBoolean(PrefKeys.ENABLED, true))
                    out.putBoolean("corner", prefs.getBoolean(PrefKeys.CORNER_SWIPE_ENABLED, true))
                    if (intent.getBooleanExtra(PrefKeys.CORNER_DIAG_TEST, false)) {
                        out.putString("test", test?.invoke() ?: "该宿主不负责绘制菜单")
                    }
                    setResultExtras(out)
                    resultCode = PrefKeys.PROBE_CODE_OK
                }.onFailure {
                    resultCode = PrefKeys.PROBE_CODE_DEGRADED
                    resultData = "宿主检测失败：${it.javaClass.simpleName}: ${it.message}"
                    HLog.w("CornerHost", resultData ?: "probe failed")
                }
            }
        }
        runCatching {
            ctx.registerReceiver(r, IntentFilter(PrefKeys.CORNER_DIAG_ACTION), Context.RECEIVER_EXPORTED)
            context = ctx
            receiver = r
            HLog.i("CornerHost", "RCM_020 host ready ${BuildConfig.VERSION_CODE} ${android.app.Application.getProcessName()}")
        }.onFailure { HLog.w("CornerHost", "register failed: ${it.message}") }
    }

    fun releaseForReload() {
        val r = receiver
        val c = context
        if (r != null && c != null) runCatching { c.unregisterReceiver(r) }
        receiver = null
        context = null
    }
}
