package com.lsp.hypersidebar.hook

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.RadialGradient
import android.graphics.LinearGradient
import android.graphics.Shader
import android.graphics.Rect
import android.os.SystemClock
import android.animation.ValueAnimator
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
                    flags = flags or WindowManager.LayoutParams.FLAG_DIM_BEHIND
                    val blurEnabled = runCatching { activeManager.isCrossWindowBlurEnabled }.getOrDefault(false)
                    dimAmount = if (blurEnabled) 0.08f else 0.18f
                    if (blurEnabled) {
                        flags = flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
                        blurBehindRadius = (24f * windowContext.resources.displayMetrics.density).toInt().coerceAtMost(80)
                    }
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
        private val labelCache = HashMap<String, String>()
        private val location = IntArray(2)
        private val radius = CornerOrbitGeometry.radius(display.widthPixels.toFloat(), display.heightPixels.toFloat(), density)
        private val iconRadius = CornerOrbitGeometry.iconRadius(radius, density)
        private val engine = CornerMenuEngine(radius, CornerOrbitGeometry.hitRadius(radius,density),
            display.widthPixels.toFloat(), display.heightPixels.toFloat())
        private val selected = FloatArray(6)
        private val created = SystemClock.uptimeMillis()
        private var previousFrame = created
        private var pageChangedAt = 0L
        private var previousPage = engine.page
        private val animate = runCatching { ValueAnimator.areAnimatorsEnabled() }.getOrDefault(true)
        private val clip = Path()

        fun move(rawX: Float, rawY: Float) {
            val oldPage = engine.page
            val oldIndex = engine.highlighted
            if (engine.move(rawX,rawY)) {
                if (oldPage != engine.page) { previousPage = oldPage; pageChangedAt = SystemClock.uptimeMillis() }
                if (haptic && (oldPage != engine.page || oldIndex != engine.highlighted && engine.highlighted >= 0)) {
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK,
                        HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING)
                }
                postInvalidateOnAnimation()
            }
        }

        fun release(rawX: Float, rawY: Float): CornerMenuEngine.Chosen? = engine.release(rawX,rawY)

        override fun onConfigurationChanged(newConfig: Configuration) {
            super.onConfigurationChanged(newConfig)
            onRotation()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            getLocationOnScreen(location)
            val now=SystemClock.uptimeMillis()
            val dt=(now-previousFrame).coerceAtMost(40L).toFloat()
            previousFrame=now
            val enter=if (animate) ((now-created)/160f).coerceIn(0f,1f) else 1f
            val pageT=if (animate && pageChangedAt>0) ((now-pageChangedAt)/120f).coerceIn(0f,1f) else 1f
            val ax=display.widthPixels-location[0].toFloat()
            val ay=display.heightPixels-location[1].toFloat()
            paint.shader=RadialGradient(ax,ay,radius*1.85f,
                intArrayOf(Color.argb(54,18,28,42),Color.argb(12,18,28,42),Color.TRANSPARENT),
                floatArrayOf(0f,0.62f,1f),Shader.TileMode.CLAMP)
            canvas.drawRect(0f,0f,width.toFloat(),height.toFloat(),paint)
            paint.shader=null
            val save=canvas.saveLayerAlpha(0f,0f,width.toFloat(),height.toFloat(),(enter*255).toInt())
            val growth=0.94f+0.06f*(1f-(1f-enter)*(1f-enter))
            canvas.scale(growth,growth,ax,ay)
            var animating=enter<1f || pageT<1f
            for (i in 0..5) {
                val target=if (engine.highlighted==i) 1f else 0f
                selected[i]=if (animate) selected[i]+(target-selected[i])*(dt/70f).coerceAtMost(1f) else target
                if (kotlin.math.abs(selected[i]-target)>0.005f) animating=true
                val (rawX,rawY)=engine.center(i)
                val x=rawX-location[0]; val y=rawY-location[1]
                val iconSave=canvas.save()
                val scale=1f+0.13f*selected[i]
                canvas.scale(scale,scale,x,y)
                paint.style=Paint.Style.FILL
                paint.color=Color.argb(28,10,16,28)
                paint.setShadowLayer(7f*density,0f,3f*density,Color.argb(80,0,0,0))
                canvas.drawCircle(x,y,iconRadius,paint)
                paint.clearShadowLayer()
                if (selected[i]>0.01f) {
                    paint.style=Paint.Style.STROKE
                    paint.strokeWidth=2f*density
                    paint.color=Color.argb((220*selected[i]).toInt(),255,255,255)
                    canvas.drawCircle(x,y,iconRadius+5f*density,paint)
                    paint.style=Paint.Style.FILL
                }
                if (i==5) drawPortal(canvas,x,y) else {
                    if (pageT<1f) drawSlot(canvas,previousPage,i,x,y,1f-pageT)
                    drawSlot(canvas,engine.page,i,x,y,pageT)
                }
                canvas.restoreToCount(iconSave)
            }
            engine.highlighted.takeIf { it in 0..4 }?.let { index ->
                val pkg=slots(engine.page).getOrNull(index)
                if (pkg!=null) {
                    val label=labelCache.getOrPut(pkg) { runCatching {
                        val pm=context.packageManager
                        pm.getApplicationLabel(pm.getApplicationInfo(pkg,0)).toString()
                    }.getOrDefault(pkg.substringAfterLast('.')) }
                    drawLabel(canvas,label,ax-88f*density,ay-64f*density)
                }
            }
            if (engine.highlighted !in 0..4) {
                drawLabel(canvas,if (engine.page==CornerMenuEngine.Page.SUGGESTIONS) "常用" else "收藏",
                    ax-88f*density,ay-64f*density,subtle=true)
            }
            canvas.restoreToCount(save)
            if (animating) postInvalidateOnAnimation()
        }

        private fun slots(page: CornerMenuEngine.Page)=if (page==CornerMenuEngine.Page.SUGGESTIONS) suggestions else favorites

        private fun drawSlot(canvas: Canvas,page: CornerMenuEngine.Page,index: Int,x: Float,y: Float,alpha: Float) {
            val pkg=slots(page).getOrNull(index)
            if (pkg==null) {
                paint.color=Color.argb((60*alpha).toInt(),255,255,255)
                paint.style=Paint.Style.STROKE; paint.strokeWidth=1f*density
                canvas.drawCircle(x,y,iconRadius*0.65f,paint)
                paint.style=Paint.Style.FILL
                return
            }
            val drawable=iconCache.getOrPut(pkg) { runCatching { context.packageManager.getApplicationIcon(pkg).mutate() }.getOrNull() } ?: return
            val oldBounds=Rect(drawable.bounds)
            val oldAlpha=drawable.alpha
            val count=canvas.save()
            clip.reset(); clip.addCircle(x,y,iconRadius,Path.Direction.CW)
            canvas.clipPath(clip)
            drawable.setBounds((x-iconRadius).toInt(),(y-iconRadius).toInt(),(x+iconRadius).toInt(),(y+iconRadius).toInt())
            drawable.alpha=(255*alpha).toInt()
            drawable.draw(canvas)
            drawable.bounds=oldBounds; drawable.alpha=oldAlpha
            canvas.restoreToCount(count)
        }

        private fun drawPortal(canvas: Canvas,x: Float,y: Float) {
            paint.style=Paint.Style.FILL
            paint.shader=LinearGradient(x,y-iconRadius,x,y+iconRadius,
                Color.rgb(72,168,255),Color.rgb(25,119,247),Shader.TileMode.CLAMP)
            canvas.drawCircle(x,y,iconRadius,paint)
            paint.shader=null; paint.color=Color.WHITE
            if (engine.page==CornerMenuEngine.Page.SUGGESTIONS) {
                for (offset in -1..1) canvas.drawCircle(x+offset*7.5f*density,y,2f*density,paint)
            } else {
                paint.style=Paint.Style.STROKE; paint.strokeWidth=2.5f*density
                paint.strokeCap=Paint.Cap.ROUND; paint.strokeJoin=Paint.Join.ROUND
                val arrow=Path().apply {
                    moveTo(x+8f*density,y); lineTo(x-8f*density,y)
                    moveTo(x-8f*density,y); lineTo(x-1f*density,y-7f*density)
                    moveTo(x-8f*density,y); lineTo(x-1f*density,y+7f*density)
                }
                canvas.drawPath(arrow,paint)
                paint.style=Paint.Style.FILL
            }
        }

        private fun drawLabel(canvas: Canvas,text: String,x: Float,y: Float,subtle: Boolean=false) {
            paint.typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL)
            paint.textSize=11f*density
            val short=if (text.length>12) text.take(11)+"…" else text
            val half=paint.measureText(short)/2f+12f*density
            val cx=x.coerceIn(half+12f*density,width-half-12f*density)
            val cy=y.coerceAtMost(height-20f*density)
            if (!subtle) {
                paint.color=Color.argb(110,28,34,44)
                canvas.drawRoundRect(RectF(cx-half,cy-16f*density,cx+half,cy+7f*density),12f*density,12f*density,paint)
            }
            paint.color=Color.argb(if(subtle) 215 else 255,255,255,255)
            paint.setShadowLayer(3f*density,0f,density,Color.argb(140,0,0,0))
            canvas.drawText(short,cx-paint.measureText(short)/2f,cy,paint)
            paint.clearShadowLayer()
        }
    }
}
