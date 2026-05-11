package com.telenav.netmonitor

import android.content.Context
import android.graphics.PixelFormat
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import com.telenav.netmonitor.view.BubbleView
import com.telenav.netmonitor.view.FloatingWindowView
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class FloatingWindowManager(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val floatingView = FloatingWindowView(context)
    private val bubbleView = BubbleView(context)
    private var isExpanded = true

    private val density = context.resources.displayMetrics.density
    private val screenWidth = context.resources.displayMetrics.widthPixels
    private val screenHeight = context.resources.displayMetrics.heightPixels
    private val tapSlopSq = ViewConfiguration.get(context).scaledTouchSlop.let { it * it }

    private val expandedWidth: Int
    private val expandedHeight: Int

    init {
        // Card occupies ~1/4 of screen area → sqrt(0.25) = 0.5 of each side.
        val edgeFactor = sqrt(NetMonitorConfig.expandedAreaFraction).coerceIn(0.1, 1.0)
        expandedWidth = (screenWidth * edgeFactor).toInt().clampDp(
            NetMonitorConfig.expandedMinWidthDp, NetMonitorConfig.expandedMaxWidthDp
        )
        expandedHeight = (screenHeight * edgeFactor).toInt().clampDp(
            NetMonitorConfig.expandedMinHeightDp, NetMonitorConfig.expandedMaxHeightDp
        )
        floatingView.onCloseClick = ::switchToBubble
    }

    private fun Int.clampDp(minDp: Int, maxDp: Int): Int {
        val minPx = (minDp * density).toInt()
        val maxPx = (maxDp * density).toInt()
        return min(max(this, minPx), maxPx)
    }

    private fun makeParams(width: Int, height: Int) = WindowManager.LayoutParams(
        width, height,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT
    ).apply {
        // start near bottom-right corner with a small margin
        val marginPx = (12 * density).toInt()
        x = max(0, screenWidth - width - marginPx)
        y = max(0, screenHeight - height - marginPx)
    }

    fun show() {
        if (floatingView.isAttachedToWindow) return
        val params = makeParams(expandedWidth, expandedHeight)
        attachDrag(floatingView, params)
        tryAddView(floatingView, params)
        isExpanded = true
    }

    fun hide() {
        tryRemoveView(floatingView)
        tryRemoveView(bubbleView)
    }

    fun updateData(data: NetDataRepository.AggregatedData) {
        if (isExpanded) floatingView.update(data)
    }

    private fun switchToBubble() {
        val prevParams = floatingView.layoutParams as? WindowManager.LayoutParams
            ?: makeParams(expandedWidth, expandedHeight)
        // 60dp bubble — matches the enlarged expanded card and stays tappable
        // on downscaled OEM dashboards.
        val sizePx = (60 * density).toInt()
        val params = makeParams(sizePx, sizePx).apply {
            x = prevParams.x
            y = prevParams.y
        }
        bubbleView.onExpandClick = { switchToExpanded(params) }
        attachDrag(bubbleView, params)
        tryRemoveView(floatingView)
        tryAddView(bubbleView, params)
        isExpanded = false
    }

    private fun switchToExpanded(prevParams: WindowManager.LayoutParams) {
        val params = makeParams(expandedWidth, expandedHeight).apply {
            x = prevParams.x
            y = prevParams.y
        }
        attachDrag(floatingView, params)
        tryRemoveView(bubbleView)
        tryAddView(floatingView, params)
        isExpanded = true
    }

    private fun tryAddView(view: View, params: WindowManager.LayoutParams) {
        try { windowManager.addView(view, params) } catch (t: Throwable) {
            NMLog.w(NMSub.WINDOW, "addView failed: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun tryRemoveView(view: View) {
        try { if (view.isAttachedToWindow) windowManager.removeView(view) } catch (t: Throwable) {
            NMLog.w(NMSub.WINDOW, "removeView failed: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun attachDrag(view: View, params: WindowManager.LayoutParams) {
        var downX = 0; var downY = 0
        var downRawX = 0f; var downRawY = 0f

        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = params.x; downY = params.y
                    downRawX = event.rawX; downRawY = event.rawY
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = downX + (event.rawX - downRawX).toInt()
                    params.y = downY + (event.rawY - downRawY).toInt()
                    try { windowManager.updateViewLayout(view, params) } catch (_: Throwable) {}
                    true
                }
                MotionEvent.ACTION_UP -> {
                    // snap to nearest horizontal edge
                    params.x = if (params.x + view.width / 2 < screenWidth / 2) 0
                    else screenWidth - view.width
                    try { windowManager.updateViewLayout(view, params) } catch (_: Throwable) {}
                    val dx = (event.rawX - downRawX).toInt()
                    val dy = (event.rawY - downRawY).toInt()
                    if (dx * dx + dy * dy < tapSlopSq) view.performClick()
                    true
                }
                else -> false
            }
        }
    }

}
