package com.lsp.hypersidebar.ui.settings

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lsp.hypersidebar.BuildConfig
import com.lsp.hypersidebar.prefs.HostPackages
import com.lsp.hypersidebar.prefs.LayoutDefaults
import com.lsp.hypersidebar.prefs.PrefKeys
import com.lsp.hypersidebar.prefs.savePref
import com.lsp.hypersidebar.util.ConfigSync
import com.lsp.hypersidebar.util.RelayToken
import com.lsp.hypersidebar.util.RemotePrefsBridge
import com.lsp.hypersidebar.util.SelfCheck
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** One navigation owner, with safe drawing bounds for edge-to-edge Android 16/17. */
@Composable
internal fun CornerMiniSettingsScreen(prefs: SharedPreferences) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var page by rememberSaveable { mutableStateOf("main") }
    var enabled by remember(prefs) { mutableStateOf(prefs.getBoolean(PrefKeys.ENABLED, true)) }
    var haptic by remember(prefs) { mutableStateOf(prefs.getBoolean(PrefKeys.CORNER_HAPTIC, true)) }
    var sensitivity by remember(prefs) {
        mutableStateOf(prefs.getInt(PrefKeys.CORNER_SENSITIVITY_DP, LayoutDefaults.CORNER_SENSITIVITY_DP))
    }
    var sysStatus by remember { mutableStateOf("尚未检测") }
    var uiStatus by remember { mutableStateOf("尚未检测") }
    var busy by remember { mutableStateOf(false) }
    var restartResult by remember { mutableStateOf("") }
    var restartBusy by remember { mutableStateOf(false) }
    var exportResult by remember { mutableStateOf("") }
    val frameworkConnected = RemotePrefsBridge.prefs != null && RemotePrefsBridge.service != null

    // CORNER_SWIPE_ENABLED was a hidden, independent gate in V0.1. This product has one enable switch.
    LaunchedEffect(prefs, frameworkConnected) {
        if (frameworkConnected) {
            if (!prefs.getBoolean(PrefKeys.CORNER_SWIPE_ENABLED, true)) {
                prefs.savePref(PrefKeys.CORNER_SWIPE_ENABLED, true)
            }
            ConfigSync.notifyConfigChanged("corner settings opened")
            sysStatus = probeCornerHost(context, prefs, HostPackages.SYSTEM_UI)
            uiStatus = probeCornerHost(context, prefs, HostPackages.UI_HOST)
        }
    }
    BackHandler(enabled = page != "main") { page = if (page == "logs") "diag" else "main" }

    Column(Modifier.fillMaxSize().background(Color(0xFFF6F7FA)).safeDrawingPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            if (page != "main") {
                Box(Modifier.sizeIn(minWidth = 64.dp, minHeight = 48.dp)
                    .clickable { page = if (page == "logs") "diag" else "main" }, contentAlignment = Alignment.Center) {
                    TextLine("返回", 16)
                }
            }
            TextLine(when (page) {
                "apps" -> "收藏应用"
                "diag" -> "诊断信息"
                "logs" -> "运行日志"
                else -> "右下角小窗"
            }, 23, true)
        }
        when (page) {
            "apps" -> Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                TextLine("勾选应用后，可拖动已选应用排序。扇形显示前 5 个。", 13)
                Spacer(Modifier.height(8.dp))
                AppSelectionPage(prefs, PrefKeys.CUSTOM_APPS, Modifier.fillMaxSize())
            }
            "logs" -> LogPage(prefs = prefs, modifier = Modifier.weight(1f))
            else -> Column(Modifier.weight(1f).verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (page == "diag") {
                    TextLine("当前版本 ${BuildConfig.VERSION_NAME} / ${BuildConfig.VERSION_CODE} / ${BuildConfig.CORNER_BUILD_ID.take(7)}", 13)
                    ItemRow("LSPosed 框架", if (frameworkConnected) "已连接" else "未连接：确认模块已启用，API ≥ 102，安装更新后重启设备") { }
                    ItemRow("输入端 · SystemUI", sysStatus) { }
                    ItemRow("执行端 · 安全中心", uiStatus) { }
                    ItemRow("重新检测并同步配置", if (busy) "检测中…" else "同步配置并读取宿主当前加载版本") {
                        if (!busy) scope.launch {
                            busy = true
                            try {
                                ConfigSync.notifyConfigChanged("manual corner diagnosis")
                                sysStatus = probeCornerHost(context, prefs, HostPackages.SYSTEM_UI)
                                uiStatus = probeCornerHost(context, prefs, HostPackages.UI_HOST)
                            } finally { busy = false }
                        }
                    }
                    ItemRow("导出自检报告", if (busy) "处理中…" else "包含三宿主日志、配置状态和 Native 检测；保存至下载目录") {
                        if (!busy) scope.launch {
                            busy = true
                            try {
                                exportResult = runCatching {
                                    val body = "右下角小窗诊断\nSystemUI: $sysStatus\n安全中心: $uiStatus\n\n" +
                                        SelfCheck.generate(context, RemotePrefsBridge.service, prefs)
                                    SelfCheck.export(context, body)
                                }.getOrElse { "导出失败：${it.message}" }
                            } finally { busy = false }
                        }
                    }
                    if (exportResult.isNotEmpty()) TextLine(exportResult, 13)
                    ItemRow("查看运行日志", "查看输入、转发、菜单窗口和小窗启动记录") { page = "logs" }
                    TextLine("勾选作用域不等于注入成功。更新 APK 后若无当前版本响应，请在 LSPosed 关闭再启用模块并重启设备，再打开本应用重新检测。不要只重启桌面。", 13)
                } else {
                    ItemRow("LSPosed 状态", when {
                        !frameworkConnected -> "框架未连接；点此查看排查方法"
                        sysStatus.startsWith("当前版本已加载") && uiStatus.startsWith("当前版本已加载") -> "当前版本已加载：SystemUI + 安全中心；点此查看详情"
                        else -> "框架已连接；宿主尚未确认，点此查看检测结果"
                    }) { page = "diag" }
                    ItemRow("启用右下角手势", if (enabled) "已开启" else "已关闭") {
                        enabled = !enabled
                        prefs.savePref(PrefKeys.CORNER_SWIPE_ENABLED, true)
                        prefs.savePref(PrefKeys.ENABLED, enabled)
                    }
                    ItemRow("收藏应用", "选择、拖动排序，最多显示前 5 个") { page = "apps" }
                    ItemRow("选择振动", if (haptic) "已开启" else "已关闭") {
                        haptic = !haptic
                        prefs.savePref(PrefKeys.CORNER_HAPTIC, haptic)
                    }
                    ItemRow("手势灵敏度", "${when { sensitivity <= 8 -> "高"; sensitivity >= 18 -> "稳健"; else -> "标准" }} · 点击切换") {
                        sensitivity = when (sensitivity) { 8 -> 12; 12 -> 18; else -> 8 }
                        prefs.savePref(PrefKeys.CORNER_SENSITIVITY_DP, sensitivity)
                    }
                    ItemRow("重启系统界面", if (restartBusy) "正在重启并检查状态…" else "点击后会短暂黑屏，系统会自动恢复；需要 Root 授权") {
                        if (!restartBusy && !busy) scope.launch {
                            restartBusy = true
                            restartResult = "正在请求 Root 授权…"
                            try {
                                val result = withContext(Dispatchers.IO) { HostRestarter.restartSystemUi() }
                                restartResult = if (result.allOk) "已发送重启请求，正在等待系统界面恢复" else "重启失败：${result.failed.joinToString()}"
                                if (result.allOk) {
                                    delay(6_000L)
                                    ConfigSync.notifyConfigChanged("SystemUI restarted by user")
                                    sysStatus = probeCornerHost(context, prefs, HostPackages.SYSTEM_UI)
                                    uiStatus = probeCornerHost(context, prefs, HostPackages.UI_HOST)
                                    restartResult = if (sysStatus.startsWith("当前版本已加载")) "系统界面已恢复，当前版本已加载" else "已发送重启请求；请到诊断信息检查恢复状态"
                                }
                            } finally { restartBusy = false }
                        }
                    }
                    if (restartResult.isNotEmpty()) TextLine(restartResult, 13)
                    ItemRow("诊断信息", "宿主版本、日志、自检报告") { page = "diag" }
                    Spacer(Modifier.height(4.dp))
                    TextLine("从右下角向左上斜滑，保持按住，滑到应用后松手打开原生小窗。滑到文件夹原位切换收藏，滑到返回可切回推荐。支持横屏和竖屏。", 14)
                    TextLine("${BuildConfig.VERSION_NAME} · 测试版；设备兼容性仍需真机验证。", 12)
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

private suspend fun probeCornerHost(context: Context, prefs: SharedPreferences, pkg: String,
                                    unused: Boolean = false): String =
    withTimeoutOrNull(3_000L) {
        suspendCancellableCoroutine { continuation ->
            val intent = Intent(PrefKeys.CORNER_DIAG_ACTION).setPackage(pkg)
            RelayToken.attach(intent, RelayToken.read(prefs))
            runCatching {
                context.sendOrderedBroadcast(intent, null, object : BroadcastReceiver() {
                    override fun onReceive(c: Context?, i: Intent?) {
                        if (!continuation.isActive) return
                        val e = getResultExtras(false)
                        val message = when {
                            resultCode == PrefKeys.PROBE_CODE_TOKEN_MISMATCH -> resultData ?: "配置未同步"
                            resultCode != PrefKeys.PROBE_CODE_OK -> resultData ?: "无响应：尚未注入、宿主未启动或仍在运行旧版 APK"
                            e?.getInt("versionCode") != BuildConfig.VERSION_CODE || e.getString("version") != BuildConfig.VERSION_NAME || e.getString("build") != BuildConfig.CORNER_BUILD_ID -> "宿主仍是旧版 APK，请重启设备"
                            else -> "当前版本已加载 ${e.getString("version")}\n" +
                                e.getString("detail").orEmpty() +
                                ""
                        }
                        continuation.resume(message)
                    }
                }, Handler(Looper.getMainLooper()), 0, null, null)
            }.onFailure { if (continuation.isActive) continuation.resume("检测失败：${it.message}") }
        }
    } ?: "检测超时：未收到宿主响应"

@Composable
private fun ItemRow(label: String, value: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(14.dp))
        .clickable(onClick = onClick).padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        TextLine(label, 15, true)
        TextLine(value, 13)
    }
}

@Composable
private fun TextLine(s: String, size: Int, bold: Boolean = false) {
    BasicText(s, style = TextStyle(fontSize = size.sp, color = Color(0xFF21242A),
        fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal))
}
