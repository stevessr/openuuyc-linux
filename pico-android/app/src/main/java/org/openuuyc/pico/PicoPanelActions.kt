package org.openuuyc.pico

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.PopupMenu

/** A single row of actions, with a trailing overflow menu. Dimensions are pixels.
 * Registered buttons belong to this container, which owns their visibility.
 */
internal class PicoPanelActions(context: Context) : ViewGroup(context) {
    private val actions = mutableListOf<Button>()
    private var visibleCount = 0
    private var overflow: PopupMenu? = null
    private data class AnchorState(val button: Button, val alpha: Float, val focusable: Int, val accessibility: Int)
    private var activeAnchor: AnchorState? = null

    private fun restoreAnchor() {
        activeAnchor?.let {
            it.button.alpha = it.alpha
            it.button.setFocusable(it.focusable)
            it.button.importantForAccessibility = it.accessibility
            it.button.visibility = if (actions.indexOf(it.button) < visibleCount) VISIBLE else INVISIBLE
        }
        activeAnchor = null
    }

    private fun prepareAnchor(button: Button) {
        restoreAnchor()
        if (actions.indexOf(button) < visibleCount) return
        activeAnchor = AnchorState(button, button.alpha, button.getFocusable(), button.importantForAccessibility)
        // Nested PopupMenu also requires isShown(), unlike PopupWindow. Keep its anchor
        // transparent but VISIBLE, out of keyboard/accessibility traversal, below "more".
        button.alpha = 0f
        button.isFocusable = false
        button.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        button.visibility = VISIBLE
    }
    private val more = Button(context).apply {
        text = "更多"
        contentDescription = "更多操作"
        isAllCaps = false
        textSize = 14f
        setTextColor(PicoTheme.TEXT)
        minWidth = 0
        minimumWidth = 0
        minHeight = 0
        minimumHeight = 0
        setPadding(dp(8), 0, dp(8), 0)
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), box(PicoTheme.HOVER))
            addState(intArrayOf(android.R.attr.state_focused), box(PicoTheme.SELECTED, PicoTheme.ACCENT))
            addState(intArrayOf(), box(PicoTheme.SURFACE))
        }
        setOnClickListener { showOverflow() }
    }

    init {
        addView(more, LayoutParams(dp(64), dp(48)))
        more.visibility = GONE
    }

    /** Register an unattached existing button without replacing its click listener. */
    fun addAction(button: Button, width: Int, height: Int) {
        require(button.parent == null) { "按钮已属于其他容器" }
        require(width >= LayoutParams.WRAP_CONTENT && height >= LayoutParams.WRAP_CONTENT)
        actions.add(button)
        addView(button, childCount - 1, LayoutParams(width, height))
        overflow?.dismiss()
        requestLayout()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + .5f).toInt()

    private fun box(color: Int, stroke: Int = PicoTheme.LINE) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(PicoTheme.CONTROL_RADIUS).toFloat()
        setStroke(dp(1).coerceAtLeast(1), stroke)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val widthMode = MeasureSpec.getMode(widthMeasureSpec)
        val available = if (widthMode == MeasureSpec.UNSPECIFIED) Int.MAX_VALUE else
            (MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight).coerceAtLeast(0)
        var totalWidth = 0L
        var rowHeight = 0
        // Measure even INVISIBLE buttons at their registered size before choosing a prefix.
        actions.forEach { button ->
            val params = button.layoutParams
            button.measure(
                getChildMeasureSpec(widthMeasureSpec, paddingLeft + paddingRight, params.width),
                getChildMeasureSpec(heightMeasureSpec, paddingTop + paddingBottom, params.height)
            )
            totalWidth += button.measuredWidth
            rowHeight = maxOf(rowHeight, button.measuredHeight)
        }
        val moreWidth = minOf(dp(64), available)
        val nextCount = if (totalWidth <= available) actions.size else {
            var used = 0L
            var count = 0
            for (button in actions) {
                if (used + button.measuredWidth > available - moreWidth) break
                used += button.measuredWidth
                count++
            }
            count
        }
        if (nextCount != visibleCount) overflow?.dismiss()
        visibleCount = nextCount
        if (activeAnchor?.button?.let { actions.indexOf(it) < visibleCount } == true) restoreAnchor()
        val hasOverflow = visibleCount < actions.size
        more.visibility = if (hasOverflow) VISIBLE else GONE
        if (hasOverflow) rowHeight = maxOf(rowHeight, dp(48))
        val desiredWidth = (totalWidth + paddingLeft + paddingRight).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        setMeasuredDimension(
            resolveSize(maxOf(suggestedMinimumWidth, desiredWidth), widthMeasureSpec),
            resolveSize(maxOf(suggestedMinimumHeight, rowHeight + paddingTop + paddingBottom), heightMeasureSpec)
        )
        val anchorHeight = minOf(dp(48), (measuredHeight - paddingTop - paddingBottom).coerceAtLeast(0))
        more.measure(
            MeasureSpec.makeMeasureSpec(moreWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(anchorHeight, MeasureSpec.EXACTLY)
        )
        actions.forEachIndexed { index, button ->
            button.visibility = if (index < visibleCount || activeAnchor?.button === button) VISIBLE else INVISIBLE
            if (index >= visibleCount) {
                // Keep a real, bounded window anchor at the overflow button's location.
                // GONE/offscreen/zero-size anchors can break existing PopupWindow actions.
                button.measure(
                    MeasureSpec.makeMeasureSpec(moreWidth, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(anchorHeight, MeasureSpec.EXACTLY)
                )
            }
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        if (changed) overflow?.dismiss()
        val rtl = layoutDirection == View.LAYOUT_DIRECTION_RTL
        var cursor = if (rtl) width - paddingRight else paddingLeft
        val rowHeight = (height - paddingTop - paddingBottom).coerceAtLeast(0)
        fun place(view: View) {
            val x = if (rtl) cursor - view.measuredWidth else cursor
            val y = paddingTop + (rowHeight - view.measuredHeight).coerceAtLeast(0) / 2
            view.layout(x, y, x + view.measuredWidth, y + view.measuredHeight)
            cursor += if (rtl) -view.measuredWidth else view.measuredWidth
        }
        for (index in 0 until visibleCount) place(actions[index])
        if (visibleCount < actions.size) {
            place(more)
            for (index in visibleCount until actions.size) {
                actions[index].layout(more.left, more.top, more.right, more.bottom)
            }
        } else {
            more.layout(0, 0, 0, 0)
        }
    }

    private fun showOverflow() {
        if (!isAttachedToWindow || visibleCount >= actions.size) return
        overflow?.dismiss()
        val popup = PopupMenu(context, more)
        // Read current labels and enabled state each time, including mic/clipboard updates.
        actions.drop(visibleCount).forEach { button ->
            popup.menu.add(button.text).apply {
                isEnabled = button.isEnabled
                setOnMenuItemClickListener {
                    popup.dismiss()
                    // Let the menu release window focus before the original listener opens a popup.
                    post {
                        if (isAttachedToWindow && button.parent === this@PicoPanelActions && button.isEnabled) {
                            prepareAnchor(button)
                            button.performClick()
                        }
                    }
                    true
                }
            }
        }
        popup.setOnDismissListener { if (overflow === popup) overflow = null }
        overflow = popup
        popup.show()
    }

    override fun onDetachedFromWindow() {
        overflow?.dismiss()
        restoreAnchor()
        super.onDetachedFromWindow()
    }
}
