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

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Point
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.text.TextPaint
import android.util.AttributeSet
import android.util.MathUtils
import android.view.View
import android.view.animation.Interpolator
import android.widget.TextView
import com.android.app.animation.Interpolators
import com.android.systemui.animation.TextAnimator
import com.android.systemui.animation.TextAnimatorListener
import com.android.systemui.animation.TypefaceVariantCache
import com.android.systemui.customization.clocks.ClockLogger
import com.android.systemui.log.core.MessageBuffer
import com.android.systemui.plugins.keyguard.ui.clocks.ClockViewIds
import com.android.systemui.plugins.keyguard.ui.clocks.ThemeConfig
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** Common surface of the text-based and lottie-based digit views. */
interface SimpleDigitalClockView {
    var verticalAlignment: VerticalAlignment
    var horizontalAlignment: HorizontalAlignment
    var dozeFraction: Float

    fun getText(): String

    fun setText(text: String)

    fun updateColors(assets: AssetLoader, theme: ThemeConfig)

    fun applyStyles(assets: AssetLoader, style: TextStyle, aodStyle: TextStyle?)

    fun applyTextSize(targetFontSizePx: Float?, constrainedByHeight: Boolean = false)

    fun refreshTime()

    fun animateDoze(isDozing: Boolean, isAnimated: Boolean)

    fun animateCharge()
}

/**
 * A single run of clock text (a digit, a digit pair, a full time or a date) rendered through
 * three [TextAnimator]s: the filled text, its outline and an inner mask used for hollow AOD text.
 */
@SuppressLint("AppCompatCustomView")
open class SimpleDigitalClockTextView(
    context: Context,
    messageBuffer: MessageBuffer,
    attrs: AttributeSet? = null,
) : TextView(context, attrs), SimpleDigitalClockView {
    val lockScreenPaint = TextPaint().apply { typeface = this@SimpleDigitalClockTextView.typeface }
    private val parser = DimensionParser(context)

    lateinit var textStyle: FontTextStyle
    lateinit var aodStyle: FontTextStyle

    var digitTranslateAnimator: DigitTranslateAnimator? = null
    var isVertical = false

    private var maxSingleDigitHeight = -1
    private var maxSingleDigitWidth = -1
    var aodFontSizePx = -1f
        private set

    // Font size used when there is no height constraint; the reference for constrained sizing.
    private var lastUnconstrainedTextSize = Float.MAX_VALUE
    // (text height / font size) measured unconstrained, used to scale a height-constrained font.
    var fontSizeAdjustFactor = 1f

    private val initThread = Thread.currentThread()

    val textBounds = Rect()
    private val prevTextBounds = Rect()
    private val targetTextBounds = Rect()

    private val clockLogger = ClockLogger(this, messageBuffer, this::class.simpleName!!)
    protected fun getLogger(): ClockLogger = clockLogger ?: ClockLogger.INIT_LOGGER

    private var aodDozingInterpolator: Interpolator = Interpolators.LINEAR

    data class Animators(
        val textAnimator: TextAnimator,
        val innerAnimator: TextAnimator,
        val outlineAnimator: TextAnimator,
    )

    var animators: Animators? = null
        private set

    var typefaceCache: TypefaceVariantCache? = null
        private set
    private var typefaceName: String? = null

    override var verticalAlignment = VerticalAlignment.CENTER
    override var horizontalAlignment = HorizontalAlignment.LEFT
    var isAnimationEnabled = true
    override var dozeFraction = 0f
        set(value) {
            field = value
            invalidate()
        }

    var textBorderWidth = 0f
    var aodBorderWidth = 0f
        private set
    private var baselineFromMeasure = 0
    private var skipInvalidateLogging = false

    private var textFillColor: Int? = null
    private var textOutlineColor = TEXT_OUTLINE_DEFAULT_COLOR
    private var aodFillColor = AOD_DEFAULT_COLOR
    private var aodOutlineColor = AOD_OUTLINE_DEFAULT_COLOR

    override fun getText(): String = super.getText().toString()

    override fun setText(text: String) = super.setText(text)

    fun setSkipInvalidateLogging(skip: Boolean) {
        skipInvalidateLogging = skip
    }

    fun createTextAnimator(onInvalidate: () -> Unit = {}): TextAnimator {
        val layout = layout ?: throw IllegalStateException("Layout cannot be null")
        val cache = typefaceCache ?: throw IllegalStateException("Typeface Cache cannot be null")
        return TextAnimator(
            layout,
            cache,
            object : TextAnimatorListener {
                override fun onInvalidate() = onInvalidate()
            },
        )
    }

    override fun updateColors(assets: AssetLoader, theme: ThemeConfig) {
        val fill =
            assets.tryReadColor(if (theme.isDarkTheme) textStyle.fillColorLight else textStyle.fillColorDark)
                ?: assets.seedColor
                ?: assets.getDefaultColor(theme.isDarkTheme)
        textFillColor = fill
        lockScreenPaint.color = fill
        textOutlineColor = assets.tryReadColor(textStyle.outlineColor) ?: TEXT_OUTLINE_DEFAULT_COLOR
        aodFillColor = assets.tryReadColor(aodStyle.fillColorLight ?: aodStyle.fillColorDark) ?: AOD_DEFAULT_COLOR
        aodOutlineColor = assets.tryReadColor(aodStyle.outlineColor) ?: AOD_OUTLINE_DEFAULT_COLOR

        if (dozeFraction < 1f) {
            animators?.let {
                it.textAnimator.setTextStyle(TextAnimator.Style(color = textFillColor))
                it.outlineAnimator.setTextStyle(TextAnimator.Style(color = textOutlineColor))
            }
        }
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        getLogger().onMeasure(widthMeasureSpec, heightMeasureSpec)
        if (isVertical) {
            super.onMeasure(
                MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.AT_MOST),
                MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(heightMeasureSpec), MeasureSpec.AT_MOST),
            )
        } else {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }

        val current = animators
        if (current != null) {
            current.textAnimator.updateLayout(layout)
            current.outlineAnimator.updateLayout(layout)
            current.innerAnimator.updateLayout(layout)
        } else {
            animators = Animators(createTextAnimator { invalidate() }, createTextAnimator(), createTextAnimator())
            setInterpolatorPaint()
        }
        baselineFromMeasure = layout.getLineBaseline(0)

        var heightSpec = heightMeasureSpec
        if (MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.EXACTLY) {
            val height =
                if (isSingleDigit()) maxSingleDigitHeight
                else textBounds.height() + lockScreenPaint.strokeWidth.toInt() * 2
            heightSpec = MeasureSpec.makeMeasureSpec(height, MeasureSpec.getMode(measuredHeight))
        }
        var widthSpec = widthMeasureSpec
        if (MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.EXACTLY) {
            val width =
                if (isSingleDigit()) maxSingleDigitWidth
                else max(textBounds.width() + lockScreenPaint.strokeWidth.toInt() * 2, MeasureSpec.getSize(measuredWidth))
            widthSpec = MeasureSpec.makeMeasureSpec(width, MeasureSpec.getMode(measuredWidth))
        }

        if (isVertical) setMeasuredDimension(heightSpec, widthSpec) else setMeasuredDimension(widthSpec, heightSpec)
    }

    override fun onDraw(canvas: Canvas) {
        val animators = animators
        if (animators == null) {
            getLogger().w("No text animators; running TextView.onDraw()")
            super.onDraw(canvas)
            return
        }

        if (isVertical) {
            canvas.save()
            canvas.translate(0f, measuredHeight.toFloat())
            canvas.rotate(-90f)
        }
        getLogger().onDraw(
            animators.textAnimator.textInterpolator.shapedText,
            animators.outlineAnimator.textInterpolator.shapedText,
        )

        val translation = getLocalTranslation()
        canvas.translate(translation.x.toFloat(), translation.y.toFloat())
        digitTranslateAnimator?.let { canvas.translate(it.updatedTranslate.x.toFloat(), it.updatedTranslate.y.toFloat()) }

        if (aodStyle.renderType == RenderType.HOLLOW_TEXT) {
            val left = -translation.x.toFloat()
            val top = -translation.y.toFloat()
            val right = left + measuredWidth
            val bottom = top + measuredHeight
            canvas.saveLayer(left, top, right, bottom, null)
            animators.outlineAnimator.draw(canvas)
            canvas.saveLayer(left, top, right, bottom, Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) })
            animators.innerAnimator.draw(canvas)
            canvas.restore()
            canvas.restore()
        } else if (aodStyle.renderType != RenderType.CHANGE_WEIGHT) {
            animators.outlineAnimator.draw(canvas)
        }
        animators.textAnimator.draw(canvas)

        digitTranslateAnimator?.let { canvas.translate(-it.updatedTranslate.x.toFloat(), -it.updatedTranslate.y.toFloat()) }
        canvas.translate(-translation.x.toFloat(), -translation.y.toFloat())
        if (isVertical) canvas.restore()
    }

    override fun setVisibility(visibility: Int) {
        getLogger().setVisibility(visibility)
        super.setVisibility(visibility)
    }

    override fun setAlpha(alpha: Float) {
        getLogger().setAlpha(alpha)
        super.setAlpha(alpha)
    }

    override fun invalidate() {
        if (!skipInvalidateLogging) getLogger().invalidate()
        super.invalidate()
        (parent as? DigitalClockFaceView)?.invalidate()
    }

    override fun refreshTime() {
        getLogger().refreshTime()
        refreshText()
    }

    override fun animateDoze(isDozing: Boolean, isAnimated: Boolean) {
        getLogger().animateDoze(isDozing, isAnimated)
        val animators = animators ?: return
        val style =
            TextAnimator.Style(
                fVar = (if (isDozing) aodStyle else textStyle).fontVariation,
                textSize = if (isDozing) aodFontSizePx else lockScreenPaint.textSize,
                color = if (isDozing) aodFillColor else textFillColor,
            )
        val animation =
            TextAnimator.Animation(
                animate = isAnimated && isAnimationEnabled,
                duration = aodStyle.transitionDuration,
                interpolator = aodDozingInterpolator,
            )
        animators.textAnimator.setTextStyle(style, animation)
        updateTextBoundsForTextAnimator()
        animators.outlineAnimator.setTextStyle(
            style.copy(
                color = if (isDozing) aodOutlineColor else textOutlineColor,
                strokeWidth = if (isDozing) aodBorderWidth else textBorderWidth,
            ),
            animation,
        )
        animators.innerAnimator.setTextStyle(style.copy(color = Color.WHITE), animation)
    }

    override fun animateCharge() {
        val animators = animators ?: return
        if (animators.textAnimator.isRunning) return
        getLogger().animateCharge()

        val chargeStyle = TextAnimator.Style(fVar = (if (dozeFraction == 0f) aodStyle else textStyle).fontVariation)
        val restoreStyle = TextAnimator.Style(fVar = (if (dozeFraction == 0f) textStyle else aodStyle).fontVariation)
        val animation = TextAnimator.Animation(animate = isAnimationEnabled)
        animators.textAnimator.setTextStyle(
            chargeStyle,
            TextAnimator.Animation(
                animate = isAnimationEnabled,
                onAnimationEnd = {
                    animators.textAnimator.setTextStyle(restoreStyle, animation)
                    animators.outlineAnimator.setTextStyle(restoreStyle, animation)
                    animators.innerAnimator.setTextStyle(restoreStyle, animation)
                    updateTextBoundsForTextAnimator()
                },
            ),
        )
        animators.outlineAnimator.setTextStyle(chargeStyle, animation)
        animators.innerAnimator.setTextStyle(chargeStyle, animation)
        updateTextBoundsForTextAnimator()
    }

    fun refreshText() {
        val text = getText()
        lockScreenPaint.getTextBounds(text, 0, text.length, textBounds)
        animators?.textAnimator?.textInterpolator?.targetPaint?.getTextBounds(text, 0, text.length, targetTextBounds)
        if (layout == null) {
            requestLayout()
            return
        }
        animators?.let {
            it.textAnimator.updateLayout(layout)
            it.outlineAnimator.updateLayout(layout)
            it.innerAnimator.updateLayout(layout)
        }
    }

    private fun isSingleDigit(): Boolean =
        id == ClockViewIds.HOUR_FIRST_DIGIT ||
            id == ClockViewIds.HOUR_SECOND_DIGIT ||
            id == ClockViewIds.MINUTE_FIRST_DIGIT ||
            id == ClockViewIds.MINUTE_SECOND_DIGIT

    private fun updateInterpolatedTextBounds(): Rect {
        animators?.let {
            val progress = it.textAnimator.progress
            if (it.textAnimator.isRunning && progress < 1f) {
                return Rect().apply {
                    left = MathUtils.lerp(prevTextBounds.left.toFloat(), targetTextBounds.left.toFloat(), progress).toInt()
                    right = MathUtils.lerp(prevTextBounds.right.toFloat(), targetTextBounds.right.toFloat(), progress).toInt()
                    top = MathUtils.lerp(prevTextBounds.top.toFloat(), targetTextBounds.top.toFloat(), progress).toInt()
                    bottom = MathUtils.lerp(prevTextBounds.bottom.toFloat(), targetTextBounds.bottom.toFloat(), progress).toInt()
                }
            }
        }
        return Rect(targetTextBounds)
    }

    private fun updateXtranslation(translation: Point, bounds: Rect): Point {
        val width = if (isVertical) measuredHeight else measuredWidth
        translation.x =
            when (horizontalAlignment) {
                HorizontalAlignment.LEFT -> lockScreenPaint.strokeWidth.toInt() - bounds.left
                HorizontalAlignment.RIGHT -> width - bounds.right - lockScreenPaint.strokeWidth.toInt()
                HorizontalAlignment.CENTER -> (width - bounds.width()) / 2 - bounds.left
            }
        return translation
    }

    fun getLocalTranslation(): Point {
        val height = if (isVertical) measuredWidth else measuredHeight
        val bounds = updateInterpolatedTextBounds()
        val translation = Point(0, 0)
        val baseline = if (baseline != -1) baseline else baselineFromMeasure
        translation.y =
            when (verticalAlignment) {
                VerticalAlignment.CENTER -> (height - bounds.height()) / 2 - bounds.top - baseline
                VerticalAlignment.TOP -> (-bounds.top + lockScreenPaint.strokeWidth - baseline).toInt()
                VerticalAlignment.BOTTOM -> height - bounds.bottom - lockScreenPaint.strokeWidth.toInt() - baseline
                VerticalAlignment.BASELINE -> -lockScreenPaint.strokeWidth.toInt()
            }
        return updateXtranslation(translation, bounds)
    }

    override fun applyStyles(assets: AssetLoader, style: TextStyle, aodStyle: TextStyle?) {
        getLogger().i({ "Updating Text Style; LS: $str1, AOD: $str2" }) {
            str1 = style.toString()
            str2 = aodStyle.toString()
        }

        val fontStyle = style as FontTextStyle
        textStyle = fontStyle
        val fontPath = "fonts/${fontStyle.fontFamily}"
        val cache =
            typefaceCache
                ?: assets.typefaceCache.getVariantCache(fontPath).also {
                    typefaceCache = it
                    typefaceName = fontPath
                }
        if (typefaceName != fontPath) {
            getLogger().e({ "Unsupported typeface change from '$str1' to '$str2'" }) {
                str1 = typefaceName
                str2 = fontPath
            }
        }

        lockScreenPaint.strokeJoin = Paint.Join.ROUND
        lockScreenPaint.typeface = cache.getTypefaceForVariant(fontStyle.fontVariation)
        fontStyle.fontFeatureSettings?.let {
            lockScreenPaint.fontFeatureSettings = it
            fontFeatureSettings = it
        }
        typeface = lockScreenPaint.typeface
        fontStyle.lineHeight?.let { lineHeight = it.toInt() }
        fontStyle.borderWidth?.let { textBorderWidth = parser.convert(it) }

        this.aodStyle = if (aodStyle != null && aodStyle is FontTextStyle) aodStyle else fontStyle.copy()
        aodDozingInterpolator = this.aodStyle.transitionInterpolator?.interpolator ?: Interpolators.LINEAR
        aodBorderWidth = parser.convert(this.aodStyle.borderWidth ?: DEFAULT_AOD_STROKE_WIDTH)
        lockScreenPaint.strokeWidth = ceil(max(textBorderWidth, aodBorderWidth))

        measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED)
        setInterpolatorPaint()
        recomputeMaxSingleDigitSizes()
        invalidate()
    }

    override fun applyTextSize(targetFontSizePx: Float?, constrainedByHeight: Boolean) {
        val adjustedFontSizePx = adjustFontSize(targetFontSizePx, constrainedByHeight)
        val fontSizePx = adjustedFontSizePx * (textStyle.fontSizeScale ?: 1f)
        aodFontSizePx = adjustedFontSizePx * (aodStyle.fontSizeScale ?: textStyle.fontSizeScale ?: 1f)
        if (fontSizePx > 0f) {
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, fontSizePx)
            lockScreenPaint.textSize = textSize
            val text = getText()
            lockScreenPaint.getTextBounds(text, 0, text.length, textBounds)
            targetTextBounds.set(textBounds)
        }

        if (!constrainedByHeight) {
            fontSizeAdjustFactor = (textBounds.height() + lockScreenPaint.strokeWidth * 2) / lastUnconstrainedTextSize
        }

        textStyle.borderWidthScale?.let { scale ->
            textBorderWidth = scale * fontSizePx
            if (dozeFraction < 1f) {
                animators?.outlineAnimator?.setTextStyle(TextAnimator.Style(strokeWidth = textBorderWidth))
            }
        }
        aodStyle.borderWidthScale?.let { scale ->
            aodBorderWidth = fontSizePx * scale
            if (dozeFraction > 0f) {
                animators?.outlineAnimator?.setTextStyle(TextAnimator.Style(strokeWidth = aodBorderWidth))
            }
        }

        lockScreenPaint.strokeWidth = ceil(max(textBorderWidth, aodBorderWidth))
        recomputeMaxSingleDigitSizes()

        animators?.let {
            val style = TextAnimator.Style(textSize = lockScreenPaint.textSize)
            it.textAnimator.setTextStyle(style)
            it.outlineAnimator.setTextStyle(style)
            it.innerAnimator.setTextStyle(style)
        }
    }

    private fun recomputeMaxSingleDigitSizes() {
        val rect = Rect()
        maxSingleDigitHeight = 0
        maxSingleDigitWidth = 0
        for (i in 0..9) {
            lockScreenPaint.getTextBounds(i.toString(), 0, 1, rect)
            maxSingleDigitHeight = max(maxSingleDigitHeight, rect.height())
            maxSingleDigitWidth = max(maxSingleDigitWidth, rect.width())
        }
        maxSingleDigitWidth += lockScreenPaint.strokeWidth.toInt() * 2
        maxSingleDigitHeight += lockScreenPaint.strokeWidth.toInt() * 2
    }

    /** Seeds the three animators' target paints from the lockscreen paint. */
    private fun setInterpolatorPaint() {
        val animators = animators ?: return

        animators.textAnimator.textInterpolator.targetPaint.set(lockScreenPaint)
        animators.textAnimator.textInterpolator.onTargetPaintModified()
        animators.textAnimator.setTextStyle(
            TextAnimator.Style(fVar = textStyle.fontVariation, textSize = lockScreenPaint.textSize, color = textFillColor)
        )

        val outlinePaint = TextPaint(lockScreenPaint)
        outlinePaint.style =
            if (aodStyle.renderType == RenderType.HOLLOW_TEXT) Paint.Style.FILL_AND_STROKE else Paint.Style.STROKE
        animators.outlineAnimator.textInterpolator.targetPaint.set(outlinePaint)
        animators.outlineAnimator.textInterpolator.onTargetPaintModified()
        animators.outlineAnimator.setTextStyle(
            TextAnimator.Style(fVar = aodStyle.fontVariation, textSize = lockScreenPaint.textSize, color = Color.TRANSPARENT)
        )

        val innerPaint = TextPaint(lockScreenPaint)
        innerPaint.style = Paint.Style.FILL
        animators.innerAnimator.textInterpolator.targetPaint.set(innerPaint)
        animators.innerAnimator.textInterpolator.onTargetPaintModified()
        animators.innerAnimator.setTextStyle(
            TextAnimator.Style(fVar = aodStyle.fontVariation, textSize = lockScreenPaint.textSize, color = Color.WHITE)
        )
    }

    private fun updateTextBoundsForTextAnimator() {
        val animators = animators ?: return
        val text = getText()
        animators.textAnimator.textInterpolator.basePaint.getTextBounds(text, 0, text.length, prevTextBounds)
        animators.textAnimator.textInterpolator.targetPaint.getTextBounds(text, 0, text.length, targetTextBounds)
    }

    private fun adjustFontSize(targetFontSizePx: Float?, constrainedByHeight: Boolean): Float {
        return if (constrainedByHeight) {
            min((targetFontSizePx ?: 0f) / fontSizeAdjustFactor, lastUnconstrainedTextSize)
        } else {
            lastUnconstrainedTextSize = targetFontSizePx ?: 1f
            lastUnconstrainedTextSize
        }
    }

    companion object {
        private const val DEFAULT_AOD_STROKE_WIDTH = "2dp"
        private const val TEXT_OUTLINE_DEFAULT_COLOR = Color.TRANSPARENT
        private const val AOD_DEFAULT_COLOR = Color.TRANSPARENT
        private const val AOD_OUTLINE_DEFAULT_COLOR = Color.WHITE
    }
}
