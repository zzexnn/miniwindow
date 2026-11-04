"""V0.2 fixes; executed by apply_patch.py against its pinned, validated output."""
# ROOT, SRC, replace, hook and turbo are supplied by apply_patch.py.
for name in ['CornerRuntime.kt', 'CornerHostBridge.kt']:
    shutil.copyfile(SRC / name, ROOT / hook / name)

def edit(path, transform):
    f = ROOT / path
    before = f.read_text(encoding='utf-8')
    after = transform(before)
    if before == after:
        raise SystemExit(f'Fix made no change: {path}')
    f.write_text(after, encoding='utf-8')

prefs = 'app/src/main/java/com/lsp/hypersidebar/prefs/PrefContract.kt'
replace(prefs, '    const val ENABLED = "enabled"', '''    const val CORNER_HAPTIC = "corner_haptic"
    const val CORNER_SENSITIVITY_DP = "cornerSensitivityDp"
    const val CORNER_DIAG_ACTION = com.lsp.hypersidebar.BuildConfig.APPLICATION_ID + ".CORNER_DIAG"
    const val CORNER_DIAG_TEST = "testMenu"
    const val ENABLED = "enabled"''')
replace(prefs, 'object LayoutDefaults {', '''object LayoutDefaults {
    const val CORNER_SENSITIVITY_DP = 12
    const val CORNER_SENSITIVITY_MIN_DP = 8
    const val CORNER_SENSITIVITY_MAX_DP = 24
    const val CORNER_SINK_PROBE_MS = 10_000L
    const val CORNER_SINK_TTL_MS = 30_000L''')
# C readers must follow the APK's new applicationId; leave class namespace unchanged.
for path in ['app/src/main/cpp/hsb_config.c', 'app/src/main/cpp/hypersidebar_native.c']:
    edit(path, lambda s: s.replace('Android/media/io.github.mikudayoooooooo.hypersidebar/',
                                 'Android/media/io.github.zzexnn.rightcornermini/'))
# The right-corner build supports tablets in either orientation, using the same cone/threshold.
trigger = hook + 'CornerTrigger.kt'
replace(trigger, '        confirmFrames: Int,\n', '        confirmFrames: Int,\n        allowLandscape: Boolean = false,\n')
replace(trigger, 'if (screenW <= 0f || screenH <= 0f || screenW >= screenH) return false',
        'if (screenW <= 0f || screenH <= 0f || (!allowLandscape && screenW >= screenH)) return false')
replace(spy, '        if (w > h) return false\n        val gt =', '        if (w <= 0 || h <= 0) return false\n        val gt =')
replace(spy, '            confirmFrames = if (cornerPilferHost) gt.CORNER_CONFIRM_FRAMES_FAST else gt.CORNER_CONFIRM_FRAMES,',
        '            confirmFrames = gt.CORNER_CONFIRM_FRAMES_FAST,\n            allowLandscape = true,')
replace(spy, 'confirmPx = gt.CORNER_INWARD_MIN_DP * dm.density,', 'confirmPx = CornerRuntime.inwardDp(prefs) * dm.density,')
replace(spy, 'inwardMinPx = gt.CORNER_INWARD_MIN_DP * dm.density,', 'inwardMinPx = CornerRuntime.inwardDp(prefs) * dm.density,')
replace(turbo, '                    GestureThresholds.CORNER_CONFIRM_FRAMES_FAST\n',
        '                    GestureThresholds.CORNER_CONFIRM_FRAMES_FAST,\n                    allowLandscape = true\n')
replace(turbo, 'val cornerConfirmPx = GestureThresholds.CORNER_INWARD_MIN_DP * density',
        'val cornerConfirmPx = CornerRuntime.inwardDp(remotePrefs) * density')
replace(turbo, '                    GestureThresholds.CORNER_INWARD_MIN_DP * density,',
        '                    cornerConfirmPx,')
# Shared actual display bounds instead of per-package resources that may exclude navigation bars.
replace(turbo, '                val dm = ctx.resources.displayMetrics\n', '                val dm = CornerRuntime.metrics(ctx)\n', number=2)
replace(turbo, 'ev.rawX >= ctx.resources.displayMetrics.widthPixels *', 'ev.rawX >= CornerRuntime.metrics(ctx).widthPixels *')
replace(spy, 'val dm = android.content.res.Resources.getSystem().displayMetrics\n                            onMotion',
        'val dm = CornerRuntime.metrics(appContext)\n                            onMotion')
# Use a proven receiver as the corner ownership gate, independent of phone-specific AIDL markers.
replace(turbo, '        if (!b2Enabled() && !cornerFallbackOwned()) return\n',
        '        if (!cornerInputEnabled()) { cornerSwitchMenu.dismiss(); return }\n')
replace(turbo, '    internal fun b2Enabled(): Boolean =',
        '    internal fun cornerInputEnabled(): Boolean = moduleEnabled() && cornerSwipeEnabled() && !breaker.open\n\n    internal fun b2Enabled(): Boolean =')
replace(hook + 'RelayGestureSink.kt',
        'val allowed = if (cornerStream) o.b2Enabled() || o.cornerFallbackOwned() else o.b2Enabled()',
        'val allowed = if (cornerStream) o.cornerInputEnabled() else o.b2Enabled()')
# Validate incoming gesture IPC, guard host callbacks, and allow a failed registration to retry.
replace(hook + 'RelayGestureSink.kt', '                    val ev = intent.getParcelableExtra<MotionEvent>',
        '                    val p = owner?.b2Prefs ?: return\n'
        '                    if (!RelayToken.verifyFan(intent, RelayToken.read(p))) return\n'
        '                    val ev = intent.getParcelableExtra<MotionEvent>')
replace(hook + 'RelayGestureSink.kt', '                        dispatch(c, intent, ev)',
        '                        runCatching { dispatch(c, intent, ev) }\n'
        '                            .onFailure { HLog.w(TAG, "stream dispatch failed: ${it.message}") }')
replace(hook + 'RelayGestureSink.kt', '}.onFailure { HLog.w(TAG, "register failed: ${it.message}") }',
        '}.onFailure { registered = false; HLog.w(TAG, "register failed: ${it.message}") }')
# Display the menu inside the receiver's main-thread callback, before a queued UP can cancel it.
f = ROOT / turbo
s = f.read_text()
a = s.index('    private fun postShowCornerFan(ctx: Context, ev: MotionEvent) {')
b = s.index('\n    private fun isLandscape(view: View)', a)
s = s[:a]+'''    private fun postShowCornerFan(ctx: Context, ev: MotionEvent) {
        // Corner side was fixed on DOWN. MOVE may legitimately cross the screen midpoint.
        check(android.os.Looper.myLooper() == android.os.Looper.getMainLooper())
        val frozenSource = runCatching { ForegroundSourceProbe.capture(HostPackages.UI_HOST) }.getOrNull()
        runCatching {
            if (cornerSwitchMenu.show(ctx, frozenSource)) cornerSwitchMenu.handle(ev)
        }.onFailure { HLog.w(TAG, "RCM_020 show failed: ${it.message}") }
    }
''' +s[b:]
f.write_text(s)
replace(turbo, '                        if (!cSysPilfered) RelayGestureSink.requestPilfer()\n                        postShowCornerFan(ctx, ev)',
        '                        if (cSysPilfered) postShowCornerFan(ctx, ev)\n'
        '                        else HLog.w(TAG, "RCM_020 no menu: input ownership not confirmed")')
# A SystemUI-confirmed later sample can arrive after :ui has already crossed its threshold.
replace(turbo, '                cLastHist = hs\n',
        '                cLastHist = hs\n'
        '                if (cSysPilfered && cornerTrigger.lastReject == "SHOWN") {\n'
        '                    postShowCornerFan(ctx, ev)\n                    return\n                }\n')
# Previous stream, cancellation, multitouch and rotation must never select an old app.
replace(turbo, '        if (cornerSwitchMenu.isShowing) {\n',
        '        if (ev.actionMasked == MotionEvent.ACTION_DOWN) cornerSwitchMenu.dismiss()\n'
        '        if (cornerSwitchMenu.isShowing) {\n')
replace(turbo, '        runCatching { RelayGestureSink.rearmHostSide(ctx) }',
        '''        CornerHostBridge.register(ctx, remotePrefs, status = {
            android.os.Bundle().apply {
                putBoolean("ready", cornerInputEnabled())
                putString("detail", "执行端已注入；可接收角区流=${cornerInputEnabled()}；推荐数据源失效=${DataDeadState.dead}（收藏独立可用）；OS4标记=$isOs4Sidebar；最近手势=$cornerSeq；窗口错误=${cornerSwitchMenu.lastError ?: "无"}")
            }
        }, test = { cornerSwitchMenu.test(ctx) })
        runCatching { RelayGestureSink.rearmHostSide(ctx) }''')
# Readiness lease: do not pilfer unless this very APK's :ui receiver recently replied.
f=ROOT / spy
s=f.read_text()
a=s.index('    private fun sysJudgeEnabled(): Boolean {')
b=s.index('\n    /** 底角几何/阈值', a)
s=s[:a]+'''    private var sinkReadyAt = 0L
    private var sinkProbeAt = 0L
    private fun sysJudgeEnabled(): Boolean = cornerSwipeEnabled() && sinkReadyAt > 0L &&
        android.os.SystemClock.uptimeMillis() - sinkReadyAt <
            com.lsp.hypersidebar.prefs.LayoutDefaults.CORNER_SINK_TTL_MS

    private fun refreshCornerSink() {
        val ctx = appContext ?: return
        val p = prefs ?: return
        val now = android.os.SystemClock.uptimeMillis()
        if (sinkProbeAt != 0L && now - sinkProbeAt < com.lsp.hypersidebar.prefs.LayoutDefaults.CORNER_SINK_PROBE_MS) return
        sinkProbeAt = now
        val i = android.content.Intent(com.lsp.hypersidebar.prefs.PrefKeys.CORNER_DIAG_ACTION)
            .setPackage(com.lsp.hypersidebar.prefs.HostPackages.UI_HOST)
        RelayToken.attach(i, RelayToken.read(p))
        runCatching {
            ctx.sendOrderedBroadcast(i, null, object : android.content.BroadcastReceiver() {
                override fun onReceive(c: android.content.Context?, i: android.content.Intent?) {
                    val e = getResultExtras(false)
                    val ready = resultCode == com.lsp.hypersidebar.prefs.PrefKeys.PROBE_CODE_OK &&
                        e?.getInt("versionCode") == com.lsp.hypersidebar.BuildConfig.VERSION_CODE &&
                        e.getString("version") == com.lsp.hypersidebar.BuildConfig.VERSION_NAME &&
                        e.getBoolean("ready")
                    val before = sinkReadyAt > 0L
                    sinkReadyAt = if (ready) android.os.SystemClock.uptimeMillis() else 0L
                    if (before != ready) HLog.i(TAG, "RCM_020 sink ready=$ready code=$resultCode")
                }
            }, mainHandler, 0, null, null)
        }.onFailure { sinkReadyAt = 0L; HLog.w(TAG, "sink probe failed: ${it.message}") }
    }

    private val sinkWatch = object : Runnable {
        override fun run() {
            if (!started) return
            refreshCornerSink()
            mainHandler.postDelayed(this, com.lsp.hypersidebar.prefs.LayoutDefaults.CORNER_SINK_PROBE_MS)
        }
    }

    fun cornerDiagnostic(): android.os.Bundle = android.os.Bundle().apply {
        putBoolean("ready", started && !gaveUp)
        putString("detail", "SystemUI已注入；输入监视器=$started；收到DOWN=$totalDown；执行端就绪=${sysJudgeEnabled()}；最近角区MOVE=$diagMoves；已抢流=$sysPilfered")
    }
''' +s[b:]
s=s.replace('            started = true\n', '            started = true\n            mainHandler.removeCallbacks(sinkWatch)\n            mainHandler.post(sinkWatch)\n')
s=s.replace('            val corner = inCornerTriangle(ev.x, ev.y, w, h)', '            refreshCornerSink()\n            val corner = inCornerTriangle(ev.x, ev.y, w, h)')
s=s.replace('        if (sysPilfered) return\n', '        if (sysPilfered || !sysJudgeEnabled()) return\n')
# Protect SystemUI even if decoding/forwarding a hostile event fails.
s=s.replace('                            onMotion(event, dm.widthPixels, dm.heightPixels)',
            '                            runCatching { onMotion(event, dm.widthPixels, dm.heightPixels) }\n'
            '                                .onFailure { HLog.w(TAG, "input callback failed: ${it.message}") }')
f.write_text(s)
replace(hook + 'SystemUiHook.kt', '    private fun armHostSide(ctx: Context, via: String) {',
        '''    private fun armHostSide(ctx: Context, via: String) {
        CornerHostBridge.register(ctx, prefs, status = { SystemUiInputSpy.cornerDiagnostic() })''')
# Config ACK used applicationContext during attach, which can still be null.
replace('app/src/main/java/com/lsp/hypersidebar/util/ConfigSync.kt',
        '        hookCtx = context.applicationContext', '        hookCtx = context')
# Do not rely solely on catching Application.attach. Re-arm after PackageReady too.
xposed='app/src/main/java/com/lsp/hypersidebar/XposedInit.kt'
replace(xposed, '    private val TAG = "XposedInit"',
        '    private val hostReadyHandler = android.os.Handler(android.os.Looper.getMainLooper())\n    private val TAG = "XposedInit"')
replace(xposed, '        HLog.setProcOverride(param.processName)',
        '        HLog.setProcOverride(param.processName)\n'
        '        HLog.i(TAG, "RCM_020 INJECTED version=${BuildConfig.VERSION_NAME}/${BuildConfig.VERSION_CODE} proc=${param.processName}")')
replace(xposed, '        installFor(host)\n    }', '''        installFor(host)
        armReadyHost(host)
    }

    private fun armReadyHost(host: Host, attempt: Int = 0) {
        hostReadyHandler.postDelayed({
            val ctx = appContextOrNull()
            if (ctx == null) {
                if (attempt < 6) armReadyHost(host, attempt + 1)
                else HLog.w(TAG, "RCM_020 host context unavailable")
            } else {
                reloadTargets().forEach { (_, h) ->
                    runCatching { h.rearmHostSide(ctx) }
                        .onFailure { HLog.w(TAG, "host ready ${h.name}: ${it.message}") }
                }
                if (host == Host.SYSTEM_UI) runCatching {
                    com.lsp.hypersidebar.hook.SystemUiInputSpy.start(
                        ctx, com.lsp.hypersidebar.util.SyncedPrefs(hookSidePrefs()))
                }
            }
        }, if (attempt == 0) 0L else 500L)
    }''')
replace(xposed, '        for ((n, h) in reloadTargets()) {',
        '        hostReadyHandler.removeCallbacksAndMessages(null)\n'
        '        com.lsp.hypersidebar.hook.CornerHostBridge.releaseForReload()\n'
        '        for ((n, h) in reloadTargets()) {')
# Wire the new pure-logic regression tests into the upstream JUnit suite.
for name in ['CornerMenuEngineTest.kt', 'CornerTabletTriggerTest.kt']:
    target=ROOT/'app/src/test/java/com/lsp/hypersidebar/hook'/name
    shutil.copyfile(SRC.parent/'tests'/name, target)
print('V0.2 fixes applied: physical display, orientation, ownership lease, diagnostics, safe navigation')

# Binder is the authenticated bootstrap for the relay secret. Public config pushes exclude it.
config='app/src/main/java/com/lsp/hypersidebar/util/ConfigSync.kt'
replace(config, '    fun applySync(map: Map<String, Any>) {',
        '    fun applySync(map: Map<String, Any>, authenticated: Boolean = false) {')
replace(config, '        cache = java.util.Collections.unmodifiableMap(HashMap(map))',
        '        val next = HashMap(map)\n        val tokenKey = com.lsp.hypersidebar.prefs.PrefKeys.RELAY_TOKEN\n        if (!authenticated) {\n            next.remove(tokenKey)\n            cache[tokenKey]?.let { next[tokenKey] = it }\n        }\n        cache = java.util.Collections.unmodifiableMap(next)')
pull='app/src/main/java/com/lsp/hypersidebar/util/ConfigPullBridge.kt'
replace(pull, 'fun refreshIfStale(context: Context?) {', 'fun refreshIfStale(context: Context?, force: Boolean = false) {')
replace(pull, '        if (!ConfigSync.isStale()) return', '        if (!force && !ConfigSync.isStale()) return')
replace(pull, '        if (now - lastPullAt < PULL_MIN_INTERVAL_MS) return',
        '        if (lastPullAt != 0L && now - lastPullAt < PULL_MIN_INTERVAL_MS) return')
replace(pull, '            ConfigSync.applySync(map)', '            ConfigSync.applySync(map, authenticated = true)')
replace(spy, 'import com.lsp.hypersidebar.util.HLog', 'import com.lsp.hypersidebar.util.HLog\nimport com.lsp.hypersidebar.util.RelayToken')
replace(spy, '        val p = prefs ?: return\n        val now = android.os.SystemClock.uptimeMillis()',
        '        val p = prefs ?: return\n'
        '        com.lsp.hypersidebar.util.ConfigPullBridge.refreshIfStale(ctx, force = RelayToken.read(p).isNullOrEmpty())\n'
        '        val now = android.os.SystemClock.uptimeMillis()')
replace(hook+'CornerHostBridge.kt', '                val expected = RelayToken.read(prefs)',
        '                val expected = RelayToken.read(prefs)\n'
        '                if (expected.isNullOrEmpty()) com.lsp.hypersidebar.util.ConfigPullBridge.refreshIfStale(c, force = true)')
replace(hook+'RelayGestureSink.kt', '                    if (!RelayToken.verifyFan(intent, RelayToken.read(p))) return',
        '                    val expected = RelayToken.read(p)\n'
        '                    if (expected.isNullOrEmpty() || intent.getStringExtra(PrefKeys.RELAY_LAUNCH_EXTRA_TOKEN) != expected) return')
# Every newly adjustable setting has a matching restore-default path.
replace('app/src/main/java/com/lsp/hypersidebar/prefs/SettingsRepository.kt',
        '            putBoolean(PrefKeys.CORNER_SWIPE_ENABLED, LayoutDefaults.CORNER_SWIPE_ENABLED)',
        '            putBoolean(PrefKeys.CORNER_SWIPE_ENABLED, LayoutDefaults.CORNER_SWIPE_ENABLED)\n'
        '            putBoolean(PrefKeys.CORNER_HAPTIC, true)\n'
        '            putInt(PrefKeys.CORNER_SENSITIVITY_DP, LayoutDefaults.CORNER_SENSITIVITY_DP)')

# Distribute the upstream license with the APK as well as the source patch.
for name in ['LICENSE', 'NOTICE']:
    shutil.copyfile(SRC.parent.parent/name, ROOT/'app/src/main/assets'/('RightCornerMiniWindow_'+name))
# Prominent modified-file notices for the Apache-2.0 derived distribution.
modified = [turbo, spy, prefs, xposed, config, pull, trigger, hook+'RelayGestureSink.kt',
            hook+'SystemUiHook.kt', 'app/build.gradle.kts',
            'app/src/main/java/com/lsp/hypersidebar/MainActivity.kt',
            'app/src/main/java/com/lsp/hypersidebar/prefs/SettingsRepository.kt']
for rel in modified:
    f=ROOT/rel
    f.write_text('// Modified for RightCornerMiniWindow V0.2; upstream attribution in assets/RightCornerMiniWindow_NOTICE.\n'+f.read_text())
for rel in ['app/src/main/cpp/hsb_config.c', 'app/src/main/cpp/hypersidebar_native.c']:
    f=ROOT/rel
    f.write_text('/* Modified for RightCornerMiniWindow V0.2: application-specific media paths. */\n'+f.read_text())
# A second finger cancels intent at the same input owner that would otherwise pilfer.
replace(spy, '        val zone = tr.zone\n',
        '        val zone = tr.zone\n'
        '        if (ev.pointerCount > 1 || a == MotionEvent.ACTION_POINTER_DOWN) {\n'
        '            sysCorner.onPointerDown()\n'
        '            pendingMove?.recycle()\n'
        '            pendingMove = null\n'
        '        }\n')
# Partial writes used to replace hsb.conf: pushBand erased all preceding corner settings.
util='app/src/main/java/com/lsp/hypersidebar/util/'
shutil.copyfile(SRC/'NativeConfigValues.kt', ROOT/util/'NativeConfigValues.kt')
shutil.copyfile(SRC.parent/'tests/NativeConfigValuesTest.kt',
                ROOT/'app/src/test/java/com/lsp/hypersidebar/util/NativeConfigValuesTest.kt')
replace(util+'ConfigFileBridge.kt', '    private const val CONF_NAME = "hsb.conf"',
        '    private const val CONF_NAME = "hsb.conf"\n    private val scalarValues = NativeConfigValues()')
replace(util+'ConfigFileBridge.kt', '    fun pushScalars(entries: Map<String, Any>): Boolean {',
        '    @Synchronized fun pushScalars(entries: Map<String, Any>): Boolean {')
replace(util+'ConfigFileBridge.kt', '        val body = buildString {',
        '        val merged = scalarValues.merge(entries)\n        val body = buildString {')
replace(util+'ConfigFileBridge.kt', '            entries.forEach { (k, v) ->',
        '            merged.forEach { (k, v) ->')
# There is no side-strip consumer in this product; disable its native suppression as well.
# hsb_band_geom treats width=0 as an invalid/disabled band and passes normal Back through.
replace(util+'CornerGeomBridge.kt', '            PROP_BAND_W_PX to f(g.wPx),',
        '            PROP_BAND_W_PX to "0", // Right-corner-only build: preserve native side Back')
# Do not leave a competing legacy fan/touch hook installed on Java-based tablet launchers.
f=ROOT/xposed
s=f.read_text()
a=s.index('            Host.HOME -> {')
b=s.index('            Host.SYSTEM_UI -> {', a)
s=s[:a]+'''            Host.HOME -> {
                HLog.i(TAG, "RCM_020 launcher native entry retained; legacy Java fan input disabled")
            }
'''+s[b:]
f.write_text(s)
replace(turbo, '''        listOf(
            { hookOnTouch() },
            { hookCoverPassThrough() },
            { hookCoverLifecycleFlags() },
            { hookHideWhiteBar() },
            { hookHandleBarPixelKill() }
        ).forEach''', '        listOf<() -> Unit>().forEach')
replace(turbo, '''            listOf(
                { hookOnTouch() },
                { hookCoverPassThrough() },
                { hookHideWhiteBar() }
            ).forEach''', '            listOf<() -> Unit>().forEach')
# Different APKs can share versionCode on the same date; identify the exact build in probes.
replace('app/build.gradle.kts', '        buildConfigField("String", "XPOSED_API_VERSION",',
        '        buildConfigField("String", "CORNER_BUILD_ID", "\\\"${System.getenv("GITHUB_SHA") ?: "local"}\\\"")\n'
        '        buildConfigField("String", "XPOSED_API_VERSION",')
replace(hook+'CornerHostBridge.kt', '                    out.putString("version", BuildConfig.VERSION_NAME)',
        '                    out.putString("version", BuildConfig.VERSION_NAME)\n'
        '                    out.putString("build", BuildConfig.CORNER_BUILD_ID)')
replace(spy, '                        e.getString("version") == com.lsp.hypersidebar.BuildConfig.VERSION_NAME &&',
        '                        e.getString("version") == com.lsp.hypersidebar.BuildConfig.VERSION_NAME &&\n'
        '                        e.getString("build") == com.lsp.hypersidebar.BuildConfig.CORNER_BUILD_ID &&')
replace(xposed, 'RCM_020 INJECTED version=${BuildConfig.VERSION_NAME}',
        'RCM_020 INJECTED build=${BuildConfig.CORNER_BUILD_ID} version=${BuildConfig.VERSION_NAME}')
for rel in [util+'ConfigFileBridge.kt', util+'CornerGeomBridge.kt']:
    f=ROOT/rel
    f.write_text('// Modified for RightCornerMiniWindow V0.2; see assets/RightCornerMiniWindow_NOTICE.\n'+f.read_text())
