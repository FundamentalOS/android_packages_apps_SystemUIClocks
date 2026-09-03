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
import android.view.View
import android.widget.ImageView
import android.widget.RelativeLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.view.children
import com.android.systemui.customization.clocks.DefaultClockFaceLayout
import com.android.systemui.customization.clocks.R as clocksR
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
import com.android.systemui.plugins.keyguard.ui.clocks.ClockPreviewConfig
import com.android.systemui.plugins.keyguard.ui.clocks.ClockTickRate
import com.android.systemui.plugins.keyguard.ui.clocks.ClockViewIds
import com.android.systemui.plugins.keyguard.ui.clocks.ThemeConfig
import com.android.systemui.plugins.keyguard.ui.clocks.TimeFormatKind
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/** An analog hand: artwork rotated about the centre of a square view on every tick. */
class SimpleAnalogHandLayerController(
    private val ctx: Context,
    private val assets: AssetLoader,
    private val layer: AnalogHandLayer,
    messageBuffer: MessageBuffer,
) : SimpleClockLayerController {
    private val logger = Logger(messageBuffer, TAG)
    private var lastLogTime = 0L
    val timespec = AnalogTimespecHandler(layer.timespec, layer.tickMode, layer.timer)

    override val config =
        ClockFaceConfig(
            tickRate =
                when (layer.timespec) {
                    AnalogTimespec.SECONDS ->
                        if (layer.tickMode == AnalogTickMode.SWEEP) ClockTickRate.PER_FRAME
                        else ClockTickRate.PER_SECOND
                    AnalogTimespec.MINUTES ->
                        if (layer.tickMode == AnalogTickMode.SWEEP) ClockTickRate.PER_SECOND
                        else ClockTickRate.PER_MINUTE
                    else -> ClockTickRate.PER_MINUTE
                }
        )

    override val view: View = ImageView(ctx)
    val asset = AssetDrawable(assets, layer.asset)

    init {
        (view as ImageView).setImageDrawable(asset)
        val size = max(asset.intrinsicHeight, asset.intrinsicWidth)
        view.layoutParams =
            RelativeLayout.LayoutParams(size, size).apply {
                addRule(RelativeLayout.CENTER_IN_PARENT, RelativeLayout.TRUE)
            }
    }

    override val events =
        object : ClockEvents {
            override var isReactiveTouchInteractionEnabled = false

            override fun onTimeZoneChanged(timeZone: TimeZone) {
                timespec.setTimeZone(timeZone)
                faceEvents.onTimeTick()
            }

            override fun onTimeFormatChanged(formatKind: TimeFormatKind) {}

            override fun onLocaleChanged(locale: Locale) {}

            override fun onWeatherDataChanged(data: WeatherData) {}

            override fun onAlarmDataChanged(data: AlarmData) {}

            override fun onZenDataChanged(data: ZenData) {}
        }

    override val animations =
        object : ClockAnimations {
            override fun enter() {}

            override fun doze(fraction: Float) = asset.setDozeFraction(fraction)

            override fun fold(fraction: Float) {}

            override fun charge() {}

            override fun onPickerCarouselSwiping(swipingFraction: Float) {}

            override fun onPositionAnimated(args: ClockPositionAnimationArgs) {}

            override fun onFidgetTap(x: Float, y: Float) {}

            override fun onFontAxesChanged(style: ClockAxisStyle) {}
        }

    override val faceEvents =
        object : ClockFaceEvents {
            override fun onTimeTick() {
                timespec.updateTime()
                view.rotation = timespec.rotation * 360f

                // Sweeping second hands tick every frame; rate-limit their logging.
                val now = System.currentTimeMillis()
                if (
                    layer.tickMode == AnalogTickMode.SWEEP &&
                        layer.timespec == AnalogTimespec.SECONDS &&
                        now - lastLogTime <= SWEEP_LOG_RATE_LIMIT_MS
                ) {
                    return
                }
                lastLogTime = now
                logger.d({ "onTimeTick: new rotation=$double1 (degrees)" }) { double1 = view.rotation.toDouble() }
            }

            override fun onThemeChanged(theme: ThemeConfig) {
                asset.setLightFraction(if (theme.isDarkTheme) 1f else 0f)
                asset.updateTints()
            }

            override fun onFontSettingChanged(fontSizePx: Float) {}

            override fun onTargetRegionChanged(targetRegion: Rect?) {}

            override fun onSecondaryDisplayChanged(onSecondaryDisplay: Boolean) {}
        }

    companion object {
        private val TAG = SimpleAnalogHandLayerController::class.simpleName!!
        private const val SWEEP_LOG_RATE_LIMIT_MS = 10000
    }
}

/** Sizes an analog face so its largest layer fits the large-clock height constraint. */
class AnalogClockFaceLayout(view: View, private val assets: AssetLoader) : DefaultClockFaceLayout(view) {
    private var maxHeight = -1
    private var maxWidth = -1

    init {
        for (child in (view as RelativeLayout).children) {
            maxHeight = max(maxHeight, child.layoutParams.height)
            maxWidth = max(maxWidth, child.layoutParams.width)
        }
    }

    override fun applyConstraints(constraints: ConstraintSet): ConstraintSet {
        constraints.constrainHeight(
            ClockViewIds.LOCKSCREEN_CLOCK_VIEW_LARGE,
            view.context.resources.getDimensionPixelSize(clocksR.dimen.large_clock_text_size) * 2,
        )

        val heightScale =
            if (constraints.getHeight(view.id) > 0) constraints.getHeight(view.id) / maxHeight.toFloat() else -1f
        val widthScale =
            if (constraints.getWidth(view.id) > 0) constraints.getWidth(view.id) / maxWidth.toFloat() else -1f
        val scale =
            if (heightScale >= 0f && widthScale >= 0f) min(heightScale, widthScale)
            else if (heightScale < 0f) (if (widthScale >= 0f) widthScale else 1f) else heightScale

        constraints.constrainHeight(view.id, (maxHeight * scale).toInt())
        constraints.constrainWidth(view.id, (scale * maxWidth).toInt())
        return constraints
    }

    override fun applyExternalDisplayPresentationConstraints(constraints: ConstraintSet): ConstraintSet {
        super.applyExternalDisplayPresentationConstraints(constraints)
        applyConstraints(constraints)
        val id = ClockViewIds.LOCKSCREEN_CLOCK_VIEW_LARGE
        constraints.connect(id, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP)
        constraints.connect(id, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM)
        constraints.connect(id, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START)
        constraints.connect(id, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END)
        return constraints
    }

    override fun applyPreviewConstraints(
        clockPreviewConfig: ClockPreviewConfig,
        constraints: ConstraintSet,
    ): ConstraintSet {
        super.applyPreviewConstraints(clockPreviewConfig, constraints)
        applyConstraints(constraints)
        return constraints
    }
}
