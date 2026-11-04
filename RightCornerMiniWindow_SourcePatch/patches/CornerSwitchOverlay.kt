package com.lsp.hypersidebar.hook

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import com.lsp.hypersidebar.prefs.PrefKeys
import com.lsp.hypersidebar.util.DataLoader
import com.lsp.hypersidebar.util.FreeformLauncher
import com.lsp.hypersidebar.util.HLog
import org.json.JSONArray
import kotlin.math.min
import kotlin.math.cos
import kotlin.math.sin

/**
 * HyperSideBar-derived OS4 corner-mode replacement.
 * The system gesture pipeline still owns the touch stream. This visual window
 * is NOT_TOUCHABLE: it cannot steal Home/back gestures or create an extra click.
 * All coordinates arrive through TurboLayout.feedCornerGesture().
 */
class CornerSwitchOverlay(private val prefs: SharedPreferences) {
    private var wm: WindowManager? = null
    private var screen: CornerVisual? = null
    private var ctx: Context? = null
    private val handler = Handler(Looper.getMainLooper())
    private val timeout = Runnable { dismiss() }
    private var suggestions: List<String> = emptyList()
    private var favorites: List<String> = emptyList()
    val isShowing: Boolean get() = screen != null

    private fun customApps(): List<String> {
        val pinned = prefs.getStringSet(PrefKeys.CUSTOM_APPS, emptySet()) ?: emptySet()
        val order = runCatching {
            val array = JSONArray(prefs.getString(PrefKeys.CUSTOM_APPS_ORDER, "[]") ?: "[]")
            (0 until array.length()).map { array.getString(it) }
        }.getOrElse { emptyList() }
        val positions = order.withIndex().associate { it.value to it.index }
        return pinned.sortedWith(compareBy<String> { positions[it] ?: Int.MAX_VALUE }.thenBy { it })
            .filter { it.isNotBlank() }
            .take(5)
    }

    var lastError: String? = null
        private set
    private var previewOnly = false

    fun show(context: Context, source: String?, preview: Boolean = false): Boolean {
        if (screen != null) return false
        lastError = null
        previewOnly = preview
        favorites = customApps()
        suggestions = DataLoader.rankedApps(context, source).asSequence()
            .filter { it.isNotBlank() }.distinct().take(5).toList()
        // Keep upstream's 2038 WindowManager route; a visual context supplies real display bounds.
        // On system-uid hosts only, try the legacy system window if AppOps rejects 2038.
        val types = if (android.os.Process.myUid() == 1000) listOf(
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
        ) else listOf(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        for (type in types) {
            var manager: WindowManager? = null
            var visual: CornerVisual? = null
            try {
                val display = context.getSystemService(android.hardware.display.DisplayManager::class.java)
                    ?.getDisplay(android.view.Display.DEFAULT_DISPLAY) ?: error("Primary display unavailable")
                val windowContext = context.createWindowContext(display, type, null)
                val activeManager = windowContext.getSystemService(WindowManager::class.java) ?: error("WindowManager unavailable")
                manager = activeManager
                val activeVisual = CornerVisual(windowContext, suggestions, favorites,
                    haptic = prefs.getBoolean(PrefKeys.CORNER_HAPTIC, true),
                    onRotation = { dismiss() })
                visual = activeVisual
                val lp = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT, type,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                    android.graphics.PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = android.view.Gravity.TOP or android.view.Gravity.START
                    title = "RightCornerMiniWindow"
                    setFitInsetsTypes(0)
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                    // Below Android's maximum untrusted overlay opacity; the window never owns input.
                    alpha = 0.8f
                }
                activeManager.addView(activeVisual, lp)
                ctx = windowContext
                wm = activeManager
                screen = activeVisual
                armTimeout()
                lastError = null
                HLog.i("CornerSwitch", "RCM_020 menu shown type=$type preview=$preview suggestions=${suggestions.size} favorites=${favorites.size}")
                return true
            } catch (failure: Throwable) {
                if (visual != null) runCatching { manager?.removeViewImmediate(visual) }
                lastError = "type=$type ${failure.javaClass.simpleName}: ${failure.message}"
                HLog.w("CornerSwitch", "window creation failed: $lastError")
            }
        }
        dismiss()
        return false
    }

    fun test(context: Context): String {
        if (isShowing) return "已有手势菜单；结束本次手势后重试"
        val shown = show(context, null, preview = true)
        if (shown) {
            handler.removeCallbacks(timeout)
            handler.postDelayed(timeout, 5_000L)
        }
        return if (shown) "菜单窗口已创建（5 秒后关闭；预览不会启动应用）" else "菜单窗口创建失败：$lastError"
    }

    private fun armTimeout() {
        handler.removeCallbacks(timeout)
        handler.postDelayed(timeout, 8_000L)
    }

    fun handle(event: MotionEvent) {
        val v = screen ?: return
        if (previewOnly) return
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                v.move(event.rawX, event.rawY)
                armTimeout()
            }
            MotionEvent.ACTION_UP -> {
                v.move(event.rawX, event.rawY)
                val selected = v.release(event.rawX, event.rawY)
                val pkg = when (selected?.page) {
                    CornerMenuEngine.Page.SUGGESTIONS -> suggestions.getOrNull(selected.index)
                    CornerMenuEngine.Page.FAVORITES -> favorites.getOrNull(selected.index)
                    null -> null
                }
                val context = ctx
                dismiss()
                if (pkg != null && context != null) {
                    // Uses original MiuiMultiWindowUtils.getActivityOptions / system-uid bridge.
                    // Never fall back to full-screen launching.
                    runCatching { FreeformLauncher.launch(context, pkg) }
                        .onFailure { HLog.w("CornerSwitch", "freeform launch failed: ${it.message}") }
                }
            }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> dismiss()
        }
    }

    fun dismiss() {
        handler.removeCallbacks(timeout)
        screen?.let { runCatching { wm?.removeViewImmediate(it) } }
        screen = null
        wm = null
        ctx = null
        suggestions = emptyList()
        favorites = emptyList()
        previewOnly = false
    }

    private class CornerVisual(
        context: Context,
        private val suggestions: List<String>,
        private val favorites: List<String>,
        private val haptic: Boolean,
        private val onRotation: () -> Unit
    ) : View(context) {
        private val display = CornerRuntime.metrics(context)
        private val density = display.density
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val iconCache = HashMap<String, Drawable?>()
        private val windowLocation = IntArray(2)
        // Engine uses raw screen coordinates. The overlay may be inset by SystemUI;
        // translating during draw only avoids page resets before its first layout.
        private val engine = CornerMenuEngine(
            radius = min(226f * density, display.widthPixels * 0.68f),
            hitRadius = 36f * density,
            anchorX = display.widthPixels.toFloat(),
            anchorY = display.heightPixels.toFloat()
        )
        private val iconRadius = 21f * density

        fun move(rawX: Float, rawY: Float) {
            val e = engine
            val oldPageVersion = e.pageVersion
            val previous = e.highlighted
            val changed = e.move(rawX, rawY)
            if (changed) {
                if (haptic && (oldPageVersion != e.pageVersion || e.highlighted != previous && e.highlighted >= 0)) {
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK,
                        HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING)
                }
                invalidate()
            }
        }

        fun release(rawX: Float, rawY: Float): CornerMenuEngine.Chosen? {
            return engine.release(rawX, rawY)
        }

        override fun onConfigurationChanged(newConfig: Configuration) {
            super.onConfigurationChanged(newConfig)
            onRotation()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            getLocationOnScreen(windowLocation)
            val e = engine
            val slots = if (e.page == CornerMenuEngine.Page.SUGGESTIONS) suggestions else favorites
            val r = min(226f * density, display.widthPixels * 0.68f)
            // Continuous quarter-annulus, not a rectangular toolbar or popup.
            val centerX = display.widthPixels - windowLocation[0].toFloat()
            val centerY = display.heightPixels - windowLocation[1].toFloat()
            val inner = r - 36f * density
            val outer = r + 37f * density
            val wedge = Path().apply {
                val steps = 48
                for (i in 0..steps) {
                    val angle = Math.toRadians((10.0 + 72.0 * i / steps))
                    val x = centerX - outer * cos(angle).toFloat()
                    val y = centerY - outer * sin(angle).toFloat()
                    if (i == 0) moveTo(x, y) else lineTo(x, y)
                }
                for (i in steps downTo 0) {
                    val angle = Math.toRadians((10.0 + 72.0 * i / steps))
                    lineTo(centerX - inner * cos(angle).toFloat(), centerY - inner * sin(angle).toFloat())
                }
                close()
            }
            paint.style = Paint.Style.FILL
            paint.color = Color.argb(224, 32, 36, 44)
            canvas.drawPath(wedge, paint)

            for (i in 0..5) {
                val (rawCenterX, rawCenterY) = e.center(i)
                val x = rawCenterX - windowLocation[0]
                val y = rawCenterY - windowLocation[1]
                val selected = e.highlighted == i
                paint.color = if (selected) Color.rgb(74, 155, 255)
                    else Color.argb(120, 255, 255, 255)
                canvas.drawCircle(x, y, if (selected) 27f * density else 24f * density, paint)
                if (i == 5) drawPortal(canvas, x, y, e.page) else {
                    val pkg = slots.getOrNull(i)
                    if (pkg != null) drawIcon(canvas, pkg, x, y)
                    else drawEmpty(canvas, x, y)
                }
            }
            paint.color = Color.WHITE
            paint.textSize = 12f * density
            paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            val label = if (e.page == CornerMenuEngine.Page.SUGGESTIONS) "常用" else "收藏"
            canvas.drawText(label, centerX - 150f * density, centerY - 40f * density, paint)
        }

        private fun drawEmpty(canvas: Canvas, x: Float, y: Float) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2f * density
            paint.color = Color.argb(160, 255, 255, 255)
            canvas.drawCircle(x, y, 8f * density, paint)
            paint.style = Paint.Style.FILL
        }

        private fun drawIcon(canvas: Canvas, pkg: String, cx: Float, cy: Float) {
            val drawable = iconCache.getOrPut(pkg) {
                runCatching { context.packageManager.getApplicationIcon(pkg).mutate() }.getOrNull()
            } ?: return drawEmpty(canvas, cx, cy)
            val half = iconRadius.toInt()
            val b = drawable.bounds
            drawable.setBounds(cx.toInt() - half, cy.toInt() - half, cx.toInt() + half, cy.toInt() + half)
            drawable.draw(canvas)
            drawable.bounds = b
        }

        private fun drawPortal(canvas: Canvas, x: Float, y: Float, page: CornerMenuEngine.Page) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2.5f * density
            paint.strokeCap = Paint.Cap.ROUND
            paint.strokeJoin = Paint.Join.ROUND
            paint.color = Color.WHITE
            if (page == CornerMenuEngine.Page.SUGGESTIONS) {
                val t = 6f * density
                for (row in 0..1) for (col in 0..1) {
                    val l = x + (col - 1) * t * 1.35f + 1f * density
                    val top = y + (row - 1) * t * 1.35f + 1f * density
                    canvas.drawRoundRect(RectF(l, top, l + t, top + t), 1f * density, 1f * density, paint)
                }
            } else {
                val arrow = Path().apply {
                    moveTo(x - 8f * density, y)
                    lineTo(x + 8f * density, y)
                    moveTo(x - 8f * density, y)
                    lineTo(x - 1f * density, y - 7f * density)
                    moveTo(x - 8f * density, y)
                    lineTo(x - 1f * density, y + 7f * density)
                }
                canvas.drawPath(arrow, paint)
            }
            paint.style = Paint.Style.FILL
        }
    }
}
