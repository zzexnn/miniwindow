package com.lsp.hypersidebar.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Bundle
import com.lsp.hypersidebar.BuildConfig
import com.lsp.hypersidebar.prefs.PrefKeys

/** Read-only injection/version evidence. Does not create windows or modify input state. */
object MiniDiagnostics {
    private var owner: Context? = null
    private var receiver: BroadcastReceiver? = null

    @Synchronized fun register(context: Context, prefs: SharedPreferences) {
        if (receiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                if (!isOrderedBroadcast) return
                // This public response contains no token, private configuration or launch action.
                resultCode = PrefKeys.PROBE_CODE_OK
                setResultExtras(Bundle().apply {
                    putString("version", BuildConfig.VERSION_NAME)
                    putInt("versionCode", BuildConfig.VERSION_CODE)
                    putString("build", BuildConfig.CORNER_BUILD_ID)
                    putString("detail", "进程 ${HLog.proc()} 已加载当前模块；上游手势链路。启用=" +
                        prefs.getBoolean(PrefKeys.ENABLED, true))
                })
            }
        }
        runCatching {
            context.registerReceiver(r, IntentFilter(PrefKeys.CORNER_DIAG_ACTION), Context.RECEIVER_EXPORTED)
            owner = context
            receiver = r
            HLog.i("MiniDiagnostics", "INJECTED ${BuildConfig.VERSION_NAME}/${BuildConfig.CORNER_BUILD_ID} proc=${HLog.proc()}")
        }.onFailure { HLog.w("MiniDiagnostics", "register failed: ${it.message}") }
    }

    @Synchronized fun release() {
        receiver?.let { r -> runCatching { owner?.unregisterReceiver(r) } }
        receiver = null
        owner = null
    }
}
