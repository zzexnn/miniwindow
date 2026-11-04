"""V0.3: single-authority early corner recognition, floating-arc UI and manual SystemUI restart."""
for name in ['CornerSwipeRecognizer.kt','CornerGestureSession.kt']:
    shutil.copyfile(SRC/name,ROOT/hook/name)
for name in ['CornerSwipeRecognizerTest.kt','CornerGestureSessionTest.kt']:
    shutil.copyfile(SRC.parent/'tests'/name,ROOT/'app/src/test/java/com/lsp/hypersidebar/hook'/name)
replace('app/build.gradle.kts','versionName = "0.2.0-test"','versionName = "0.3.0-test"')
replace(prefs,'    const val CORNER_DIAG_TEST = "testMenu"',
        '    const val CORNER_DIAG_TEST = "testMenu"\n    const val CORNER_DIAG_RECOVER = "recoverInput"')
# One direction judge beside InputMonitor. :ui consumes the proof of input ownership only.
replace(spy,'private val sysCorner = CornerTrigger()','private val sysCorner = CornerSwipeRecognizer()')
f=ROOT/spy
s=f.read_text()
a=s.index('    private fun sysCornerOnDown(')
b=s.index('\n    /** 同进程 pilfer',a)
s=s[:a]+'''    private fun sysCornerOnDown(x: Float, y: Float, w: Int, h: Int): Boolean {
        val density = CornerRuntime.metrics(appContext).density
        val setting = prefs?.getInt(
            com.lsp.hypersidebar.prefs.PrefKeys.CORNER_SENSITIVITY_DP,
            com.lsp.hypersidebar.prefs.LayoutDefaults.CORNER_SENSITIVITY_DP
        ) ?: 12
        return sysCorner.onDown(x, y, w.toFloat(), h.toFloat(), density, setting)
    }
''' +s[b:]
a=s.index('    private fun inCornerTriangle(')
b=s.index('\n    // （2026-10-13',a)
s=s[:a]+'''    private fun inCornerTriangle(x: Float, y: Float, w: Int, h: Int): Boolean =
        CornerSwipeRecognizer.inHotspot(x, y, w.toFloat(), h.toFloat(), CornerRuntime.metrics(appContext).density)
''' +s[b:]
a=s.index('    private fun sysCornerOnMove(')
b=s.index('\n    /** 注册抢占指令',a)
s=s[:a]+'''    private fun sysCornerOnMove(ev: MotionEvent): Boolean {
        if (ev.pointerCount != 1) { sysCorner.onPointerDown(); return false }
        for (sample in ev.toGestureSamples()) {
            if (sysCorner.onMove(sample.x, sample.y) == CornerSwipeRecognizer.Decision.CLAIM) return true
        }
        return false
    }
''' +s[b:]
a=s.index('    private fun forwardIfNeeded(')
b=s.index('\n    private fun sendStream(',a)
s=s[:a]+'''    private fun forwardIfNeeded(ev: MotionEvent, w: Int, h: Int) {
        val p = prefs ?: return
        val ctx = appContext ?: return
        val a = ev.actionMasked
        val zone = com.lsp.hypersidebar.prefs.PrefKeys.SPY_ZONE_CORNER
        if (a == MotionEvent.ACTION_DOWN) {
            refreshCornerSink()
            pendingMove?.recycle(); pendingMove = null
            sysCorner.reset(); sysPilfered = false
            tracks.clear()
            if (!cornerSwipeEnabled() || ev.pointerCount != 1 || !inCornerTriangle(ev.x, ev.y, w, h)) return
            tracks[ev.downTime] = Track(ev.deviceId, ev.source, ev.edgeFlags, ev.getPointerId(0), zone)
            lastDownTime = ev.downTime
            lastMoveFwdEventTime = ev.eventTime
            diagMoves = 0; diagFwdMoves = 0; diagHist = 0
            sysCornerOnDown(ev.x, ev.y, w, h)
            HLog.i(TAG, "RCM_030 corner DOWN judge=${sysJudgeEnabled()} raw=(${ev.x.toInt()},${ev.y.toInt()})")
            sendStream(ev, ctx, w, h, p, zone)
            return
        }
        if (tracks[ev.downTime] == null) return
        if (ev.pointerCount != 1 || a == MotionEvent.ACTION_POINTER_DOWN) {
            sysCorner.onPointerDown()
            pendingMove?.recycle(); pendingMove = null
            sendStream(ev, ctx, w, h, p, zone)
            return
        }
        if (a == MotionEvent.ACTION_MOVE) {
            diagMoves++; diagHist += ev.historySize
            val newlyOwned = !sysPilfered && sysJudgeEnabled() && sysCornerOnMove(ev)
            if (newlyOwned) sysPilferNow("early-corner", ev.downTime)
            // The first owned MOVE must reach the renderer now, never after a coalescing window.
            if (newlyOwned && sysPilfered) {
                pendingMove?.recycle(); pendingMove = null
                lastMoveFwdEventTime = ev.eventTime
                diagFwdMoves++
                HLog.i(TAG, "RCM_030 OWNED after=${ev.eventTime-ev.downTime}ms ${sysCorner.diag()}")
                sendStream(ev, ctx, w, h, p, zone)
                return
            }
            if (ev.eventTime-lastMoveFwdEventTime < MOVE_COALESCE_MS) {
                pendingMove?.recycle(); pendingMove = MotionEvent.obtain(ev)
                return
            }
            pendingMove?.recycle(); pendingMove = null
            lastMoveFwdEventTime = ev.eventTime
            diagFwdMoves++
            sendStream(ev, ctx, w, h, p, zone)
            return
        }
        if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
            // Never steal a stream while processing its CANCEL/UP.
            if (a == MotionEvent.ACTION_UP && sysPilfered) pendingMove?.let { sendStream(it, ctx, w, h, p, zone) }
            pendingMove?.recycle(); pendingMove = null
            sendStream(ev, ctx, w, h, p, zone)
            HLog.i(TAG, "RCM_030 END action=$a owned=$sysPilfered moves=$diagMoves fwd=$diagFwdMoves ${sysCorner.diag()}")
            tracks.remove(ev.downTime)
            sysPilfered = false; sysCorner.reset()
        }
    }
''' +s[b:]
s=s.replace('private const val MOVE_COALESCE_MS = 16L','private const val MOVE_COALESCE_MS = 8L')
f.write_text(s)
f=ROOT/turbo
s=f.read_text()
a=s.index('    fun feedCornerGesture(')
b=s.index('\n    private fun cornerSwipeEnabled()',a)
s=s[:a]+'''    private val cornerSession = CornerGestureSession()
    fun feedCornerGesture(ctx: Context, ev: MotionEvent, sysPilfered: Boolean = false) {
        if (!cornerInputEnabled()) { cornerSwitchMenu.dismiss(); cornerSession.finish(ev.downTime); return }
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cornerSwitchMenu.dismiss()
                cornerSeq++
                cornerSession.begin(ev.downTime)
            }
            MotionEvent.ACTION_MOVE -> {
                if (!cornerSession.isCurrent(ev.downTime)) return
                if (ev.pointerCount != 1) {
                    cornerSession.cancel(ev.downTime); cornerSwitchMenu.dismiss(); return
                }
                if (cornerSession.shouldShow(ev.downTime, sysPilfered)) {
                    HLog.i(TAG, "RCM_030 render owned gesture k#$cornerSeq")
                    StatsRecorder.onStall()
                    postShowCornerFan(ctx, ev)
                } else if (sysPilfered && cornerSwitchMenu.isShowing) cornerSwitchMenu.handle(ev)
            }
            MotionEvent.ACTION_UP -> {
                if (!cornerSession.isCurrent(ev.downTime)) return
                if (sysPilfered && cornerSwitchMenu.isShowing) cornerSwitchMenu.handle(ev)
                cornerSession.finish(ev.downTime)
                cornerSwitchMenu.dismiss()
            }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> {
                if (cornerSession.isCurrent(ev.downTime)) {
                    cornerSession.cancel(ev.downTime)
                    cornerSwitchMenu.dismiss()
                }
            }
        }
    }
''' +s[b:]
f.write_text(s)
# Boot/reload health: re-arm this module's observer without restarting the system process.
replace(spy,'    private var attempts = 0','    private var monitorEpoch = 0L\n    private var lastMotionAt = 0L\n    private var buildDownBaseline = 0L\n    private var attempts = 0')
replace(spy,'        attempts++\n        runCatching {',
        '        attempts++\n        val epoch = ++monitorEpoch\n        buildDownBaseline = totalDown\n        armed = false\n        runCatching {')
replace(spy,'                        if (event is MotionEvent) {',
        '                        if (event is MotionEvent && epoch == monitorEpoch) {\n'
        '                            lastMotionAt = android.os.SystemClock.uptimeMillis()')
replace(spy,'mainHandler.postDelayed({ deafCheck() }, DEAF_CHECK_WINDOW_MS)',
        'mainHandler.postDelayed({ if (epoch == monitorEpoch) deafCheck() }, DEAF_CHECK_WINDOW_MS)')
replace(spy,'        if (totalDown > 0) {','        if (totalDown > buildDownBaseline) {')
replace(spy,'"hypersidebar-spy-$attempts"','"rightcorner-spy-$monitorEpoch-$attempts"')
replace(spy,'    private fun refreshCornerSink() {', '''    fun ensureCornerReady(): String {
        val ctx = appContext ?: return "输入宿主上下文尚未就绪"
        com.lsp.hypersidebar.util.ConfigPullBridge.refreshIfStale(ctx, force = true)
        sinkProbeAt = 0L
        refreshCornerSink()
        val stale = lastMotionAt == 0L || android.os.SystemClock.uptimeMillis()-lastMotionAt > 5_000L
        if ((!started || stale) && tracks.isEmpty() && !takeover.active && !takeoverForwarding) {
            disposeSpy()
            started = false; armed = false; gaveUp = false; attempts = 0
            lastMotionAt = 0L
            attemptStart()
            HLog.i(TAG, "RCM_030 input observer rearmed by settings")
            return if (started) "已重新挂接手势监听" else "监听暂未建立，已安排重试"
        }
        return "手势监听正在接收输入"
    }

    private fun refreshCornerSink() {''')
replace(prefs,'    const val CORNER_SINK_PROBE_MS = 10_000L',
        '    const val CORNER_SINK_RETRY_MS = 1_000L\n    const val CORNER_SINK_PROBE_MS = 10_000L')
replace(spy,'        if (sinkProbeAt != 0L && now - sinkProbeAt < com.lsp.hypersidebar.prefs.LayoutDefaults.CORNER_SINK_PROBE_MS) return',
        '        val interval = if (sinkReadyAt == 0L) com.lsp.hypersidebar.prefs.LayoutDefaults.CORNER_SINK_RETRY_MS else com.lsp.hypersidebar.prefs.LayoutDefaults.CORNER_SINK_PROBE_MS\n'
        '        if (sinkProbeAt != 0L && now - sinkProbeAt < interval) return')
replace(spy,'            mainHandler.postDelayed(this, com.lsp.hypersidebar.prefs.LayoutDefaults.CORNER_SINK_PROBE_MS)',
        '            mainHandler.postDelayed(this, if (sinkReadyAt == 0L) com.lsp.hypersidebar.prefs.LayoutDefaults.CORNER_SINK_RETRY_MS else com.lsp.hypersidebar.prefs.LayoutDefaults.CORNER_SINK_PROBE_MS)')
replace(spy,'最近角区MOVE=$diagMoves；已抢流=$sysPilfered',
        '最近输入距今=${if (lastMotionAt == 0L) -1 else (android.os.SystemClock.uptimeMillis()-lastMotionAt)/1000}s；监听代次=$monitorEpoch；角区MOVE=$diagMoves；已抢流=$sysPilfered')
replace(hook+'SystemUiHook.kt',
        'CornerHostBridge.register(ctx, prefs, status = { SystemUiInputSpy.cornerDiagnostic() })',
        'CornerHostBridge.register(ctx, prefs, status = { SystemUiInputSpy.cornerDiagnostic() }, recover = { SystemUiInputSpy.ensureCornerReady() })')
# The module UI's explicit resume signal is bounded to its own observer and config channels.
activity='app/src/main/java/com/lsp/hypersidebar/MainActivity.kt'
replace(activity,'    override fun onDestroy() {', '''    override fun onResume() {
        super.onResume()
        val p = com.lsp.hypersidebar.util.RemotePrefsBridge.prefs ?: return
        com.lsp.hypersidebar.util.ConfigSync.notifyConfigChanged("corner settings resumed")
        val i = android.content.Intent(com.lsp.hypersidebar.prefs.PrefKeys.CORNER_DIAG_ACTION)
            .setPackage(com.lsp.hypersidebar.prefs.HostPackages.SYSTEM_UI)
            .putExtra(com.lsp.hypersidebar.prefs.PrefKeys.CORNER_DIAG_RECOVER, true)
        com.lsp.hypersidebar.util.RelayToken.attach(i, com.lsp.hypersidebar.util.RelayToken.read(p))
        runCatching { sendOrderedBroadcast(i, null) }
    }

    override fun onDestroy() {''')
# User-requested manual restart: exact SystemUI PIDs only, with failure on absent PID/root denial.
restarter='app/src/main/java/com/lsp/hypersidebar/ui/settings/HostRestarter.kt'
replace(restarter,'    private const val COMMAND_TIMEOUT_MS = 8_000L','    private const val COMMAND_TIMEOUT_MS = 15_000L')
replace(restarter,'    data class Result(val ok: List<String>, val failed: List<String>) {','    fun restartSystemUi(): Result {\n        val command = "pids=\\$(pidof ${HostPackages.SYSTEM_UI}) || exit 2; " +\n            "[ -n \\"\\$pids\\" ] || exit 3; kill -s 15 \\$pids"\n        val done = runSu(command)\n        return if (done) Result(listOf("系统界面"), emptyList())\n            else Result(emptyList(), listOf("系统界面（未获 Root 授权、进程不存在或系统拒绝）"))\n    }\n\n    data class Result(val ok: List<String>, val failed: List<String>) {')
# Keep module boot and new component logs distinguishable from the previous APK generation.
for rel in [spy,turbo,hook+'CornerHostBridge.kt','app/src/main/java/com/lsp/hypersidebar/XposedInit.kt']:
    f=ROOT/rel
    f.write_text(f.read_text().replace('RCM_020','RCM_030'))
print('V0.3 applied: early single judge, observer recovery, floating arc and manual SystemUI restart')
shutil.copyfile(SRC/'CornerOrbitGeometry.kt',ROOT/hook/'CornerOrbitGeometry.kt')
shutil.copyfile(SRC.parent/'tests/CornerOrbitGeometryTest.kt',ROOT/'app/src/test/java/com/lsp/hypersidebar/hook/CornerOrbitGeometryTest.kt')
