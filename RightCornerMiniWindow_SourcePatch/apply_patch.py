#!/usr/bin/env python3
"""Apply the corner-first MVP to a clean HyperSideBar checkout.

This preserves the upstream HyperOS 4 native and input monitor mechanisms.
It does not automatically remove every upstream setting page or feature.

Usage: python3 apply_patch.py /path/to/HyperSideBar
"""
from pathlib import Path
import re
import shutil
import sys

ROOT = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else None
SRC = Path(__file__).resolve().parent / "patches"
if ROOT is None or not (ROOT / 'app/src/main/java/com/lsp/hypersidebar/hook/TurboLayout.kt').is_file():
    raise SystemExit('Usage: python3 apply_patch.py /path/to/HyperSideBar (clean master checkout)')


def replace(path, before, after, number=1):
    f = ROOT / path
    s = f.read_text(encoding='utf-8')
    count = s.count(before)
    if count != number:
        raise SystemExit(f'Anchor mismatch in {path}: wanted {number}, found {count}. Stop safely; do not patch unknown revision.')
    f.write_text(s.replace(before, after), encoding='utf-8')
    print('patched', path)

hook = 'app/src/main/java/com/lsp/hypersidebar/hook/'
for name in ['CornerMenuEngine.kt', 'CornerSwitchOverlay.kt', 'CornerMiniSettingsScreen.kt']:
    destination = hook if name != 'CornerMiniSettingsScreen.kt' else 'app/src/main/java/com/lsp/hypersidebar/ui/settings/'
    target = ROOT / destination / name
    if target.exists():
        raise SystemExit(f'File already exists: {target}; stop to avoid overwriting.')
    shutil.copyfile(SRC / name, target)
    print('added', target.relative_to(ROOT))

turbo = hook + 'TurboLayout.kt'
replace(turbo,
    '    private var cSysPilfered = false\n',
    '    private var cSysPilfered = false\n'
    '    // Right-corner-only one-stroke suggestions -> custom favorites overlay.\n'
    '    private val cornerSwitchMenu by lazy { CornerSwitchOverlay(remotePrefs) }\n')

replace(turbo,
    '        if (!b2Enabled() && !cornerFallbackOwned()) return\n'
    '        // fan 展示中',
    '        if (!b2Enabled() && !cornerFallbackOwned()) return\n'
    '        // Route later MOVE/UP/CANCEL to the new screen without creating a second touch owner.\n'
    '        if (cornerSwitchMenu.isShowing) {\n'
    '            cornerSwitchMenu.handle(ev)\n'
    '            if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL\n'
    '                || ev.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {\n'
    '                cornerTrigger.reset()\n'
    '                cornerEngaged = false\n'
    '            }\n'
    '            return\n'
    '        }\n'
    '        // fan 展示中')

replace(turbo,
    '                cornerEngaged = cornerSwipeEnabled()\n',
    '                cornerEngaged = moduleEnabled() && cornerSwipeEnabled() &&\n'
    '                    ev.rawX >= ctx.resources.displayMetrics.widthPixels *\n'
    '                        (1f - GestureThresholds.CORNER_WIDTH_RATIO)\n')

# HyperOS 4: UP before menu is attached must cancel the pending show,
# not put the old fan controller into "released" mode.
old = ('                    } else if (sPendingShow != null) {\n'
       '                        // 快扫整包到达（1007 晚）：SHOW 已发但 fan 尚未落地时手指已抬起——\n'
       '                        // 沿用"撤销排队 show"会让快扫永远白做（201757 导出：整段流压缩成\n'
       '                        // DOWN+UP 一次到达）。落地转点选态：点图标直启、点空白/看门狗收。\n'
       '                        fanController.markReleasedOnArrival()\n')
new = ('                    } else if (sPendingShow != null) {\n'
       '                        // Strict single continuous gesture: no lingering menu after UP.\n'
       '                        cancelPendingStripShow()\n')
replace(turbo, old, new)

# Keep the existing post scheduling and cleanup, but replace its target.
f = ROOT / turbo
s = f.read_text(encoding='utf-8')
start = s.index('    private fun postShowCornerFan(ctx: Context, ev: MotionEvent) {')
end = s.index('\n    private fun isLandscape(view: View)', start)
body = '''    private fun postShowCornerFan(ctx: Context, ev: MotionEvent) {
        if (ev.rawX < ctx.resources.displayMetrics.widthPixels / 2f) return
        val frozenSource = ForegroundSourceProbe.capture(HostPackages.UI_HOST)
        val r = Runnable {
            sPendingShow = null
            runCatching { cornerSwitchMenu.show(ctx, frozenSource) }
                .onFailure { HLog.w(TAG, "corner switch show failed: ${it.message}") }
        }
        sPendingShow = r
        mainHandler.post(r)
    }
'''
f.write_text(s[:start] + body + s[end:], encoding='utf-8')
print('patched', turbo, '(corner show entry)')

# Avoid legacy controller holding a second canvas on a corner gesture.
# On hot reload the new overlay must be detached too.
replace(turbo,
    '            cancelPendingStripShow()\n            cancelStallTimer()\n            cancelAuthPoll()\n',
    '            cancelPendingStripShow()\n            cornerSwitchMenu.dismiss()\n            cancelStallTimer()\n            cancelAuthPoll()\n')

spy = hook + 'SystemUiInputSpy.kt'
replace(spy,
    '        return x <= cornerPx || x >= w - cornerPx\n',
    '        return x >= w - cornerPx\n')

# Respect the global enable switch at the SystemUI input-owner side before
# pilfering. Otherwise turning off the module could still swallow corner swipes.
replace(spy,
    '''    private fun cornerSwipeEnabled(): Boolean = runCatching {
        prefs?.getBoolean(
            com.lsp.hypersidebar.prefs.PrefKeys.CORNER_SWIPE_ENABLED,
            com.lsp.hypersidebar.prefs.LayoutDefaults.CORNER_SWIPE_ENABLED
        ) ?: false
    }.getOrDefault(false)
''',
    '''    private fun cornerSwipeEnabled(): Boolean = runCatching {
        val p = prefs ?: return@runCatching false
        p.getBoolean(com.lsp.hypersidebar.prefs.PrefKeys.ENABLED, true) &&
            p.getBoolean(
                com.lsp.hypersidebar.prefs.PrefKeys.CORNER_SWIPE_ENABLED,
                com.lsp.hypersidebar.prefs.LayoutDefaults.CORNER_SWIPE_ENABLED
            )
    }.getOrDefault(false)
''')

# InputMonitor only routes right-corner gestures (not the normal middle edge strip).
replace(spy,
    '        return y >= top && y <= top + bandH && nearSide\n',
    '        return false // Right-corner-only build: disable middle edge trigger.\n')

native = 'app/src/main/cpp/hypersidebar_native.c'
replace(native,
    '    int hit = (x <= edge || x >= w - edge) && (y >= yband);\n',
    '    int hit = (x >= w - edge) && (y >= yband); // right corner only\n')

prefs = 'app/src/main/java/com/lsp/hypersidebar/prefs/PrefContract.kt'
replace(prefs,
    '    const val CORNER_SWIPE_ENABLED = false\n',
    '    const val CORNER_SWIPE_ENABLED = true\n')

build = 'app/build.gradle.kts'
replace(build,
    'applicationId = "io.github.mikudayoooooooo.hypersidebar"',
    'applicationId = "io.github.zzexnn.rightcornermini"')
replace(build,
    'versionName = "2.3.1"',
    'versionName = "0.2.0-test"')

strings = 'app/src/main/res/values/strings.xml'
replace(strings,
    '<string name="app_name">hyperSidebar</string>',
    '<string name="app_name">右下角小窗（测试版）</string>')
replace(strings,
    '<string name="module_description">替换 HyperOS 原生侧边栏为双层半圆扇形摇杆菜单。侧滑唤醒，松手即小窗。</string>',
    '<string name="module_description">右下角斜滑：常用应用 + 自定义收藏，原生小窗启动。</string>')

# Replace the giant settings entry with a smaller page, still leveraging
# the upstream application picker and existing preferences synchronization.
replace('app/src/main/java/com/lsp/hypersidebar/MainActivity.kt',
    'import com.lsp.hypersidebar.ui.settings.MainScreen',
    'import com.lsp.hypersidebar.ui.settings.CornerMiniSettingsScreen')
replace('app/src/main/java/com/lsp/hypersidebar/MainActivity.kt',
    '''                MainScreen(
                    prefs = activePrefs,
                    service = xposedService,
                    themeMode = themeMode,
                    onThemeModeChange = { mode ->
                        themeMode = mode
                        activePrefs.savePref(PrefKeys.THEME_MODE, mode)
                        fallbackPrefs.savePref(PrefKeys.THEME_MODE, mode)
                    }
                )
''',
    '                CornerMiniSettingsScreen(prefs = activePrefs)\n')

# IMPORTANT: app selection and backend code remain in the MVP so the already working
# CUSTOM_APPS/CUSTOM_APPS_ORDER selection and cross-process sync can be reused.
print('\nPatch applied. Build with ./gradlew assembleDebug on an Android-enabled machine.')
print('Favorite apps use the retained app-picker under 自定义收藏; capped at five in corner menu.')

# Keep V0.2 repairs separate so every edit is checked against the pinned V0.1 output.
exec(compile((SRC.parent / "apply_fixes.py").read_text(encoding="utf-8"), str(SRC.parent / "apply_fixes.py"), "exec"))
exec(compile((SRC.parent / "apply_v03.py").read_text(encoding="utf-8"), str(SRC.parent / "apply_v03.py"), "exec"))
