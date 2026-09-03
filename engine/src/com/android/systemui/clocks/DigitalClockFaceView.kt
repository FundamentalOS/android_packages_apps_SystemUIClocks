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
import android.widget.FrameLayout
import com.android.systemui.customization.clocks.ClockLogger
import com.android.systemui.log.core.MessageBuffer
import com.android.systemui.plugins.keyguard.data.model.AlarmData
import com.android.systemui.plugins.keyguard.data.model.WeatherData
import com.android.systemui.plugins.keyguard.data.model.ZenData
import com.android.systemui.plugins.keyguard.ui.clocks.ClockPositionAnimationArgs
import com.android.systemui.plugins.keyguard.ui.clocks.ThemeConfig
import java.util.Locale

/**
 * Base for the composed clock faces: owns a set of [SimpleDigitalClockTextView]s (which don't draw
 * themselves) and draws them in a custom arrangement.
 */
abstract class DigitalClockFaceView(context: Context, messageBuffer: MessageBuffer) : FrameLayout(context) {
    private val clockLogger = ClockLogger(this, messageBuffer, this::class.simpleName!!)
    protected fun getLogger(): ClockLogger = clockLogger ?: ClockLogger.INIT_LOGGER

    abstract val digitalClockTextViewMap: MutableMap<Int, SimpleDigitalClockTextView>

    var isAnimationEnabled = true
    var dozeFraction = 0f
        set(value) {
            field = value
            digitalClockTextViewMap.forEach { (_, view) -> view.dozeFraction = value }
        }

    val dozeControlState = DozeControlState()
    var isReactiveTouchInteractionEnabled = false

    open val hasCustomPositionUpdatedAnimation = false
    open val hasCustomWeatherDataDisplay = false
    open val useCustomClockScene = false
    open val isAlignedWithScreen = false
    open val text: String? = null

    protected open fun calculateSize(widthMeasureSpec: Int, heightMeasureSpec: Int): Point? = null

    protected open fun calculateLeftTopPosition() {}

    open fun refreshTime() {
        getLogger().refreshTime()
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
        getLogger().invalidate()
        super.invalidate()
    }

    override fun requestLayout() {
        getLogger().requestLayout()
        super.requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        getLogger().onMeasure(widthMeasureSpec, heightMeasureSpec)
        val size = calculateSize(widthMeasureSpec, heightMeasureSpec)
        if (size != null) setMeasuredDimension(size.x, size.y) else super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        calculateLeftTopPosition()
        dozeControlState.animateReady = true
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        getLogger().onLayout(changed, left, top, right, bottom)
        super.onLayout(changed, left, top, right, bottom)
    }

    override fun onDraw(canvas: Canvas) {
        getLogger().onDraw(text)
        super.onDraw(canvas)
    }

    override fun onViewAdded(child: View?) {
        if (child == null) return
        getLogger().onViewAdded(child)
        super.onViewAdded(child)
        if (child is SimpleDigitalClockTextView) {
            digitalClockTextViewMap[child.id] = child
            child.setSkipInvalidateLogging(true)
        }
        child.setWillNotDraw(true)
    }

    open fun animateDoze(isDozing: Boolean, isAnimated: Boolean) {
        digitalClockTextViewMap.forEach { (_, view) -> view.animateDoze(isDozing, isAnimated) }
    }

    open fun animateCharge() {
        digitalClockTextViewMap.forEach { (_, view) -> view.animateCharge() }
    }

    fun updateColors(assets: AssetLoader, theme: ThemeConfig) {
        digitalClockTextViewMap.forEach { (_, view) -> view.updateColors(assets, theme) }
        invalidate()
    }

    open fun onFontSettingChanged(fontSizePx: Float) {
        digitalClockTextViewMap.forEach { (_, view) -> view.applyTextSize(fontSizePx) }
    }

    open fun onPositionAnimated(args: ClockPositionAnimationArgs) {}

    open fun onPickerCarouselSwiping(swipingFraction: Float) {}

    open fun onLocaleChanged(locale: Locale) {}

    open fun onWeatherDataChanged(data: WeatherData) {}

    open fun onAlarmDataChanged(data: AlarmData) {}

    open fun onZenDataChanged(data: ZenData) {}

    /** Defers a doze animation until the view has been measured at least once. */
    class DozeControlState {
        private var animateDoze: () -> Unit = {}
        var animateReady = false
            set(value) {
                if (value) {
                    animateDoze()
                    animateDoze = {}
                }
                field = value
            }

        fun setAnimateDoze(action: () -> Unit) {
            if (animateReady) {
                action()
                animateDoze = {}
            } else {
                animateDoze = action
            }
        }
    }
}
