/*
 * Copyright (C) 2026 FundamentalOS
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.
 */
package com.android.systemui.clocks.words

import android.content.Context
import android.graphics.Canvas
import android.graphics.Point
import android.text.format.DateFormat
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.android.systemui.clocks.AssetLoader
import com.android.systemui.clocks.DigitalClockFaceView
import com.android.systemui.clocks.HorizontalAlignment
import com.android.systemui.clocks.SimpleDigitalClockTextView
import com.android.systemui.clocks.VerticalAlignment
import com.android.systemui.clocks.saveBlock
import com.android.systemui.customization.clocks.R as clocksR
import com.android.systemui.log.core.MessageBuffer
import com.android.systemui.plugins.keyguard.ui.clocks.ClockViewIds
import java.util.Calendar
import kotlin.math.roundToInt

/**
 * The time in words, drawn as a left-aligned stack of lines with a fixed baseline pitch. The design
 * supplies digital-hand layers for the hour (DIGIT_PAIR "hh") and the minute (DIGIT_PAIR "mm"),
 * optionally preceded by a prefix line (TIME_FULL_FORMAT); their text is replaced with words here.
 * The view sizes itself to the stack plus a small gap that keeps the date row below at a distance.
 */
abstract class WordClockViewBase(context: Context, messageBuffer: MessageBuffer) :
    DigitalClockFaceView(context, messageBuffer) {
    override var digitalClockTextViewMap = mutableMapOf<Int, SimpleDigitalClockTextView>()
    override val positionedByLayout = true
    // Words are read at their real size in AOD; the keyguard translates the block with the date
    // row instead of shrinking it (burn-in protection stays on for everything else).
    override val useAlternateSmartspaceAODTransition = true

    var prefixView: SimpleDigitalClockTextView? = null
        private set
    lateinit var hourView: SimpleDigitalClockTextView
    lateinit var minuteView: SimpleDigitalClockTextView
    private var lastWords: List<String> = emptyList()

    val lines: List<SimpleDigitalClockTextView>
        get() = listOfNotNull(prefixView, hourView, minuteView)

    val isReady: Boolean
        get() = this::hourView.isInitialized && this::minuteView.isInitialized

    protected val density: Float
        get() = context.resources.displayMetrics.density

    /** Gap kept below the last line (the date row hangs off this view's bottom). */
    val bottomGap: Int
        get() = (DATE_GAP_DP * density).roundToInt()

    init {
        layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        setWillNotDraw(false)
        clipChildren = false
        clipToPadding = false
    }

    override fun onViewAdded(child: View?) {
        if (child == null) return
        super.onViewAdded(child)
        if (child !is SimpleDigitalClockTextView) return
        child.layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        child.setSingleLine()
        child.horizontalAlignment = HorizontalAlignment.LEFT
        child.verticalAlignment = VerticalAlignment.TOP
        child.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        when (child.id) {
            ClockViewIds.TIME_FULL_FORMAT -> prefixView = child
            ClockViewIds.HOUR_DIGIT_PAIR -> hourView = child
            ClockViewIds.MINUTE_DIGIT_PAIR -> minuteView = child
        }
    }

    /** Height a line view reports for itself (mirrors SimpleDigitalClockTextView.onMeasure). */
    fun lineHeight(view: SimpleDigitalClockTextView): Int =
        view.textBounds.height() + 2 * view.lockScreenPaint.strokeWidth.toInt()

    /** Width a line view reports for itself. */
    fun lineWidth(view: SimpleDigitalClockTextView): Int =
        view.textBounds.width() + 2 * view.lockScreenPaint.strokeWidth.toInt()

    /** Distance between consecutive baselines, relative to the hour line's font size. */
    fun linePitch(): Int = (hourView.lockScreenPaint.textSize * LINE_PITCH).roundToInt()

    /**
     * Top offset of each line so that the baselines sit [linePitch] apart. A TOP-aligned line draws
     * its glyph top at (view top + stroke width), i.e. its baseline is at (top + stroke -
     * textBounds.top).
     */
    fun lineTops(): IntArray {
        val ascent = lines.maxOf { -it.textBounds.top + it.lockScreenPaint.strokeWidth.toInt() }
        val pitch = linePitch()
        return IntArray(lines.size) { i ->
            val line = lines[i]
            ascent + i * pitch + line.textBounds.top - line.lockScreenPaint.strokeWidth.toInt()
        }
    }

    fun stackHeight(): Int = lineTops().last() + lineHeight(lines.last())

    override fun calculateSize(widthMeasureSpec: Int, heightMeasureSpec: Int): Point? {
        if (!isReady) return null
        // Measure the lines first (the base class only measures children when it lays out itself).
        for (line in lines) line.measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED)
        return Point(lines.maxOf { lineWidth(it) }, stackHeight() + bottomGap)
    }

    override fun refreshTime() {
        super.refreshTime()
        if (!isReady) return
        val now = Calendar.getInstance()
        val is24Hour = DateFormat.is24HourFormat(context)
        val words =
            listOfNotNull(
                prefixView?.let { TimeWords.PREFIX },
                TimeWords.hour(now.get(Calendar.HOUR_OF_DAY), is24Hour),
                TimeWords.minute(now.get(Calendar.MINUTE)),
            )
        // The digital-hand layers have just written their digits ("02", "46") into these views on
        // this same tick; always put the words back, but only relayout when the words changed.
        lines.zip(words).forEach { (view, text) -> setLine(view, text) }
        if (words == lastWords) return
        lastWords = words
        contentDescription = words.joinToString(" ")
        requestLayout()
        invalidate()
    }

    private fun setLine(view: SimpleDigitalClockTextView, text: String) {
        if (view.getText() == text) return
        view.setText(text)
        view.refreshText()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!isReady) return
        val tops = lineTops()
        for (i in lines.indices) {
            canvas.saveBlock().use {
                canvas.translate(0f, tops[i].toFloat())
                lines[i].draw(canvas)
            }
        }
    }

    companion object {
        const val LINE_PITCH = 1.25f
        const val DATE_GAP_DP = 20f
    }
}

/** Large face: "It\u2019s" / hour / minute, placed by [WordClockFaceLayoutLarge]. */
class WordClockViewLarge(context: Context, val assets: AssetLoader, messageBuffer: MessageBuffer) :
    WordClockViewBase(context, messageBuffer) {
    override val useCustomClockScene = true
}

/** Small (collapsed) face: hour / minute at the top of the small clock slot, tinted by the design. */
class WordClockViewSmall(context: Context, val assets: AssetLoader, messageBuffer: MessageBuffer) :
    WordClockViewBase(context, messageBuffer) {
    /** The words keep the large face's size when notifications push the clock into the small slot. */
    override fun onFontSettingChanged(fontSizePx: Float) =
        super.onFontSettingChanged(context.resources.getDimensionPixelSize(clocksR.dimen.large_clock_text_size).toFloat())
}
