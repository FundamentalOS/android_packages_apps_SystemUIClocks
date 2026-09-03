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
import com.android.systemui.log.core.Logger
import com.android.systemui.log.core.MessageBuffer
import com.android.systemui.plugins.keyguard.data.model.AlarmData
import com.android.systemui.plugins.keyguard.data.model.WeatherData
import com.android.systemui.plugins.keyguard.data.model.ZenData
import com.android.systemui.plugins.keyguard.ui.clocks.ClockAnimations
import com.android.systemui.plugins.keyguard.ui.clocks.ClockAxisStyle
import com.android.systemui.plugins.keyguard.ui.clocks.ClockEvents
import com.android.systemui.plugins.keyguard.ui.clocks.ClockFaceConfig
import com.android.systemui.plugins.keyguard.ui.clocks.ClockFaceEvents
import com.android.systemui.plugins.keyguard.ui.clocks.ClockPositionAnimationArgs
import com.android.systemui.plugins.keyguard.ui.clocks.ThemeConfig
import com.android.systemui.plugins.keyguard.ui.clocks.TimeFormatKind
import java.util.Locale

/** A custom [DigitalClockFaceView] hosting several digital hands. */
class ComposedDigitalLayerController(
    private val ctx: Context,
    private val assets: AssetLoader,
    private val layer: ComposedDigitalHandLayer,
    private val isLargeClock: Boolean,
    messageBuffer: MessageBuffer,
) : SimpleClockLayerController {
    private val logger = Logger(messageBuffer, ComposedDigitalLayerController::class.simpleName!!)
    val layerControllers = mutableListOf<SimpleClockLayerController>()
    val dozeState = AnimationState(1f)

    override val view: DigitalClockFaceView =
        when (layer.customizedView) {
            "InflateClockView" -> InflateClockView(ctx, assets, messageBuffer)
            "InflateClockViewSmall" -> InflateClockSmallView(ctx, assets, messageBuffer)
            "NumberOverlap" -> NumberOverlapClockView(ctx, assets, dozeState, messageBuffer)
            "NumberOverlapSmall" -> NumberOverlapSmallClockView(ctx, assets, dozeState, messageBuffer)
            "WeatherClock" -> WeatherDigitalClockViewLarge(ctx, assets, messageBuffer)
            "WeatherClockSmall" -> WeatherDigitalClockViewSmall(ctx, assets, messageBuffer)
            else ->
                CustomClockFaces.createView(layer.customizedView, ctx, assets, messageBuffer)
                    ?: throw IllegalArgumentException("Unrecognized customizedView: ${layer.customizedView}")
        }

    init {
        for (digitalLayer in layer.digitalLayers) {
            val controller = SimpleClockLayerController.Factory.create(ctx, assets, digitalLayer, isLargeClock, messageBuffer)
            view.addView(controller.view)
            layerControllers.add(controller)
        }
    }

    override val config =
        ClockFaceConfig(
            hasCustomWeatherDataDisplay = view.hasCustomWeatherDataDisplay,
            hasCustomPositionUpdatedAnimation = view.hasCustomPositionUpdatedAnimation,
            useCustomClockScene = view.useCustomClockScene,
        )

    override val events =
        object : ClockEvents {
            override var isReactiveTouchInteractionEnabled: Boolean
                get() = view.isReactiveTouchInteractionEnabled
                set(value) {
                    view.isReactiveTouchInteractionEnabled = value
                }

            override fun onTimeZoneChanged(timeZone: TimeZone) {
                layerControllers.forEach { it.events.onTimeZoneChanged(timeZone) }
                refreshTime()
            }

            override fun onTimeFormatChanged(formatKind: TimeFormatKind) {
                layerControllers.forEach { it.events.onTimeFormatChanged(formatKind) }
                refreshTime()
            }

            override fun onLocaleChanged(locale: Locale) {
                layerControllers.forEach { it.events.onLocaleChanged(locale) }
                view.onLocaleChanged(locale)
                refreshTime()
            }

            override fun onWeatherDataChanged(data: WeatherData) = view.onWeatherDataChanged(data)

            override fun onAlarmDataChanged(data: AlarmData) = view.onAlarmDataChanged(data)

            override fun onZenDataChanged(data: ZenData) = view.onZenDataChanged(data)
        }

    override val animations =
        object : ClockAnimations {
            override fun enter() = refreshTime()

            override fun doze(fraction: Float) {
                val (hasChanged, hasJumped) = dozeState.update(fraction)
                if (hasChanged) view.animateDoze(dozeState.isActive, !hasJumped)
                view.dozeFraction = fraction
                view.invalidate()
            }

            override fun fold(fraction: Float) = refreshTime()

            override fun charge() = view.animateCharge()

            override fun onPickerCarouselSwiping(swipingFraction: Float) = view.onPickerCarouselSwiping(swipingFraction)

            override fun onPositionAnimated(anim: ClockPositionAnimationArgs) = view.onPositionAnimated(anim)

            override fun onFidgetTap(x: Float, y: Float) {}

            override fun onFontAxesChanged(style: ClockAxisStyle) {}
        }

    override val faceEvents =
        object : ClockFaceEvents {
            override fun onTimeTick() = refreshTime()

            override fun onThemeChanged(theme: ThemeConfig) = view.updateColors(assets, theme)

            override fun onFontSettingChanged(fontSizePx: Float) = view.onFontSettingChanged(fontSizePx)

            override fun onTargetRegionChanged(targetRegion: Rect?) {}

            override fun onSecondaryDisplayChanged(onSecondaryDisplay: Boolean) {}
        }

    private fun refreshTime() {
        layerControllers.forEach { it.faceEvents.onTimeTick() }
        view.refreshTime()
    }
}
