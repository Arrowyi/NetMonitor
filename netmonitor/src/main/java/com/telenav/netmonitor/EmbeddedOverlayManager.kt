package com.telenav.netmonitor

import android.app.Activity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import com.telenav.netmonitor.view.BubbleView
import com.telenav.netmonitor.view.FloatingWindowView
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Hosts the NetMonitor panel / bubble inside the host Activity's DecorView.
 * No system overlay window is used, so SYSTEM_ALERT_WINDOW is not required.
 *
 * Process-wide singleton. View instances are created against the Activity
 * context on [attach] and dropped on [detach]; only bubble position and
 * expanded-vs-collapsed state are preserved across Activity recreations.
 *
 * All entry points must be called on the main thread.
 */
internal object EmbeddedOverlayManager {

    private var attachedActivity: Activity? = null
    private var panelView: FloatingWindowView? = null
    private var bubbleView: BubbleView? = null
    private var isExpanded = true

    // Translation in parent-coordinate space. NaN ⇒ use default (bottom-right corner).
    private var savedX: Float = Float.NaN
    private var savedY: Float = Float.NaN

    private var latestData: NetDataRepository.AggregatedData? = null

    fun attach(activity: Activity) {
        if (attachedActivity === activity) return
        if (attachedActivity != null) detach()
        attachedActivity = activity
        val parent = activity.window.decorView as ViewGroup
        if (isExpanded) addPanel(activity, parent) else addBubble(activity, parent)
    }

    fun detach() {
        rememberPosition()
        removeAllViews()
        attachedActivity = null
    }

    fun updateData(data: NetDataRepository.AggregatedData) {
        latestData = data
        if (isExpanded) panelView?.update(data)
    }

    private fun currentView(): View? = if (isExpanded) panelView else bubbleView

    private fun rememberPosition() {
        currentView()?.let {
            if (it.isLaidOut) {
                savedX = it.translationX
                savedY = it.translationY
            }
        }
    }

    private fun removeAllViews() {
        panelView?.let { (it.parent as? ViewGroup)?.removeView(it) }
        bubbleView?.let { (it.parent as? ViewGroup)?.removeView(it) }
        panelView = null
        bubbleView = null
    }

    private fun addPanel(activity: Activity, parent: ViewGroup) {
        val density = activity.resources.displayMetrics.density
        val screenW = activity.resources.displayMetrics.widthPixels
        val screenH = activity.resources.displayMetrics.heightPixels
        // Card occupies ~expandedAreaFraction of screen area → sqrt → per-side factor.
        val edgeFactor = sqrt(NetMonitorConfig.expandedAreaFraction).coerceIn(0.1, 1.0)
        val w = (screenW * edgeFactor).toInt()
            .clampDp(density, NetMonitorConfig.expandedMinWidthDp, NetMonitorConfig.expandedMaxWidthDp)
        val h = (screenH * edgeFactor).toInt()
            .clampDp(density, NetMonitorConfig.expandedMinHeightDp, NetMonitorConfig.expandedMaxHeightDp)

        val view = FloatingWindowView(activity).also { it.onCloseClick = ::switchToBubble }
        panelView = view
        parent.addView(view, FrameLayout.LayoutParams(w, h))
        attachDrag(view)
        positionWhenLaidOut(view, w, h, density)
        latestData?.let { view.update(it) }
    }

    private fun addBubble(activity: Activity, parent: ViewGroup) {
        val density = activity.resources.displayMetrics.density
        // 60dp bubble — matches the original tappable target on downscaled OEM dashboards.
        val sizePx = (60 * density).toInt()
        val view = BubbleView(activity).also { it.onExpandClick = ::switchToExpanded }
        bubbleView = view
        parent.addView(view, FrameLayout.LayoutParams(sizePx, sizePx))
        attachDrag(view)
        positionWhenLaidOut(view, sizePx, sizePx, density)
    }

    private fun positionWhenLaidOut(view: View, w: Int, h: Int, density: Float) {
        val parent = view.parent as ViewGroup
        val place = Runnable {
            val marginPx = 12f * density
            val (x, y) = if (savedX.isNaN() || savedY.isNaN()) {
                max(0f, parent.width - w - marginPx) to
                    max(0f, parent.height - h - marginPx)
            } else {
                savedX.coerceIn(0f, max(0f, parent.width - w.toFloat())) to
                    savedY.coerceIn(0f, max(0f, parent.height - h.toFloat()))
            }
            view.translationX = x
            view.translationY = y
        }
        if (parent.width > 0 && parent.height > 0) {
            place.run()
        } else {
            parent.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
                override fun onLayoutChange(
                    v: View, l: Int, t: Int, r: Int, b: Int,
                    oL: Int, oT: Int, oR: Int, oB: Int
                ) {
                    v.removeOnLayoutChangeListener(this)
                    place.run()
                }
            })
        }
    }

    private fun attachDrag(view: View) {
        val tapSlopSq = ViewConfiguration.get(view.context).scaledTouchSlop.let { it * it }
        var downTransX = 0f
        var downTransY = 0f
        var downRawX = 0f
        var downRawY = 0f

        view.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downTransX = v.translationX
                    downTransY = v.translationY
                    downRawX = event.rawX
                    downRawY = event.rawY
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    v.translationX = downTransX + (event.rawX - downRawX)
                    v.translationY = downTransY + (event.rawY - downRawY)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    (v.parent as? ViewGroup)?.let { parent ->
                        v.translationY = v.translationY.coerceIn(
                            0f, max(0f, parent.height - v.height.toFloat())
                        )
                        val midX = v.translationX + v.width / 2f
                        val snappedX = if (midX < parent.width / 2f) 0f
                        else max(0f, parent.width - v.width.toFloat())
                        v.animate().translationX(snappedX).setDuration(120).start()
                        savedX = snappedX
                        savedY = v.translationY
                    }
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (dx * dx + dy * dy < tapSlopSq) v.performClick()
                    true
                }
                else -> false
            }
        }
    }

    private fun switchToBubble() {
        val activity = attachedActivity ?: return
        rememberPosition()
        isExpanded = false
        removeAllViews()
        addBubble(activity, activity.window.decorView as ViewGroup)
    }

    private fun switchToExpanded() {
        val activity = attachedActivity ?: return
        rememberPosition()
        isExpanded = true
        removeAllViews()
        addPanel(activity, activity.window.decorView as ViewGroup)
    }

    private fun Int.clampDp(density: Float, minDp: Int, maxDp: Int): Int {
        val minPx = (minDp * density).toInt()
        val maxPx = (maxDp * density).toInt()
        return min(max(this, minPx), maxPx)
    }
}
