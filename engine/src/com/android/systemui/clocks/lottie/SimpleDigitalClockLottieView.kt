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
package com.android.systemui.clocks.lottie

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.os.Trace
import android.util.AttributeSet
import android.view.View
import com.airbnb.lottie.LottieComposition
import com.airbnb.lottie.LottieCompositionFactory
import com.airbnb.lottie.LottieDrawable
import com.airbnb.lottie.LottieProperty
import com.airbnb.lottie.RenderMode
import com.airbnb.lottie.model.KeyPath
import com.android.app.animation.Interpolators
import com.android.systemui.clocks.AssetLoader
import com.android.systemui.clocks.DimensionParser
import com.android.systemui.clocks.HorizontalAlignment
import com.android.systemui.clocks.LottieTextStyle
import com.android.systemui.clocks.SimpleDigitalClockView
import com.android.systemui.clocks.TextStyle
import com.android.systemui.clocks.VerticalAlignment
import com.android.systemui.customization.clocks.ClockLogger
import com.android.systemui.log.core.MessageBuffer
import com.android.systemui.plugins.keyguard.ui.clocks.ThemeConfig
import java.io.FileNotFoundException
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

fun readLottieComposition(assets: AssetLoader, path: String): LottieComposition? {
    return try {
        LottieCompositionFactory.fromJsonStringSync(assets.readTextAsset(path), path).value
    } catch (ex: FileNotFoundException) {
        assets.logger.w({ "Failed to find file at $str1" }) { str1 = path }
        null
    }
}

/** Per-size cache of the ten digit animations (and colon) shared by every digit view. */
class TransitClockLottieAssets {
    private var numbers: List<LottieDrawable> = listOf()
    private var colon: LottieDrawable? = null

    fun getNumbersList(assets: AssetLoader, paths: List<String>): List<LottieDrawable> {
        if (numbers.isEmpty()) {
            numbers = List(10) { LottieDrawable() }
            paths.forEachIndexed { i, path ->
                numbers[i].setComposition(readLottieComposition(assets, path))
                numbers[i].renderMode = RenderMode.HARDWARE
            }
        }
        return numbers
    }

    fun getColon(assets: AssetLoader, path: String): LottieDrawable =
        colon ?: LottieDrawable().also {
            it.setComposition(readLottieComposition(assets, path))
            colon = it
        }
}

/** Runs one 0..1 sweep through a quarter of the lottie timeline. */
class LottieAnimator(val updateCallback: (Float) -> Unit, val startCallback: () -> Unit) {
    private val loopAnimator: ValueAnimator =
        ValueAnimator.ofFloat(1f).apply {
            addUpdateListener { updateCallback(it.animatedValue as Float) }
            addListener(
                object : AnimatorListenerAdapter() {
                    override fun onAnimationStart(animation: Animator) = startCallback()
                }
            )
        }

    fun start() {
        if (loopAnimator.isRunning) loopAnimator.cancel()
        loopAnimator.duration = DEFAULT_ANIMATION_DURATION
        loopAnimator.interpolator = Interpolators.EMPHASIZED
        loopAnimator.start()
    }

    companion object {
        private const val DEFAULT_ANIMATION_DURATION = 800L
    }
}

/** Digit view rendering each character from a lottie animation. */
class SimpleDigitalClockLottieView(
    context: Context,
    private val isLargeClock: Boolean,
    messageBuffer: MessageBuffer,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr), SimpleDigitalClockView {
    private val logger = ClockLogger(this, messageBuffer, TAG)
    private var assets: AssetLoader? = null
    var numbers: List<LottieDrawable> = listOf()
        private set
    private var colon: LottieDrawable? = null
    private var lightColorMap: Map<String, String> = mapOf()
    private var darkColorMap: Map<String, String> = mapOf()
    private val parser = DimensionParser(context)
    lateinit var textStyle: LottieTextStyle

    override var verticalAlignment = VerticalAlignment.CENTER
    override var horizontalAlignment = HorizontalAlignment.CENTER
    var isAnimationEnabled = true
    private var lastUnconstrainedFontSizePx = Float.MAX_VALUE
    private var letterSpacing = 0f
    private var paddingVertical = 0f
    private var paddingHorizontal = 0f
    private var innerWidth = 0f
    private var innerHeight = 0f
    private var animationState = 0
    private var lastProgress = 0f
    private var startProgress = -1f

    private var text = ""

    override fun getText(): String = text

    override fun setText(text: String) {
        this.text = text
        requestLayout()
    }

    override var dozeFraction = 0f
        set(value) {
            field = value
            refreshAlphaByText(value)
            invalidate()
        }

    private val lottieAnimator =
        LottieAnimator(
            updateCallback = { progress ->
                val start = getStartProgress()
                val end = if (start > getEndProgress()) getEndProgress() + 1 else getEndProgress()
                val current = (end - start) * progress + start
                syncLottieProgress(current - floor(current))
                invalidate()
            },
            startCallback = {
                startProgress = lastProgress
                updateAnimationState()
                logger.d({ "onAnimationStart startProgress $double1" }) { double1 = getStartProgress().toDouble() }
            },
        )

    init {
        setWillNotDraw(false)
        setLayerType(
            LAYER_TYPE_SOFTWARE,
            Paint().apply {
                isDither = true
                isAntiAlias = true
                isFilterBitmap = true
            },
        )
    }

    override fun refreshTime() {
        refreshAlphaByText(dozeFraction)
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        logger.onMeasure(widthMeasureSpec, heightMeasureSpec)
        innerWidth = 0f
        innerHeight = 0f
        for (c in text) {
            val bounds = getDrawable(c, numbers, colon)?.bounds ?: continue
            if (innerWidth > 0f) innerWidth += letterSpacing
            innerHeight = max(innerHeight, bounds.height().toFloat())
            innerWidth += bounds.width()
        }
        innerWidth += paddingHorizontal * 2
        innerHeight += paddingVertical * 2

        val widthMode = MeasureSpec.getMode(widthMeasureSpec)
        var width = MeasureSpec.getSize(widthMeasureSpec)
        val heightMode = MeasureSpec.getMode(heightMeasureSpec)
        var height = MeasureSpec.getSize(heightMeasureSpec)
        when (widthMode) {
            MeasureSpec.AT_MOST -> width = min(width, innerWidth.toInt())
            MeasureSpec.EXACTLY -> {}
            else -> width = innerWidth.toInt()
        }
        when (heightMode) {
            MeasureSpec.AT_MOST -> height = min(height, innerHeight.toInt())
            MeasureSpec.EXACTLY -> {}
            else -> height = innerHeight.toInt()
        }
        setMeasuredDimension(width, height)
    }

    override fun updateColors(assets: AssetLoader, theme: ThemeConfig) {
        val colorMap = if (theme.isDarkTheme) lightColorMap else darkColorMap
        val aodColor = assets.readColor("@android:color/system_accent1_100")
        colorMap.forEach { (layerName, colorRef) ->
            val color = assets.readColor(colorRef)
            for (drawable in numbers) {
                drawable.addValueCallback(KeyPath("**", layerName), LottieProperty.STROKE_COLOR) { color }
                drawable.addValueCallback(KeyPath("**", "#FFFFFF"), LottieProperty.STROKE_COLOR) { aodColor }
                drawable.addValueCallback(KeyPath("**", "#FFFFFF"), LottieProperty.COLOR) { aodColor }
            }
        }
        invalidate()
    }

    override fun applyStyles(assets: AssetLoader, style: TextStyle, aodStyle: TextStyle?) {
        logger.d("applyTextStyle")
        this.assets = assets
        val lottieStyle = style as LottieTextStyle
        textStyle = lottieStyle
        val sharedAssets = if (isLargeClock) largeTransitClockAssets else smallTransitClockAssets
        if (numbers.isEmpty()) numbers = sharedAssets.getNumbersList(assets, lottieStyle.numbers)
        colon = lottieStyle.colon?.let { sharedAssets.getColon(assets, it) }
        lottieStyle.fillColorLightMap?.let { lightColorMap = it }
        lottieStyle.fillColorDarkMap?.let { darkColorMap = it }
    }

    override fun applyTextSize(targetFontSizePx: Float?, constrainedByHeight: Boolean) {
        val fontSizePx = adjustTextSize(targetFontSizePx, constrainedByHeight)
        logger.d("applyTextSize")
        val scale = fontSizePx * (textStyle.fontSizeScale ?: 1f) / numbers[0].intrinsicHeight
        letterSpacing = parser.convert(textStyle.spacing) * scale
        paddingVertical = parser.convert(textStyle.paddingVertical) * scale
        paddingHorizontal = parser.convert(textStyle.paddingHorizontal) * scale
        for (drawable in numbers + colon) {
            if (drawable == null) return
            drawable.setBounds(
                0,
                0,
                (drawable.intrinsicWidth * scale - paddingHorizontal * 2).toInt(),
                (drawable.intrinsicHeight * scale - paddingVertical * 2).toInt(),
            )
        }
        refreshTime()
    }

    override fun animateDoze(isDozing: Boolean, isAnimated: Boolean) {
        if (isAnimated) lottieAnimator.start()
    }

    override fun animateCharge() = lottieAnimator.start()

    override fun setVisibility(visibility: Int) {
        logger.setVisibility(visibility)
        super.setVisibility(visibility)
    }

    override fun setAlpha(alpha: Float) {
        logger.setAlpha(alpha)
        super.setAlpha(alpha)
    }

    override fun invalidate() {
        logger.invalidate()
        super.invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        logger.onDraw(text)
        drawNumbers(canvas) { getDrawable(it, numbers, colon) }
    }

    fun adjustTextSize(targetFontSizePx: Float?, constrainedByHeight: Boolean): Float {
        return if (constrainedByHeight) {
            min(targetFontSizePx ?: 0f, lastUnconstrainedFontSizePx)
        } else {
            lastUnconstrainedFontSizePx = targetFontSizePx ?: 0f
            lastUnconstrainedFontSizePx
        }
    }

    private fun refreshAlphaByText(fraction: Float) {
        for (c in text) {
            val drawable = getDrawable(c, numbers, colon)
            for (layerName in COLOR_STROKE_KEYPATH_LIST) {
                drawable?.addValueCallback(KeyPath("**", layerName), LottieProperty.OPACITY) { ((1 - fraction) * 100f).toInt() }
            }
            drawable?.addValueCallback(KeyPath("**", "#FFFFFF"), LottieProperty.OPACITY) { (fraction * 100f).toInt() }
        }
    }

    private fun drawNumbers(canvas: Canvas, drawableFor: (Char) -> Drawable?) {
        canvas.save()
        canvas.translate(paddingLeft.toFloat(), paddingTop.toFloat())
        canvas.translate(getLocalTranslationX(), getLocalTranslationY())
        var missing: StringBuilder? = null
        for (c in text) {
            val drawable = drawableFor(c)
            if (drawable != null) {
                Trace.beginSection("drawNumbers $text")
                drawable.draw(canvas)
                Trace.endSection()
                canvas.translate(drawable.bounds.width() + letterSpacing, 0f)
            } else {
                (missing ?: StringBuilder().also { missing = it }).append(c)
            }
        }
        missing?.let { logger.e({ "Didn't find drawables for '$str1'" }) { str1 = it.toString() } }
        canvas.restore()
    }

    private fun getLocalTranslationY(): Float =
        when (verticalAlignment) {
            VerticalAlignment.CENTER -> (measuredHeight - innerHeight) / 2f
            VerticalAlignment.BOTTOM -> measuredHeight - innerHeight
            else -> 0f
        }

    private fun getLocalTranslationX(): Float =
        when (horizontalAlignment) {
            HorizontalAlignment.CENTER -> (measuredWidth - innerWidth) / 2f
            HorizontalAlignment.RIGHT -> measuredWidth - innerWidth
            else -> 0f
        }

    private fun getDrawable(c: Char, numbers: List<LottieDrawable>, colon: LottieDrawable?): LottieDrawable? {
        if (c == ':') return colon
        val digit = c.digitToIntOrNull() ?: return null
        return numbers[digit]
    }

    private fun syncLottieProgress(progress: Float) {
        lastProgress = progress
        for (c in text.toSet()) getDrawable(c, numbers, colon)?.progress = progress
        logger.d({ "syncLottieProgress progress $double1" }) { double1 = progress.toDouble() }
    }

    private fun updateAnimationState() {
        startProgress = getStartProgress() - floor(getStartProgress())
        animationState = Math.rint((getStartProgress() / LOOP_INTERVAL).toDouble()).toInt() % ANIMATE_TOTAL_STATE
        logger.d({ "updateAnimationState $int1" }) { int1 = animationState }
    }

    fun getStartProgress(): Float = if (startProgress == -1f) animationState * LOOP_INTERVAL else startProgress

    fun getEndProgress(): Float = (animationState + 1) * LOOP_INTERVAL

    companion object {
        private val TAG = SimpleDigitalClockLottieView::class.simpleName!!
        private const val ANIMATE_TOTAL_STATE = 4
        private const val LOOP_INTERVAL = 1f / ANIMATE_TOTAL_STATE
        private val largeTransitClockAssets = TransitClockLottieAssets()
        private val smallTransitClockAssets = TransitClockLottieAssets()
        private val COLOR_STROKE_KEYPATH_LIST = listOf("Color 1", "Color 2", "Color 3", "Color 4")
    }
}
