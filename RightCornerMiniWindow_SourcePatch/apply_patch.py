#!/usr/bin/env python3
"""Adapt pinned HyperSideBar e45661e, using its actual input/transport/window/launcher.
V0.1–0.3 replacement triggers and overlays are retired and are never applied.
"""
from pathlib import Path
import hashlib
import json
import shutil
import sys

ROOT = Path(sys.argv[1]).resolve()
HERE = Path(__file__).resolve().parent
K = 'app/src/main/java/com/lsp/hypersidebar/'
changes = {}

def edit(path, transform):
    p = ROOT / path
    before = p.read_text()
    after = transform(before)
    if before == after:
        raise SystemExit('No change: ' + path)
    changes.setdefault(path, {'before': hashlib.sha256(before.encode()).hexdigest()})
    p.write_text(after)

def replace(path, a, b, count=1):
    def apply(s):
        if s.count(a) != count:
            raise SystemExit(f'Pinned-source anchor mismatch: {path}: {s.count(a)} != {count}')
        return s.replace(a, b)
    edit(path, apply)

def copy(name, dest):
    p = ROOT / (K + dest + name)
    p.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(HERE / 'patches' / name, p)
    changes[str(p.relative_to(ROOT))] = {'added': True}

# CI must never issue an APK signed with a new runner's ephemeral debug key.
build = 'app/build.gradle.kts'
replace(build, 'applicationId = "io.github.mikudayoooooooo.hypersidebar"', 'applicationId = "io.github.zzexnn.rightcornermini"')
replace(build, 'versionName = "2.3.1"', 'versionName = "0.4.0-upstream"')
replace(build, 'versionCode = computedVersionCode', 'versionCode = 2_100_000_000 + (System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1)')
replace(build, '    buildTypes {', '    buildTypes {\n        debug { signingConfig = null }')
replace(build, '        testInstrumentationRunner =', '        buildConfigField("String", "CORNER_BUILD_ID", "\\"${System.getenv("GITHUB_SHA") ?: "local"}\\"")\n        testInstrumentationRunner =')
for rel in ['app/src/main/cpp/hsb_config.c', 'app/src/main/cpp/hypersidebar_native.c']:
    edit(rel, lambda s: s.replace('Android/media/io.github.mikudayoooooooo.hypersidebar/', 'Android/media/io.github.zzexnn.rightcornermini/'))
replace('app/src/main/res/values/strings.xml', '<string name="app_name">hyperSidebar</string>', '<string name="app_name">右下角小窗</string>')

# Original CornerTrigger; only scope/orientation/parameter adaptations.
prefs = K+'prefs/PrefContract.kt'
replace(prefs, '    const val ENABLED = "enabled"', '''    const val CORNER_HAPTIC = "corner_haptic"
    const val CORNER_SENSITIVITY_DP = "cornerSensitivityDp"
    const val CORNER_DIAG_ACTION = com.lsp.hypersidebar.BuildConfig.APPLICATION_ID + ".CORNER_DIAG"
    const val ENABLED = "enabled"''')
replace(prefs, 'object LayoutDefaults {', 'object LayoutDefaults {\n    const val CORNER_SENSITIVITY_DP = 12')
replace(prefs, '    const val CORNER_SWIPE_ENABLED = false', '    const val CORNER_SWIPE_ENABLED = true')
trigger = K+'hook/CornerTrigger.kt'
replace(trigger, 'if (screenW <= 0f || screenH <= 0f || screenW >= screenH) return false', 'if (screenW <= 0f || screenH <= 0f) return false')
replace(trigger, 'claimed = inBand && (leftCorner || x >= screenW - cornerWidth)', 'claimed = inBand && x >= screenW - cornerWidth')
spy = K+'hook/SystemUiInputSpy.kt'
replace(spy, '        if (w > h) return false\n        val gt =', '        if (w <= 0 || h <= 0) return false\n        val gt =')
replace(spy, '        return x <= cornerPx || x >= w - cornerPx', '        return x >= w - cornerPx')
replace(spy, '''        prefs?.getBoolean(
            com.lsp.hypersidebar.prefs.PrefKeys.CORNER_SWIPE_ENABLED,
            com.lsp.hypersidebar.prefs.LayoutDefaults.CORNER_SWIPE_ENABLED
        ) ?: false''', '''        val p = prefs ?: return@runCatching false
        p.getBoolean(com.lsp.hypersidebar.prefs.PrefKeys.ENABLED, true) && p.getBoolean(
            com.lsp.hypersidebar.prefs.PrefKeys.CORNER_SWIPE_ENABLED, true)''')
for arg in ['confirmPx','inwardMinPx']:
    replace(spy, f'{arg} = gt.CORNER_INWARD_MIN_DP * dm.density,', f'{arg} = (prefs?.getInt(com.lsp.hypersidebar.prefs.PrefKeys.CORNER_SENSITIVITY_DP, 12) ?: 12).coerceIn(8, 24) * dm.density,')
turbo = K+'hook/TurboLayout.kt'
replace(turbo, 'val cornerConfirmPx = GestureThresholds.CORNER_INWARD_MIN_DP * density', 'val cornerConfirmPx = remotePrefs.getInt(PrefKeys.CORNER_SENSITIVITY_DP, 12).coerceIn(8, 24) * density')
replace(turbo, '                    GestureThresholds.CORNER_INWARD_MIN_DP * density,', '                    cornerConfirmPx,')
replace(turbo, 'val anchorX = if (ev.rawX < dm.widthPixels / 2f) 0f else dm.widthPixels.toFloat()', 'val anchorX = dm.widthPixels.toFloat()')
replace('app/src/main/cpp/hypersidebar_native.c',
        '    int hit = (x <= edge || x >= w - edge) && (y >= yband);',
        '    int hit = (x >= w - edge) && (y >= yband);')

# No persistent probe/prewarm overlays. Never bypass obscured-touch security.
replace(K+'hook/InsetProbe.kt', '        addOverlay(ctx)', '        // Miniwindow: no persistent inset-probe overlay.')
controller = K+'ui/fan/FanMenuController.kt'
def no_warmup(s):
    a=s.index('    fun warmupAssembly(context: Context) {')
    b=s.index('\n    fun hideAll()',a)
    return s[:a]+'    fun warmupAssembly(context: Context) {\n        // DataLoader still preloads data/icons; no offscreen window is attached.\n    }\n'+s[b:]
edit(controller,no_warmup)
replace(controller, '    private fun assembleFanData(context: Context, cornerAnchor: Boolean = false): FanData {', '''    private fun assembleFanData(context: Context, cornerAnchor: Boolean = false): FanData {
        if (cornerAnchor) return FanData(
            MiniMenu.recommendations(context, DataLoader.rankedApps(context, frozenSource)),
            emptyList(), emptyList(),
            context.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        )''')
replace(controller, '            onAppSelected = { appInfo ->', '''            onAppSelected = miniApp@{ appInfo ->
                if (MiniMenu.isControl(appInfo)) { hideAll(); return@miniApp }''')

# Change data in the SAME upstream host. Its motion dispatch, hit testing, geometry,
# selection hysteresis, window lifecycle and native launch callbacks stay in place.
host = K+'ui/fan/ComposeFanHost.kt'
replace(host, '    private class GeometryInput(', '    private data class GeometryInput(')
replace(host, '    private var built = false', '''    private var miniRecommendations: List<FanAppInfo> = emptyList()
    private var miniFavorites = false
    private var miniSwitchArmed = true
    private var built = false''')
replace(host, '        pendingInput = GeometryInput(anchorX, anchorY, apps, quickApps, isLandscape, cornerAnchor)', '''        miniRecommendations = if (cornerAnchor) apps else emptyList()
        miniFavorites = false
        miniSwitchArmed = true
        pendingInput = GeometryInput(anchorX, anchorY, apps, quickApps, isLandscape, cornerAnchor)''')
replace(host, '                        touchState.value = FanTouchState(x, y, 0, fanSel, quickSel)\n\n                        // 悬停展开组', '''                        if (pendingInput?.cornerAnchor == true) {
                            val isSwitch = fanSel == 5
                            if (!isSwitch) miniSwitchArmed = true
                            if (isSwitch && miniSwitchArmed) {
                                miniSwitchArmed = false
                                miniFavorites = !miniFavorites
                                pendingInput = pendingInput?.copy(apps = if (miniFavorites)
                                    MiniMenu.favorites(context, prefs) else miniRecommendations)
                                lastSelectedFanIndex = -1
                                lastSelectedQuickIndex = -1
                                touchState.value = FanTouchState(x, y, 0, -1, -1)
                                computeAndPublishGeometry()
                                return true
                            }
                            if (fanSel != prevFan && fanSel >= 0 && prefs.getBoolean(PrefKeys.CORNER_HAPTIC, true))
                                performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                        }
                        touchState.value = FanTouchState(x, y, 0, fanSel, quickSel)

                        // 悬停展开组''')
replace(host, 'cornerMaxAppsOuter = readInt(PrefKeys.CORNER_MAX_APPS_OUTER, LayoutDefaults.CORNER_MAX_APPS_OUTER),', 'cornerMaxAppsOuter = 6,')
replace(host, 'cornerMaxAppsInner = readInt(PrefKeys.CORNER_MAX_APPS_INNER, LayoutDefaults.CORNER_MAX_APPS_INNER)', 'cornerMaxAppsInner = 0')
compose=K+'ui/fan/FanMenuCompose.kt'
replace(compose,'            FanBoard(', '            if (!MiniMenu.isMenu(geometry.apps)) FanBoard(')
replace(compose,'        AppIconImage(\n            bitmap = bitmap,', '''        if (MiniMenu.isControl(item.app)) {
            if (item.app.packageName == MiniMenu.FOLDER || item.app.packageName == MiniMenu.BACK) {
                Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color(0xFF248AF5), androidx.compose.foundation.shape.CircleShape), contentAlignment = Alignment.Center) {
                    Text(if (item.app.packageName == MiniMenu.FOLDER) "•••" else "↩", color = androidx.compose.ui.graphics.Color.White)
                }
            }
        } else AppIconImage(
            bitmap = bitmap,''')

# Keep upstream configuration channel; repair partial writes and attach context only.
copy('NativeConfigValues.kt','util/')
cf=K+'util/ConfigFileBridge.kt'
replace(cf,'    private const val CONF_NAME = "hsb.conf"','    private const val CONF_NAME = "hsb.conf"\n    private val scalarValues = NativeConfigValues()')
replace(cf,'    fun pushScalars(entries: Map<String, Any>): Boolean {','    @Synchronized fun pushScalars(entries: Map<String, Any>): Boolean {')
replace(cf,'        val body = buildString {','        val merged = scalarValues.merge(entries)\n        val body = buildString {')
replace(cf,'            entries.forEach { (k, v) ->','            merged.forEach { (k, v) ->')
replace(K+'util/ConfigSync.kt','        hookCtx = context.applicationContext','        hookCtx = context')
for name,dest in [('MiniMenu.kt','ui/fan/'),('MiniDiagnostics.kt','util/'),('CornerMiniSettingsScreen.kt','ui/settings/')]:
    copy(name,dest)
activity=K+'MainActivity.kt'
replace(activity,'import com.lsp.hypersidebar.ui.settings.MainScreen','import com.lsp.hypersidebar.ui.settings.CornerMiniSettingsScreen')
def settings(s):
    a=s.index('                MainScreen(')
    b=s.index('\n            }',a)
    return s[:a]+'                CornerMiniSettingsScreen(activePrefs)'+s[b:]
edit(activity,settings)

# Read-only status/version receiver on existing setup points. No health-repair loop.
replace(turbo,'    override fun rearmHostSide(ctx: Context) {','    override fun rearmHostSide(ctx: Context) {\n        com.lsp.hypersidebar.util.MiniDiagnostics.register(ctx, remotePrefs)')
replace(K+'hook/SystemUiHook.kt','    private fun armHostSide(ctx: Context, via: String) {','    private fun armHostSide(ctx: Context, via: String) {\n        com.lsp.hypersidebar.util.MiniDiagnostics.register(ctx, prefs)')
xposed=K+'XposedInit.kt'
replace(xposed,'        installFor(host)\n    }', '''        installFor(host)
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            appContextOrNull()?.let { ctx ->
                reloadTargets().forEach { (_, h) -> runCatching { h.rearmHostSide(ctx) } }
                com.lsp.hypersidebar.util.MiniDiagnostics.register(ctx,
                    com.lsp.hypersidebar.util.SyncedPrefs(hookSidePrefs()))
                if (host == Host.SYSTEM_UI) runCatching {
                    com.lsp.hypersidebar.hook.SystemUiInputSpy.start(ctx,
                        com.lsp.hypersidebar.util.SyncedPrefs(hookSidePrefs()))
                }
            }
        }
    }''')
replace(xposed,'        for ((n, h) in reloadTargets()) {','        com.lsp.hypersidebar.util.MiniDiagnostics.release()\n        for ((n, h) in reloadTargets()) {')
restarter=K+'ui/settings/HostRestarter.kt'
replace(restarter,'    /** IO 线程调用：','    fun restartSystemUi(): Result = restart(HOSTS.filter { it.pkg == HostPackages.SYSTEM_UI })\n\n    /** IO 线程调用：')

replace(restarter, r'killCommand = "pids=\$(pidof ${HostPackages.SYSTEM_UI}); " +',
        r'killCommand = "pids=\$(pidof ${HostPackages.SYSTEM_UI}); [ -n \"\$pids\" ] || exit 1; " +')


for name in ['LICENSE','NOTICE']:
    p=ROOT/'app/src/main/assets'/('RightCornerMiniWindow_'+name)
    p.parent.mkdir(parents=True,exist_ok=True)
    shutil.copyfile(HERE.parent/name,p)
for rel,entry in changes.items():
    p=ROOT/rel
    if p.suffix in {'.kt','.kts','.c'} and not entry.get('added'):
        p.write_text('// Modified for RightCornerMiniWindow V0.4; Apache-2.0 attribution in bundled NOTICE.\n'+p.read_text())
    entry['after']=hashlib.sha256(p.read_bytes()).hexdigest()
(ROOT/'miniwindow-changes.json').write_text(json.dumps(changes,ensure_ascii=False,indent=2)+'\n')
print('Applied upstream adaptations:',len(changes),'files. No retired patch executed.')
