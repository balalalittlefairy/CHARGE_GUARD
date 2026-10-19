package io.github.chargercontroller

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

/** Keep long records readable on tablets without making phone text smaller. */
internal class LogContentLayout(context: Context) : LinearLayout(context) {
    init { orientation = VERTICAL }
    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val width = minOf(MeasureSpec.getSize(widthSpec), Fluent.dp(context, 720))
        super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), heightSpec)
    }
}

/** The time moves below the title when width or the system font scale requires it. */
internal class LogEntryHeader(context: Context, title: String, time: String) : LinearLayout(context) {
    val titleView = TextView(context).apply {
        tag = "log-title"; text = title; textSize = 16f; setTextColor(Fluent.ink)
        typeface = Typeface.DEFAULT_BOLD; setLineSpacing(Fluent.dp(context, 3).toFloat(), 1f)
    }
    val timeView = TextView(context).apply {
        tag = "log-time"; text = time; textSize = 13f; setTextColor(Fluent.muted)
        typeface = Typeface.create("monospace", Typeface.NORMAL); isSingleLine = true
    }
    private var stacked: Boolean? = null
    init { gravity = Gravity.TOP; addView(titleView); addView(timeView) }
    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val effectiveWidth = MeasureSpec.getSize(widthSpec) / resources.displayMetrics.density / resources.configuration.fontScale
        val next = effectiveWidth < 300f
        if (stacked != next) {
            stacked = next; orientation = if (next) VERTICAL else HORIZONTAL
            titleView.layoutParams = if (next) LayoutParams(-1, -2) else LayoutParams(0, -2, 1f)
            timeView.layoutParams = LayoutParams(-2, -2).apply {
                if (next) topMargin = Fluent.dp(context, 4) else leftMargin = Fluent.dp(context, 8)
            }
        }
        super.onMeasure(widthSpec, heightSpec)
    }
}

/** Filter chips wrap to another row instead of clipping at large system font sizes. */
internal class LogFiltersLayout(context: Context) : ViewGroup(context) {
    private val gap = Fluent.dp(context, 8)
    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val width = MeasureSpec.getSize(widthSpec)
        val available = (width - paddingLeft - paddingRight).coerceAtLeast(1)
        var x = 0; var y = paddingTop; var lineHeight = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == View.GONE) continue
            child.measure(MeasureSpec.makeMeasureSpec(available, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            if (x > 0 && x + child.measuredWidth > available) { y += lineHeight + gap; x = 0; lineHeight = 0 }
            x += child.measuredWidth + gap; lineHeight = maxOf(lineHeight, child.measuredHeight)
        }
        setMeasuredDimension(width, resolveSize(y + lineHeight + paddingBottom, heightSpec))
    }
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val available = width - paddingLeft - paddingRight
        var x = 0; var y = paddingTop; var lineHeight = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == View.GONE) continue
            if (x > 0 && x + child.measuredWidth > available) { y += lineHeight + gap; x = 0; lineHeight = 0 }
            child.layout(paddingLeft + x, y, paddingLeft + x + child.measuredWidth, y + child.measuredHeight)
            x += child.measuredWidth + gap; lineHeight = maxOf(lineHeight, child.measuredHeight)
        }
    }
}
