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
import android.icu.util.TimeZone
import com.android.systemui.customization.clocks.ClockLogger
import com.android.systemui.plugins.keyguard.data.model.AlarmData
import com.android.systemui.plugins.keyguard.data.model.WeatherData
import com.android.systemui.plugins.keyguard.data.model.ZenData
import com.android.systemui.plugins.keyguard.ui.clocks.ClockConfig
import com.android.systemui.plugins.keyguard.ui.clocks.ClockController
import com.android.systemui.plugins.keyguard.ui.clocks.ClockEventListeners
import com.android.systemui.plugins.keyguard.ui.clocks.ClockEvents
import com.android.systemui.plugins.keyguard.ui.clocks.ClockMessageBuffers
import com.android.systemui.plugins.keyguard.ui.clocks.TimeFormatKind
import java.io.PrintWriter
import java.util.Locale

/** Top-level controller for one design: a large face and a small face. */
class SimpleClockController(
    private val ctx: Context,
    private val assets: AssetLoader,
    private val design: ClockDesign,
    private val messageBuffers: ClockMessageBuffers?,
) : ClockController {
    override val smallClock: SimpleClockFaceController
    override val largeClock: SimpleClockFaceController
    override val config: ClockConfig
    override val eventListeners = ClockEventListeners()

    init {
        val smallBuffer = messageBuffers?.smallClockMessageBuffer ?: ClockLogger.DEFAULT_MESSAGE_BUFFER
        smallClock =
            SimpleClockFaceController(
                ctx,
                assets.copy(messageBuffer = smallBuffer),
                design.small ?: design.large!!,
                false,
                smallBuffer,
            )

        val largeBuffer = messageBuffers?.largeClockMessageBuffer ?: ClockLogger.DEFAULT_MESSAGE_BUFFER
        largeClock =
            SimpleClockFaceController(
                ctx,
                assets.copy(messageBuffer = largeBuffer),
                design.large ?: design.small!!,
                true,
                largeBuffer,
            )

        config =
            ClockConfig(
                id = design.id,
                name = design.name?.let { assets.tryReadString(it) ?: it } ?: "",
                description = design.description?.let { assets.tryReadString(it) ?: it } ?: "",
                // Stock behaviour: a clock with its own weather display (Weather) moves with the
                // smartspace in AOD; faces can also opt in explicitly.
                useAlternateSmartspaceAODTransition =
                    smallClock.config.hasCustomWeatherDataDisplay ||
                        largeClock.config.hasCustomWeatherDataDisplay ||
                        smallClock.useAlternateSmartspaceAODTransition ||
                        largeClock.useAlternateSmartspaceAODTransition,
                useCustomClockScene = smallClock.config.useCustomClockScene || largeClock.config.useCustomClockScene,
            )
    }

    override val events =
        object : ClockEvents {
            override var isReactiveTouchInteractionEnabled = false
                set(value) {
                    field = value
                    smallClock.events.isReactiveTouchInteractionEnabled = value
                    largeClock.events.isReactiveTouchInteractionEnabled = value
                }

            override fun onTimeZoneChanged(timeZone: TimeZone) {
                smallClock.events.onTimeZoneChanged(timeZone)
                largeClock.events.onTimeZoneChanged(timeZone)
            }

            override fun onTimeFormatChanged(formatKind: TimeFormatKind) {
                smallClock.events.onTimeFormatChanged(formatKind)
                largeClock.events.onTimeFormatChanged(formatKind)
            }

            override fun onLocaleChanged(locale: Locale) {
                smallClock.events.onLocaleChanged(locale)
                largeClock.events.onLocaleChanged(locale)
            }

            override fun onWeatherDataChanged(data: WeatherData) {
                smallClock.events.onWeatherDataChanged(data)
                largeClock.events.onWeatherDataChanged(data)
            }

            override fun onAlarmDataChanged(data: AlarmData) {
                smallClock.events.onAlarmDataChanged(data)
                largeClock.events.onAlarmDataChanged(data)
            }

            override fun onZenDataChanged(data: ZenData) {
                smallClock.events.onZenDataChanged(data)
                largeClock.events.onZenDataChanged(data)
            }
        }

    override fun initialize(isDarkTheme: Boolean, dozeFraction: Float, foldFraction: Float) {
        for (face in listOf(smallClock, largeClock)) {
            face.events.onThemeChanged(face.theme.copy(isDarkTheme = isDarkTheme))
            face.animations.doze(dozeFraction)
            face.animations.fold(foldFraction)
            face.events.onTimeTick()
        }
    }

    override fun dump(pw: PrintWriter) {}
}
