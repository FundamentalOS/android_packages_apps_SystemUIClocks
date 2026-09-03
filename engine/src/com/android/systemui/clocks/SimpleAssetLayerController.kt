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

/** A static artwork layer, centred at its intrinsic size. */
class SimpleAssetLayerController(
    private val ctx: Context,
    private val assets: AssetLoader,
    private val layer: AssetLayer,
) : SimpleClockLayerController {
    override val config = ClockFaceConfig()
    override val view: View = ImageView(ctx)
    val asset = AssetDrawable(assets, layer.asset)

    init {
        (view as ImageView).setImageDrawable(asset)
        view.layoutParams =
            RelativeLayout.LayoutParams(asset.intrinsicWidth, asset.intrinsicHeight).apply {
                addRule(RelativeLayout.CENTER_IN_PARENT, RelativeLayout.TRUE)
            }
    }

    override val events =
        object : ClockEvents {
            override var isReactiveTouchInteractionEnabled = false

            override fun onTimeZoneChanged(timeZone: TimeZone) {}

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
            override fun onTimeTick() {}

            override fun onThemeChanged(theme: ThemeConfig) {
                asset.setLightFraction(if (theme.isDarkTheme) 1f else 0f)
                asset.updateTints()
            }

            override fun onFontSettingChanged(fontSizePx: Float) {}

            override fun onTargetRegionChanged(targetRegion: Rect?) {}

            override fun onSecondaryDisplayChanged(onSecondaryDisplay: Boolean) {}
        }
}
