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
import android.graphics.Paint
import android.graphics.Point
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.text.TextPaint
import android.view.View
import android.view.ViewGroup
import android.widget.RelativeLayout
import com.android.app.animation.Interpolators
import com.android.systemui.animation.TextAnimator
import com.android.systemui.log.core.MessageBuffer
import com.android.systemui.plugins.keyguard.ui.clocks.ClockViewIds
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Four overlapping digits where each digit "cuts" a gap into the digits it overlaps, using
 * per-digit DST_OUT text animators drawn at a wider stroke.
 */
open class NumberOverlapClockView(
    context: Context,
    protected val assetLoader: AssetLoader,
    open val dozeState: AnimationState,
    messageBuffer: MessageBuffer,
) : DigitalClockFaceView(context, messageBuffer) {
    protected val digitLeftTopMap = mutableMapOf<Int, Point>()
    protected var maxSingleDigitHeight = -1
        private set
    protected var maxSingleDigitWidth = -1
        private set
    private val outlineCutAnimator = mutableMapOf<Int, TextAnimator>()
    private val innerCutAnimator = mutableMapOf<Int, TextAnimator>()
    override val digitalClockTextViewMap = mutableMapOf<Int, SimpleDigitalClockTextView>()
    private val aodTranslateMap = mutableMapOf<Int, Point>()
    protected val canvasRectF = RectF(0f, 0f, 0f, 0f)
    private var outlineCutStrokeWidth = 0f
    private var innerCutStrokeWidth = 0f
    private val paintForInner = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) }

    enum class PaintState {
        AOD_INNER,
        AOD_OUTER,
        LOCKSCREEN,
    }

    init {
        setWillNotDraw(false)
        layoutParams = RelativeLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    override fun onViewAdded(child: View?) {
        if (child == null) return
        getLogger().onViewAdded(child)
        super.onViewAdded(child)
        if (child is SimpleDigitalClockTextView) {
            child.digitTranslateAnimator =
                DigitTranslateAnimator {
                    updateCanvasRectF()
                    invalidate()
                }
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        setClippingAnimator()
    }

    override fun calculateSize(widthMeasureSpec: Int, heightMeasureSpec: Int): Point {
        for ((id, view) in digitalClockTextViewMap) {
            view.measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED)
            outlineCutAnimator[id]?.updateLayout(view.layout)
            innerCutAnimator[id]?.updateLayout(view.layout)
        }
        val first = digitalClockTextViewMap[ClockViewIds.HOUR_FIRST_DIGIT]!!
        if (maxSingleDigitHeight != first.measuredHeight || maxSingleDigitWidth != first.measuredWidth) {
            maxSingleDigitHeight = first.measuredHeight
            maxSingleDigitWidth = first.measuredWidth
            updateCuttingAnimatorStrokeWidth()
        }
        val height = ceil(maxSingleDigitHeight * (2 - OVERLAPPED_HEIGHT_RATIO) + AOD_VERTICAL_TRANSLATE_RATIO * maxSingleDigitHeight * 2).toInt()
        val width = ceil(maxSingleDigitWidth * (2 - OVERLAPPED_WIDTH_RATIO) + AOD_HORIZONTAL_TRANSLATE_RATIO * maxSingleDigitWidth * 2).toInt()
        return Point(width, height)
    }

    override fun onFontSettingChanged(fontSizePx: Float) {
        super.onFontSettingChanged(fontSizePx)
        val style = TextAnimator.Style(textSize = fontSizePx)
        outlineCutAnimator.values.forEach { it.setTextStyle(style) }
        innerCutAnimator.values.forEach { it.setTextStyle(style) }
    }

    private fun setClippingAnimator() {
        getLogger().i({ "setClippingAnimator(inner=${int1 / 1000f}, outline=${int2 / 1000f})" }) {
            int1 = (innerCutStrokeWidth * 1000f).roundToInt()
            int2 = (outlineCutStrokeWidth * 1000f).roundToInt()
        }
        updateCuttingAnimator(outlineCutAnimator, outlineCutStrokeWidth)
        updateCuttingAnimator(innerCutAnimator, innerCutStrokeWidth)
    }

    private fun updateCuttingAnimator(animators: MutableMap<Int, TextAnimator>, strokeWidth: Float) {
        digitalClockTextViewMap.forEach { (id, view) ->
            val layout = view.layout ?: return@forEach
            val animator =
                animators.getOrPut(id) {
                    TextAnimator(layout, view.typefaceCache!!).also {
                        val paint = TextPaint(view.lockScreenPaint)
                        paint.style = Paint.Style.FILL_AND_STROKE
                        paint.strokeJoin = Paint.Join.ROUND
                        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
                        it.textInterpolator.targetPaint.set(paint)
                        it.textInterpolator.onTargetPaintModified()
                    }
                }
            animator.updateLayout(layout)
            animator.setTextStyle(
                TextAnimator.Style(
                    fVar = if (!dozeState.isActive) view.textStyle.fontVariation else view.aodStyle.fontVariation,
                    strokeWidth = strokeWidth,
                )
            )
        }
    }

    private fun updateCuttingAnimatorStrokeWidth() {
        outlineCutStrokeWidth = getOverlappedWidth(maxSingleDigitWidth)
        outlineCutAnimator.forEach { (_, animator) -> animator.setTextStyle(TextAnimator.Style(strokeWidth = outlineCutStrokeWidth)) }
        val aodBorderWidth = digitalClockTextViewMap[ClockViewIds.HOUR_FIRST_DIGIT]!!.aodBorderWidth
        innerCutStrokeWidth = outlineCutStrokeWidth + aodBorderWidth
        innerCutAnimator.forEach { (_, animator) -> animator.setTextStyle(TextAnimator.Style(strokeWidth = innerCutStrokeWidth)) }
        getLogger().i({
            "updateCuttingAnimatorStrokeWidth($int1, ${int2 / 1000f}) -> (inner=${long1 / 1000f}, outline=${long2 / 1000f})"
        }) {
            int1 = maxSingleDigitWidth
            int2 = (aodBorderWidth * 1000f).roundToInt()
            long1 = (innerCutStrokeWidth * 1000f).roundToInt().toLong()
            long2 = (outlineCutStrokeWidth * 1000f).roundToInt().toLong()
        }
    }

    override fun refreshTime() {
        super.refreshTime()
        invalidate()
    }

    override fun calculateLeftTopPosition() {
        if (measuredWidth == 0 || measuredHeight == 0) measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED)
        digitLeftTopMap[ClockViewIds.HOUR_FIRST_DIGIT] = Point(0, 0)
        digitLeftTopMap[ClockViewIds.HOUR_SECOND_DIGIT] = Point((maxSingleDigitWidth * (1 - OVERLAPPED_WIDTH_RATIO)).toInt(), 0)
        digitLeftTopMap[ClockViewIds.MINUTE_FIRST_DIGIT] = Point(0, (maxSingleDigitHeight * (1 - OVERLAPPED_HEIGHT_RATIO)).toInt())
        digitLeftTopMap[ClockViewIds.MINUTE_SECOND_DIGIT] =
            Point(
                (maxSingleDigitWidth * (1 - OVERLAPPED_WIDTH_RATIO)).toInt(),
                (maxSingleDigitHeight * (1 - OVERLAPPED_HEIGHT_RATIO)).toInt(),
            )
        digitLeftTopMap.forEach { (_, point) ->
            point.x += ceil(AOD_HORIZONTAL_TRANSLATE_RATIO * maxSingleDigitWidth).toInt()
            point.y += ceil(AOD_VERTICAL_TRANSLATE_RATIO * maxSingleDigitHeight).toInt()
        }
        aodTranslateMap[ClockViewIds.HOUR_FIRST_DIGIT] = Point(0, (-AOD_VERTICAL_TRANSLATE_RATIO * maxSingleDigitHeight).toInt())
        aodTranslateMap[ClockViewIds.HOUR_SECOND_DIGIT] = Point((maxSingleDigitWidth * AOD_HORIZONTAL_TRANSLATE_RATIO).toInt(), 0)
        aodTranslateMap[ClockViewIds.MINUTE_FIRST_DIGIT] = Point((-AOD_HORIZONTAL_TRANSLATE_RATIO * maxSingleDigitWidth).toInt(), 0)
        aodTranslateMap[ClockViewIds.MINUTE_SECOND_DIGIT] = Point(0, (AOD_VERTICAL_TRANSLATE_RATIO * maxSingleDigitHeight).toInt())
        updateCanvasRectF()
    }

    override fun animateDoze(isDozing: Boolean, isAnimated: Boolean) {
        digitalClockTextViewMap.forEach { (id, view) ->
            view.animateDoze(isDozing, isAnimated)
            val style = TextAnimator.Style(fVar = if (isDozing) view.aodStyle.fontVariation else view.textStyle.fontVariation)
            val animation = TextAnimator.Animation(animate = isAnimated && isAnimationEnabled)
            outlineCutAnimator[id]?.setTextStyle(style, animation)
            innerCutAnimator[id]?.setTextStyle(style, animation)
        }
        dozeControlState.setAnimateDoze {
            digitalClockTextViewMap.forEach { (id, view) ->
                view.digitTranslateAnimator?.animatePosition(
                    animate = isAnimated && isAnimationEnabled,
                    duration = TRANSLATE_ANIMATION_DURATION,
                    interpolator = Interpolators.EMPHASIZED,
                    targetTranslation = if (isDozing) aodTranslateMap[id] else Point(0, 0),
                )
            }
        }
    }

    override fun animateCharge() {
        digitalClockTextViewMap.forEach { (id, view) ->
            view.animateCharge()
            val chargeStyle =
                TextAnimator.Style(fVar = if (dozeFraction < 0.5f) view.aodStyle.fontVariation else view.textStyle.fontVariation)
            val restoreStyle =
                TextAnimator.Style(fVar = if (dozeFraction < 0.5f) view.textStyle.fontVariation else view.aodStyle.fontVariation)
            val animation = TextAnimator.Animation(animate = isAnimationEnabled)
            outlineCutAnimator[id]?.setTextStyle(
                chargeStyle,
                TextAnimator.Animation(
                    animate = isAnimationEnabled,
                    onAnimationEnd = {
                        outlineCutAnimator[id]?.setTextStyle(restoreStyle, animation)
                        innerCutAnimator[id]?.setTextStyle(restoreStyle, animation)
                    },
                ),
            )
            innerCutAnimator[id]?.setTextStyle(chargeStyle, animation)
        }
    }

    private fun drawLockScreenClock(canvas: Canvas) {
        canvas.saveLayer(canvasRectF, null)
        drawOverlappedDigits(canvas, PaintState.LOCKSCREEN)
        canvas.restore()
    }

    private fun drawAodClock(canvas: Canvas) {
        canvas.saveLayer(canvasRectF, null)
        drawOverlappedDigits(canvas, PaintState.AOD_OUTER)
        canvas.saveLayer(canvasRectF, paintForInner)
        drawOverlappedDigits(canvas, PaintState.AOD_INNER)
        canvas.restore()
        canvas.restore()
    }

    private fun drawSingleDigit(canvas: Canvas, paintState: PaintState, id: Int) {
        canvas.saveBlock().use {
            val (tx, ty) = getAddedTranslateForDrawing(id)
            canvas.translate(tx.toFloat(), ty.toFloat())
            digitalClockTextViewMap[id]?.animators?.let { animators ->
                when (paintState) {
                    PaintState.AOD_INNER -> animators.innerAnimator.draw(canvas)
                    PaintState.AOD_OUTER -> animators.outlineAnimator.draw(canvas)
                    PaintState.LOCKSCREEN -> animators.textAnimator.draw(canvas)
                }
            }
        }
        OVERLAPPED_MAP[id]?.forEach { overlappedId ->
            canvas.saveBlock().use {
                val (tx, ty) = getAddedTranslateForDrawing(overlappedId)
                canvas.translate(tx.toFloat(), ty.toFloat())
                when (paintState) {
                    PaintState.AOD_INNER -> innerCutAnimator[overlappedId]?.draw(canvas)
                    PaintState.AOD_OUTER,
                    PaintState.LOCKSCREEN -> outlineCutAnimator[overlappedId]?.draw(canvas)
                }
            }
        }
    }

    private fun getAddedTranslateForDrawing(id: Int): Pair<Int, Int> {
        if (digitLeftTopMap.isEmpty()) return 0 to 0
        val pos = digitLeftTopMap[id]!!
        val view = digitalClockTextViewMap[id]!!
        val local = view.getLocalTranslation()
        val animated = view.digitTranslateAnimator?.updatedTranslate
        return (pos.x + local.x + (animated?.x ?: 0)) to (pos.y + local.y + (animated?.y ?: 0))
    }

    private fun drawOverlappedDigits(canvas: Canvas, paintState: PaintState) {
        for ((id, _) in digitalClockTextViewMap) drawSingleDigit(canvas, paintState, id)
    }

    open fun updateCanvasRectF() {
        if (digitLeftTopMap.isEmpty()) return
        canvasRectF.left =
            digitLeftTopMap[ClockViewIds.MINUTE_FIRST_DIGIT]!!.x +
                digitalClockTextViewMap[ClockViewIds.MINUTE_FIRST_DIGIT]!!.digitTranslateAnimator!!.updatedTranslate.x.toFloat()
        canvasRectF.top =
            digitLeftTopMap[ClockViewIds.HOUR_FIRST_DIGIT]!!.y +
                digitalClockTextViewMap[ClockViewIds.HOUR_FIRST_DIGIT]!!.digitTranslateAnimator!!.updatedTranslate.y.toFloat()
        canvasRectF.right =
            digitLeftTopMap[ClockViewIds.HOUR_SECOND_DIGIT]!!.x +
                digitalClockTextViewMap[ClockViewIds.HOUR_SECOND_DIGIT]!!.digitTranslateAnimator!!.updatedTranslate.x +
                measuredWidth.toFloat()
        canvasRectF.bottom =
            digitLeftTopMap[ClockViewIds.MINUTE_SECOND_DIGIT]!!.y +
                digitalClockTextViewMap[ClockViewIds.MINUTE_SECOND_DIGIT]!!.digitTranslateAnimator!!.updatedTranslate.y +
                measuredHeight.toFloat()
    }

    override val text: String
        get() = buildString { digitalClockTextViewMap.values.forEach { append(it.getText()) } }

    override fun onDraw(canvas: Canvas) {
        getLogger().i({
            "onDraw($str1, inner=${int1 / 1000f}, outline=${int2 / 1000f}, frac=${long1 / 1000f}, isEmpty=$bool1)"
        }) {
            str1 = text
            int1 = (innerCutStrokeWidth * 1000f).roundToInt()
            int2 = (outlineCutStrokeWidth * 1000f).roundToInt()
            long1 = (dozeState.fraction * 1000f).roundToInt().toLong()
            bool1 = digitLeftTopMap.isEmpty()
        }
        if (dozeState.fraction > 0.0) drawAodClock(canvas)
        if (dozeState.fraction < 1.0) drawLockScreenClock(canvas)
    }

    companion object {
        private const val OVERLAPPED_HEIGHT_RATIO = 0.15f
        private const val OVERLAPPED_WIDTH_RATIO = 0.2f
        const val TRANSLATE_ANIMATION_DURATION = 750L
        const val AOD_VERTICAL_TRANSLATE_RATIO = 0.25f
        const val AOD_HORIZONTAL_TRANSLATE_RATIO = 0.1f

        /** Which digits each digit overlaps (and therefore cuts into). */
        private val OVERLAPPED_MAP =
            mapOf(
                ClockViewIds.HOUR_FIRST_DIGIT to
                    listOf(ClockViewIds.HOUR_SECOND_DIGIT, ClockViewIds.MINUTE_FIRST_DIGIT, ClockViewIds.MINUTE_FIRST_DIGIT),
                ClockViewIds.HOUR_SECOND_DIGIT to listOf(ClockViewIds.MINUTE_SECOND_DIGIT, ClockViewIds.MINUTE_FIRST_DIGIT),
                ClockViewIds.MINUTE_FIRST_DIGIT to listOf(ClockViewIds.MINUTE_SECOND_DIGIT),
            )

        private fun getOverlappedWidth(maxSingleDigitWidth: Int): Float = maxSingleDigitWidth * 0.1f
    }
}

/** Small variant: "h:mm" horizontally with 30% digit overlap and no AOD translation. */
class NumberOverlapSmallClockView(
    context: Context,
    assetLoader: AssetLoader,
    override val dozeState: AnimationState,
    messageBuffer: MessageBuffer,
) : NumberOverlapClockView(context, assetLoader, dozeState, messageBuffer) {
    private val colonView =
        SimpleDigitalClockTextView(context, messageBuffer).apply {
            setText(":")
            horizontalAlignment = HorizontalAlignment.LEFT
            verticalAlignment = VerticalAlignment.CENTER
        }

    init {
        addView(colonView)
    }

    override fun onViewAdded(child: View?) {
        if (child == null) return
        getLogger().onViewAdded(child)
        super.onViewAdded(child)
        if (child is SimpleDigitalClockTextView) child.digitTranslateAnimator = null
        if (child.id == ClockViewIds.HOUR_SECOND_DIGIT) {
            child as SimpleDigitalClockTextView
            colonView.applyStyles(assetLoader, child.textStyle, child.aodStyle)
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        updateCanvasRectF()
    }

    override fun calculateSize(widthMeasureSpec: Int, heightMeasureSpec: Int): Point {
        super.calculateSize(widthMeasureSpec, heightMeasureSpec)
        val digitCount = if (digitalClockTextViewMap[ClockViewIds.HOUR_FIRST_DIGIT]!!.getText().isBlank()) 3 else 4
        val width = ceil(maxSingleDigitWidth * (digitCount - (digitCount - 2) * OVERLAPPED_WIDTH_RATIO) + colonView.measuredWidth).toInt()
        val height = if (MeasureSpec.getSize(heightMeasureSpec) != 0) MeasureSpec.getSize(heightMeasureSpec) else maxSingleDigitHeight
        return Point(width, height)
    }

    override fun calculateLeftTopPosition() {
        if (measuredWidth == 0 || measuredHeight == 0) measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED)
        val top = measuredHeight / 2 - maxSingleDigitHeight / 2
        digitLeftTopMap[ClockViewIds.HOUR_FIRST_DIGIT] = Point(0, top)
        if (digitalClockTextViewMap[ClockViewIds.HOUR_FIRST_DIGIT]!!.getText().isBlank()) {
            digitLeftTopMap[ClockViewIds.HOUR_SECOND_DIGIT] = Point(0, top)
        } else {
            digitLeftTopMap[ClockViewIds.HOUR_SECOND_DIGIT] = Point((maxSingleDigitWidth * (1 - OVERLAPPED_WIDTH_RATIO)).toInt(), top)
        }
        digitLeftTopMap[colonView.id] =
            Point(
                digitLeftTopMap[ClockViewIds.HOUR_SECOND_DIGIT]!!.x + maxSingleDigitWidth,
                measuredHeight / 2 - colonView.measuredHeight / 2,
            )
        digitLeftTopMap[ClockViewIds.MINUTE_FIRST_DIGIT] = Point(digitLeftTopMap[colonView.id]!!.x + colonView.textBounds.width(), top)
        digitLeftTopMap[ClockViewIds.MINUTE_SECOND_DIGIT] =
            Point(
                (digitLeftTopMap[ClockViewIds.MINUTE_FIRST_DIGIT]!!.x + maxSingleDigitWidth * (1 - OVERLAPPED_WIDTH_RATIO)).toInt(),
                top,
            )
    }

    override fun updateCanvasRectF() {
        if (digitLeftTopMap.isEmpty()) return
        canvasRectF.left = digitLeftTopMap[ClockViewIds.HOUR_FIRST_DIGIT]!!.x.toFloat()
        canvasRectF.top = digitLeftTopMap[ClockViewIds.HOUR_FIRST_DIGIT]!!.y.toFloat()
        canvasRectF.right = digitLeftTopMap[ClockViewIds.MINUTE_SECOND_DIGIT]!!.x + measuredWidth.toFloat()
        canvasRectF.bottom = digitLeftTopMap[ClockViewIds.MINUTE_SECOND_DIGIT]!!.y + measuredHeight.toFloat()
    }

    companion object {
        private const val OVERLAPPED_WIDTH_RATIO = 0.3f
    }
}
