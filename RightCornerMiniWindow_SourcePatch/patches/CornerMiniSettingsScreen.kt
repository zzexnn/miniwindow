package com.lsp.hypersidebar.ui.settings

import android.content.SharedPreferences
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lsp.hypersidebar.prefs.PrefKeys
import com.lsp.hypersidebar.prefs.savePref

/** Minimal front page; reuses the upstream working app-selection and sorting page. */
@Composable
internal fun CornerMiniSettingsScreen(prefs: SharedPreferences) {
    var appPicker by remember { mutableStateOf(false) }
    var enabled by remember(prefs) { mutableStateOf(prefs.getBoolean(PrefKeys.ENABLED, true)) }
    var haptic by remember(prefs) { mutableStateOf(prefs.getBoolean("corner_haptic", true)) }
    val background = Color(0xFFF6F7FA)
    Column(Modifier.fillMaxSize().background(background).padding(horizontal = 18.dp, vertical = 24.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextLine(if (appPicker) "选择收藏应用" else "右下角小窗", 23.sp, bold = true)
            if (appPicker) Box(Modifier.clickable { appPicker = false }) { TextLine("返回", 16.sp) }
        }
        Spacer(Modifier.height(22.dp))
        if (appPicker) {
            TextLine("可在原有应用选择列表中勾选、拖动排序，扇形最多显示前 5 个。", 13.sp)
            Spacer(Modifier.height(10.dp))
            Box(Modifier.weight(1f)) {
                AppSelectionPage(prefs = prefs, prefsKey = PrefKeys.CUSTOM_APPS, modifier = Modifier.fillMaxSize())
            }
        } else {
            ItemRow("启用右下角手势", if (enabled) "已开启" else "已关闭") {
                enabled = !enabled
                prefs.savePref(PrefKeys.ENABLED, enabled)
            }
            Spacer(Modifier.height(12.dp))
            ItemRow("自定义收藏", "选择并排序，最多 5 个显示在第二页") { appPicker = true }
            Spacer(Modifier.height(12.dp))
            ItemRow("选择振动", if (haptic) "已开启" else "已关闭") {
                haptic = !haptic
                prefs.savePref("corner_haptic", haptic)
            }
            Spacer(Modifier.height(12.dp))
            ItemRow("手势灵敏度", "标准 · 12dp（测试版固定）") { }
            Spacer(Modifier.height(24.dp))
            TextLine("右下角向左上滑动，第一层是系统推荐的 5 个应用；滑至最后的文件夹切换为收藏，反向滑回应用后松手，以 HyperOS 原生小窗打开。", 14.sp)
            Spacer(Modifier.height(20.dp))
            TextLine("测试版只支持 HyperOS 4。安装后需在 LSPosed 启用作用域并重启手机。", 12.sp)
        }
    }
}

@Composable
private fun ItemRow(label: String, value: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick).padding(18.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        TextLine(label, 15.sp, bold = true)
        TextLine(value, 13.sp)
    }
}

@Composable
private fun TextLine(s: String, size: androidx.compose.ui.unit.TextUnit, bold: Boolean = false) {
    BasicText(s, style = TextStyle(
        fontSize = size,
        color = Color(0xFF21242A),
        fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal
    ))
}
