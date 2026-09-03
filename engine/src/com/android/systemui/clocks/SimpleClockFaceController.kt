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
import android.graphics.Rect
import android.icu.util.TimeZone
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.RelativeLayout
import androidx.core.view.children
import com.android.systemui.customization.clocks.DefaultClockFaceLayout
import com.android.systemui.customization.clocks.R as clocksR
import com.android.systemui.log.core.MessageBuffer
import com.android.systemui.plugins.keyguard.data.model.AlarmData
import com.android.systemui.plugins.keyguard.data.model.WeatherData
import com.android.systemui.plugins.keyguard.data.model.ZenData
import com.android.systemui.plugins.keyguard.ui.clocks.ClockAnimations
import com.android.systemui.plugins.keyguard.ui.clocks.ClockAxisStyle
import com.android.systemui.plugins.keyguard.ui.clocks.ClockEvents
import com.android.systemui.plugins.keyguard.ui.clocks.ClockFaceConfig
import com.android.systemui.plugins.keyguard.ui.clocks.ClockFaceController
import com.android.systemui.plugins.keyguard.ui.clocks.ClockFaceEvents
import com.android.systemui.plugins.keyguard.ui.clocks.ClockFaceLayout
import com.android.systemui.plugins.keyguard.ui.clocks.ClockPositionAnimationArgs
import com.android.systemui.plugins.keyguard.ui.clocks.ClockTickRate
import com.android.systemui.plugins.keyguard.ui.clocks.ClockViewIds
import com.android.systemui.plugins.keyguard.ui.clocks.ThemeConfig
import com.android.systemui.plugins.keyguard.ui.clocks.TimeFormatKind
import java.util.Locale
import kotlin.math.max

interface ClockEventUnion : ClockEvents, ClockFaceEvents

/**
 * Container that, for the vertical / centred digital layouts, shares half the exact height
 * budget with each digit view before measuring.
 */
class SimpleClockRelativeLayout(context: Context, private val faceLayout: DigitalFaceLayout?) :
    RelativeLayout(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (
            MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY &&
                (faceLayout == DigitalFaceLayout.TWO_PAIRS_VERTICAL ||
                    faceLayout == DigitalFaceLayout.FOUR_DIGITS_ALIGN_CENTER)
        ) {
            val size = MeasureSpec.getSize(heightMeasureSpec) / 2f
            for (child in children) {
                (child as SimpleDigitalClockView).applyTextSize(size, true)
            }
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
}

/** One face (large or small) of a design: a stack of layer controllers in a single view. */
class SimpleClockFaceController(
    ctx: Context,
    val assets: AssetLoader,
    private val face: ClockFace,
    private val isLargeClock: Boolean,
    messageBuffer: MessageBuffer,
) : ClockFaceController {
    override var theme = ThemeConfig(true, assets.seedColor)
    val layers = mutableListOf<SimpleClockLayerController>()
    val timespecHandler = DigitalTimespecHandler(DigitalTimespec.TIME_FULL_FORMAT, "hh:mm")

    override val view: View
    override val config: ClockFaceConfig
    override val layout: ClockFaceLayout

    init {
        val lp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        lp.gravity = Gravity.CENTER

        view =
            if (face.layers.size == 1) {
                val controller = SimpleClockLayerController.Factory.create(ctx, assets, face.layers[0], isLargeClock, messageBuffer)
                layers.add(controller)
                controller.view.layoutParams = lp
                controller.view
            } else {
                val container = SimpleClockRelativeLayout(ctx, face.faceLayout)
                container.layoutParams = lp
                container.gravity = Gravity.CENTER
                container.clipChildren = false
                for (layer in face.layers) {
                    face.faceLayout?.let { if (layer is DigitalHandLayer) layer.faceLayout = it }
                    val controller = SimpleClockLayerController.Factory.create(ctx, assets, layer, isLargeClock, messageBuffer)
                    container.addView(controller.view)
                    layers.add(controller)
                }
                container
            }

        config =
            ClockFaceConfig(
                tickRate = getTickRate(),
                hasCustomWeatherDataDisplay = layers.any { it.config.hasCustomWeatherDataDisplay },
                hasCustomPositionUpdatedAnimation = layers.any { it.config.hasCustomPositionUpdatedAnimation },
                useCustomClockScene = layers.any { it.config.useCustomClockScene },
            )

        layout =
            if (view is WeatherDigitalClockViewLarge) {
                WeatherClockFaceLayoutLarge(view, assets, ctx)
            } else {
                view.id = if (isLargeClock) ClockViewIds.LOCKSCREEN_CLOCK_VIEW_LARGE else ClockViewIds.LOCKSCREEN_CLOCK_VIEW_SMALL
                if (face.layers.any { it is AnalogHandLayer }) AnalogClockFaceLayout(view, assets)
                else DefaultClockFaceLayout(view)
            }
    }

    override val events =
        object : ClockEventUnion {
            override var isReactiveTouchInteractionEnabled = false
                set(value) {
                    field = value
                    layers.forEach { it.events.isReactiveTouchInteractionEnabled = value }
                }

            override fun onTimeTick() {
                timespecHandler.updateTime()
                if (config.tickRate == ClockTickRate.PER_MINUTE || view.contentDescription != timespecHandler.getContentDescription()) {
                    view.contentDescription = timespecHandler.getContentDescription()
                }
                layers.forEach { it.faceEvents.onTimeTick() }
            }

            override fun onTimeZoneChanged(timeZone: TimeZone) {
                timespecHandler.setTimeZone(timeZone)
                layers.forEach { it.events.onTimeZoneChanged(timeZone) }
            }

            override fun onTimeFormatChanged(formatKind: TimeFormatKind) {
                timespecHandler.is24Hr = formatKind == TimeFormatKind.FULL_DAY
                layers.forEach { it.events.onTimeFormatChanged(formatKind) }
            }

            override fun onLocaleChanged(locale: Locale) {
                timespecHandler.updateLocale(locale)
                layers.forEach { it.events.onLocaleChanged(locale) }
            }

            override fun onFontSettingChanged(fontSizePx: Float) {
                layers.forEach { it.faceEvents.onFontSettingChanged(fontSizePx) }
            }

            override fun onThemeChanged(theme: ThemeConfig) {
                this@SimpleClockFaceController.theme = theme
                assets.setSeedColor(theme.seedColor, assets.style)
                layers.forEach { it.faceEvents.onThemeChanged(theme) }
            }

            override fun onTargetRegionChanged(targetRegion: Rect?) {
                val v = view
                if (v is DigitalClockFaceView && v.isAlignedWithScreen) {
                    val topMargin = v.context.resources.getDimensionPixelSize(clocksR.dimen.keyguard_large_clock_top_margin)
                    targetRegion?.let { region ->
                        val (_, dy) = v.computeLayoutDiff(region, isLargeClock)
                        if (dy.toInt() != 0) {
                            v.translationY = dy - topMargin / 2
                        }
                    }
                    return
                }

                var maxWidth = 0f
                var maxHeight = 0f
                for (layer in layers) {
                    layer.faceEvents.onTargetRegionChanged(targetRegion)
                    maxWidth = max(maxWidth, layer.view.layoutParams.width.toFloat())
                    maxHeight = max(maxHeight, layer.view.layoutParams.height.toFloat())
                }

                val lp =
                    if (maxHeight <= 0f || maxWidth <= 0f || targetRegion == null) {
                        FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    } else {
                        val ratio =
                            if (maxWidth / maxHeight > targetRegion.width() / targetRegion.height().toFloat())
                                targetRegion.width() / maxWidth
                            else targetRegion.height() / maxHeight
                        FrameLayout.LayoutParams((maxWidth * ratio).toInt(), (maxHeight * ratio).toInt())
                    }
                lp.gravity = Gravity.CENTER
                view.layoutParams = lp

                targetRegion?.let { region ->
                    val (dx, dy) = view.computeLayoutDiff(region, isLargeClock)
                    view.translationX = dx
                    view.translationY = dy
                }
            }

            override fun onSecondaryDisplayChanged(onSecondaryDisplay: Boolean) {}

            override fun onWeatherDataChanged(data: WeatherData) {
                layers.forEach { it.events.onWeatherDataChanged(data) }
            }

            override fun onAlarmDataChanged(data: AlarmData) {
                layers.forEach { it.events.onAlarmDataChanged(data) }
            }

            override fun onZenDataChanged(data: ZenData) {
                layers.forEach { it.events.onZenDataChanged(data) }
            }
        }

    override val animations =
        object : ClockAnimations {
            override fun enter() = layers.forEach { it.animations.enter() }

            override fun doze(fraction: Float) = layers.forEach { it.animations.doze(fraction) }

            override fun fold(fraction: Float) = layers.forEach { it.animations.fold(fraction) }

            override fun charge() = layers.forEach { it.animations.charge() }

            override fun onPickerCarouselSwiping(swipingFraction: Float) {
                face.pickerScale?.let { scale ->
                    view.scaleX = (1 - scale.scaleX) * swipingFraction + scale.scaleX
                    view.scaleY = (1 - scale.scaleY) * swipingFraction + scale.scaleY
                }
                val v = view
                if (!(v is DigitalClockFaceView && v.isAlignedWithScreen)) {
                    view.translationY =
                        view.context.resources.getDimensionPixelSize(clocksR.dimen.keyguard_large_clock_top_margin) / 2f *
                            swipingFraction
                }
                layers.forEach { it.animations.onPickerCarouselSwiping(swipingFraction) }
                view.invalidate()
            }

            override fun onPositionAnimated(args: ClockPositionAnimationArgs) =
                layers.forEach { it.animations.onPositionAnimated(args) }

            override fun onFidgetTap(x: Float, y: Float) {}

            override fun onFontAxesChanged(style: ClockAxisStyle) {}
        }

    private fun getTickRate(): ClockTickRate {
        var rate = ClockTickRate.PER_MINUTE
        for (layer in layers) {
            if (layer.config.tickRate.value < rate.value) rate = layer.config.tickRate
        }
        return rate
    }
}
