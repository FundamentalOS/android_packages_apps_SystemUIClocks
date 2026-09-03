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
package com.android.systemui.clocks

import android.content.Context
import android.graphics.Canvas
import android.graphics.Point
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.RelativeLayout
import com.android.systemui.log.core.MessageBuffer
import com.android.systemui.plugins.keyguard.ui.clocks.ClockViewIds

/** Four overlapping digits in a 2x2 grid that "inflate" apart on the lockscreen. */
open class InflateClockView(context: Context, private val assetLoader: AssetLoader, messageBuffer: MessageBuffer) :
    DigitalClockFaceView(context, messageBuffer) {
    override val digitalClockTextViewMap = mutableMapOf<Int, SimpleDigitalClockTextView>()
    open val digitLeftTopMap = mutableMapOf<Int, Point>()
    open var maxSingleDigitHeight = -1
    open var maxSingleDigitWidth = -1
    val lockscreenTranslate = Point(0, 0)
    val aodTranslate = Point(0, 0)
    val bounceTranslate = Point(0, 0)
    open val renderOrder =
        listOf(
            ClockViewIds.HOUR_FIRST_DIGIT,
            ClockViewIds.HOUR_SECOND_DIGIT,
            ClockViewIds.MINUTE_FIRST_DIGIT,
            ClockViewIds.MINUTE_SECOND_DIGIT,
        )

    init {
        setWillNotDraw(false)
        layoutParams = RelativeLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    override fun onViewAdded(child: View?) {
        if (child == null) return
        getLogger().onViewAdded(child)
        super.onViewAdded(child)
        (child as SimpleDigitalClockTextView).digitTranslateAnimator = DigitTranslateAnimator { invalidate() }
    }

    override fun calculateSize(widthMeasureSpec: Int, heightMeasureSpec: Int): Point {
        digitalClockTextViewMap.forEach { (_, view) -> view.measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED) }
        val first = digitalClockTextViewMap[ClockViewIds.HOUR_FIRST_DIGIT]!!
        maxSingleDigitHeight = first.measuredHeight
        maxSingleDigitWidth = first.measuredWidth
        aodTranslate.x = (maxSingleDigitWidth * OVERLAPPED_WIDTH_RATIO * AOD_HORIZONTAL_TRANSLATE_RATIO).toInt()
        aodTranslate.y = (maxSingleDigitHeight * OVERLAPPED_HEIGHT_RATIO * AOD_VERTICAL_TRANSLATE_RATIO).toInt()
        bounceTranslate.x = (maxSingleDigitWidth * OVERLAPPED_WIDTH_RATIO * BOUNCE_TRANSLATE_RATIO).toInt()
        bounceTranslate.y = (maxSingleDigitHeight * OVERLAPPED_HEIGHT_RATIO * BOUNCE_TRANSLATE_RATIO).toInt()
        return Point(
            (maxSingleDigitWidth * (2 - OVERLAPPED_WIDTH_RATIO)).toInt(),
            (maxSingleDigitHeight * (2 - OVERLAPPED_HEIGHT_RATIO)).toInt(),
        )
    }

    override fun calculateLeftTopPosition() {
        digitLeftTopMap[ClockViewIds.HOUR_FIRST_DIGIT] = Point(0, 0)
        digitLeftTopMap[ClockViewIds.HOUR_SECOND_DIGIT] = Point(((1 - OVERLAPPED_WIDTH_RATIO) * maxSingleDigitWidth).toInt(), 0)
        digitLeftTopMap[ClockViewIds.MINUTE_FIRST_DIGIT] = Point(0, ((1 - OVERLAPPED_HEIGHT_RATIO) * maxSingleDigitHeight).toInt())
        digitLeftTopMap[ClockViewIds.MINUTE_SECOND_DIGIT] =
            Point(
                ((1 - OVERLAPPED_WIDTH_RATIO) * maxSingleDigitWidth).toInt(),
                ((1 - OVERLAPPED_HEIGHT_RATIO) * maxSingleDigitHeight).toInt(),
            )
    }

    override fun refreshTime() {
        super.refreshTime()
        digitalClockTextViewMap.forEach { (_, view) -> view.refreshText() }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        for (id in renderOrder) {
            val view = digitalClockTextViewMap[id]!!
            val pos = digitLeftTopMap[id]!!
            canvas.translate(pos.x.toFloat(), pos.y.toFloat())
            view.draw(canvas)
            canvas.translate(-pos.x.toFloat(), -pos.y.toFloat())
        }
    }

    override fun animateDoze(isDozing: Boolean, isAnimated: Boolean) {
        dozeControlState.setAnimateDoze {
            super.animateDoze(isDozing, isAnimated)
            if (maxSingleDigitHeight == -1) measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED)
            digitalClockTextViewMap.forEach { (id, view) ->
                val animator = view.digitTranslateAnimator ?: return@forEach
                if (isDozing) {
                    animator.animatePosition(
                        animate = isAnimated && isAnimationEnabled,
                        duration = INFLATE_CLOCK_DOZING_DURATION,
                        interpolator = INFLATE_CLOCK_DOZING_INTERPOLATOR,
                        targetTranslation = updateDirectionalTargetTranslate(id, aodTranslate),
                    )
                } else {
                    val phase3 = Runnable {
                        animator.animatePosition(
                            animate = isAnimated && isAnimationEnabled,
                            duration = INFLATE_CLOCK_PHASE3_DURATION[id] ?: 0L,
                            interpolator = INFLATE_CLOCK_PHASE3_INTERPOLATOR,
                            targetTranslation = updateDirectionalTargetTranslate(id, lockscreenTranslate),
                        )
                    }
                    val phase2 = Runnable {
                        animator.animatePosition(
                            animate = isAnimated && isAnimationEnabled,
                            duration = INFLATE_CLOCK_PHASE2_DURATION[id] ?: 0L,
                            interpolator = INFLATE_CLOCK_PHASE2_INTERPOLATOR,
                            targetTranslation = updateDirectionalTargetTranslate(id, bounceTranslate),
                            onAnimationEnd = phase3,
                        )
                    }
                    animator.animatePosition(
                        animate = isAnimated && isAnimationEnabled,
                        duration = INFLATE_CLOCK_PHASE1_DURATION[id] ?: 0L,
                        interpolator = INFLATE_CLOCK_PHASE1_INTERPOLATOR,
                        targetTranslation = updateDirectionalTargetTranslate(id, lockscreenTranslate),
                        onAnimationEnd = phase2,
                    )
                }
            }
        }
    }

    companion object {
        private const val OVERLAPPED_WIDTH_RATIO = 0.32f
        private const val OVERLAPPED_HEIGHT_RATIO = 0.15f
        val INFLATE_CLOCK_PHASE1_INTERPOLATOR = PathInterpolator(0.33f, 0f, 0.6f, 1f)
        val INFLATE_CLOCK_PHASE2_INTERPOLATOR = PathInterpolator(0.22f, 0f, 0.54f, 1f)
        val INFLATE_CLOCK_PHASE3_INTERPOLATOR = PathInterpolator(0.29f, 0f, 0.58f, 1f)
        val INFLATE_CLOCK_PHASE1_DURATION =
            mapOf(
                ClockViewIds.HOUR_FIRST_DIGIT to 400L,
                ClockViewIds.HOUR_SECOND_DIGIT to 350L,
                ClockViewIds.MINUTE_FIRST_DIGIT to 367L,
                ClockViewIds.MINUTE_SECOND_DIGIT to 450L,
            )
        val INFLATE_CLOCK_PHASE2_DURATION =
            mapOf(
                ClockViewIds.HOUR_FIRST_DIGIT to 783L,
                ClockViewIds.HOUR_SECOND_DIGIT to 733L,
                ClockViewIds.MINUTE_FIRST_DIGIT to 783L,
                ClockViewIds.MINUTE_SECOND_DIGIT to 667L,
            )
        val INFLATE_CLOCK_PHASE3_DURATION =
            mapOf(
                ClockViewIds.HOUR_FIRST_DIGIT to 817L,
                ClockViewIds.HOUR_SECOND_DIGIT to 1050L,
                ClockViewIds.MINUTE_FIRST_DIGIT to 917L,
                ClockViewIds.MINUTE_SECOND_DIGIT to 1083L,
            )
        const val INFLATE_CLOCK_DOZING_DURATION = 800L
        val INFLATE_CLOCK_DOZING_INTERPOLATOR = PathInterpolator(0.3f, 0f, 0.1f, 1f)
        private const val AOD_HORIZONTAL_TRANSLATE_RATIO = 0.15f
        private const val AOD_VERTICAL_TRANSLATE_RATIO = 0.35f
        private const val BOUNCE_TRANSLATE_RATIO = 0.1f

        /** Mirrors [translate] so each digit moves away from the grid centre. */
        fun updateDirectionalTargetTranslate(id: Int, translate: Point): Point {
            val result = Point(translate)
            when (id) {
                ClockViewIds.HOUR_FIRST_DIGIT -> {
                    result.x *= -1
                    result.y *= -1
                }
                ClockViewIds.HOUR_SECOND_DIGIT -> result.y *= -1
                ClockViewIds.MINUTE_FIRST_DIGIT -> result.x *= -1
                ClockViewIds.MINUTE_SECOND_DIGIT -> {}
            }
            return result
        }
    }
}

/** Small variant: "h:mm" laid out horizontally with overlapping digits around a colon. */
class InflateClockSmallView(context: Context, val assetLoader: AssetLoader, messageBuffer: MessageBuffer) :
    InflateClockView(context, assetLoader, messageBuffer) {
    override val digitalClockTextViewMap = mutableMapOf<Int, SimpleDigitalClockTextView>()
    override val digitLeftTopMap = mutableMapOf<Int, Point>()
    override var maxSingleDigitHeight = -1
    override var maxSingleDigitWidth = -1
    private val colonView =
        SimpleDigitalClockTextView(context, messageBuffer).apply {
            setText(":")
            horizontalAlignment = HorizontalAlignment.LEFT
            verticalAlignment = VerticalAlignment.TOP
        }
    override val renderOrder =
        listOf(
            ClockViewIds.HOUR_FIRST_DIGIT,
            ClockViewIds.HOUR_SECOND_DIGIT,
            colonView.id,
            ClockViewIds.MINUTE_FIRST_DIGIT,
            ClockViewIds.MINUTE_SECOND_DIGIT,
        )

    init {
        addView(colonView)
    }

    override fun onViewAdded(child: View?) {
        if (child == null) return
        getLogger().onViewAdded(child)
        super.onViewAdded(child)
        if (child.id == ClockViewIds.HOUR_FIRST_DIGIT && child is SimpleDigitalClockTextView) {
            colonView.applyStyles(assetLoader, child.textStyle, child.aodStyle)
        }
    }

    override fun calculateLeftTopPosition() {
        val top = (measuredHeight - maxSingleDigitWidth) / 2
        digitLeftTopMap[ClockViewIds.HOUR_FIRST_DIGIT] = Point(0, top)
        if (digitalClockTextViewMap[ClockViewIds.HOUR_FIRST_DIGIT]!!.getText().isBlank()) {
            digitLeftTopMap[ClockViewIds.HOUR_SECOND_DIGIT] = Point(0, top)
        } else {
            digitLeftTopMap[ClockViewIds.HOUR_SECOND_DIGIT] = Point(((1 - NUMBER_OVERLAP_RATIO) * maxSingleDigitWidth).toInt(), top)
        }
        digitLeftTopMap[colonView.id] =
            Point(
                (digitLeftTopMap[ClockViewIds.HOUR_SECOND_DIGIT]!!.x + (1 - NUMBER_COLON_OVERLAP_RATIO) * maxSingleDigitWidth).toInt(),
                (measuredHeight - colonView.textBounds.height()) / 2,
            )
        digitLeftTopMap[ClockViewIds.MINUTE_FIRST_DIGIT] =
            Point(
                (digitLeftTopMap[colonView.id]!!.x + colonView.measuredWidth - NUMBER_COLON_OVERLAP_RATIO * maxSingleDigitWidth).toInt(),
                top,
            )
        digitLeftTopMap[ClockViewIds.MINUTE_SECOND_DIGIT] =
            Point(
                (digitLeftTopMap[ClockViewIds.MINUTE_FIRST_DIGIT]!!.x + (1 - NUMBER_OVERLAP_RATIO) * maxSingleDigitWidth).toInt(),
                top,
            )
    }

    override fun calculateSize(widthMeasureSpec: Int, heightMeasureSpec: Int): Point {
        digitalClockTextViewMap.forEach { (_, view) -> view.measure(-1, -1) }
        val first = digitalClockTextViewMap[ClockViewIds.HOUR_FIRST_DIGIT]!!
        maxSingleDigitHeight = first.measuredHeight
        maxSingleDigitWidth = first.measuredWidth
        val digitCount = if (first.getText().isBlank()) 3 else 4
        val height = if (MeasureSpec.getSize(heightMeasureSpec) != 0) MeasureSpec.getSize(heightMeasureSpec) else maxSingleDigitHeight
        val width =
            maxSingleDigitWidth * digitCount + colonView.measuredWidth -
                maxSingleDigitWidth * NUMBER_OVERLAP_RATIO * (digitCount - 2) -
                maxSingleDigitWidth * NUMBER_COLON_OVERLAP_RATIO * 2
        return Point(width.toInt(), height)
    }

    companion object {
        private const val NUMBER_OVERLAP_RATIO = 0.3
        private const val NUMBER_COLON_OVERLAP_RATIO = 0.2
    }
}
