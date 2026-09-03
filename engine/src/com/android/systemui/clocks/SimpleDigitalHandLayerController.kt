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
import android.view.ViewGroup
import android.widget.RelativeLayout
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
import com.android.systemui.plugins.keyguard.ui.clocks.ClockViewIds
import com.android.systemui.plugins.keyguard.ui.clocks.ThemeConfig
import com.android.systemui.plugins.keyguard.ui.clocks.TimeFormatKind
import java.util.Locale

/** One digital hand (a digit, pair, full time or date) bound to a [SimpleDigitalClockView]. */
open class SimpleDigitalHandLayerController(
    private val ctx: Context,
    private val assets: AssetLoader,
    private val layer: DigitalHandLayer,
    override val view: View,
    messageBuffer: MessageBuffer,
) : SimpleClockLayerController {
    private val logger = Logger(messageBuffer, TAG)
    val timespec = DigitalTimespecHandler(layer.timespec, layer.dateTimeFormat)
    override val config = ClockFaceConfig()
    var dozeState: AnimationState? = null

    private val digitView: SimpleDigitalClockView
        get() = view as SimpleDigitalClockView

    init {
        view.layoutParams = RelativeLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        layer.alignment?.let { alignment ->
            alignment.verticalAlignment?.let { digitView.verticalAlignment = it }
            alignment.horizontalAlignment?.let { digitView.horizontalAlignment = it }
        }
        digitView.applyStyles(assets, layer.style, layer.aodStyle)
        view.id = layer.lookupDigitalLayerId()
    }

    val hasLeadingZero: Boolean
        get() = layer.dateTimeFormat.startsWith("hh") || timespec.is24Hr

    override val events =
        object : ClockEvents {
            override var isReactiveTouchInteractionEnabled = false

            override fun onTimeZoneChanged(timeZone: TimeZone) {
                timespec.setTimeZone(timeZone)
                refreshTime()
            }

            override fun onTimeFormatChanged(formatKind: TimeFormatKind) {
                timespec.is24Hr = formatKind == TimeFormatKind.FULL_DAY
                refreshTime()
            }

            override fun onLocaleChanged(locale: Locale) {
                timespec.updateLocale(locale)
                refreshTime()
            }

            override fun onWeatherDataChanged(data: WeatherData) {}

            override fun onAlarmDataChanged(data: AlarmData) {}

            override fun onZenDataChanged(data: ZenData) {}
        }

    override val animations =
        object : ClockAnimations {
            override fun enter() {
                applyLayout(layer.faceLayout)
                refreshTime()
            }

            override fun doze(fraction: Float) {
                val state = dozeState
                if (state == null) {
                    val created = AnimationState(fraction)
                    dozeState = created
                    digitView.animateDoze(created.isActive, false)
                } else {
                    val (hasChanged, hasJumped) = state.update(fraction)
                    if (hasChanged) digitView.animateDoze(state.isActive, !hasJumped)
                }
                digitView.dozeFraction = fraction
            }

            override fun fold(fraction: Float) {
                applyLayout(layer.faceLayout)
                refreshTime()
            }

            override fun charge() = digitView.animateCharge()

            override fun onPickerCarouselSwiping(swipingFraction: Float) {}

            override fun onPositionAnimated(args: ClockPositionAnimationArgs) {}

            override fun onFidgetTap(x: Float, y: Float) {}

            override fun onFontAxesChanged(style: ClockAxisStyle) {}
        }

    override val faceEvents =
        object : ClockFaceEvents {
            override fun onTimeTick() {
                refreshTime()
                if (layer.timespec == DigitalTimespec.TIME_FULL_FORMAT || layer.timespec == DigitalTimespec.DATE_FORMAT) {
                    view.contentDescription = timespec.getContentDescription()
                }
            }

            override fun onFontSettingChanged(fontSizePx: Float) {
                digitView.applyTextSize(fontSizePx)
                applyMargin()
            }

            override fun onThemeChanged(theme: ThemeConfig) = digitView.updateColors(assets, theme)

            override fun onTargetRegionChanged(targetRegion: Rect?) {}

            override fun onSecondaryDisplayChanged(onSecondaryDisplay: Boolean) {}
        }

    fun applyLayout(faceLayout: DigitalFaceLayout?) {
        when (faceLayout) {
            DigitalFaceLayout.FOUR_DIGITS_ALIGN_CENTER,
            DigitalFaceLayout.FOUR_DIGITS_HORIZONTAL -> applyFourDigitsLayout(faceLayout)
            DigitalFaceLayout.TWO_PAIRS_HORIZONTAL,
            DigitalFaceLayout.TWO_PAIRS_VERTICAL -> applyTwoPairsLayout(faceLayout)
            null -> {}
        }
        applyMargin()
    }

    private fun applyMargin() {
        val lp = view.layoutParams as? RelativeLayout.LayoutParams ?: return
        layer.marginRatio?.let { ratio ->
            lp.setMargins(
                (ratio.left * view.measuredWidth).toInt(),
                (ratio.top * view.measuredHeight).toInt(),
                (ratio.right * view.measuredWidth).toInt(),
                (ratio.bottom * view.measuredHeight).toInt(),
            )
        }
        view.layoutParams = lp
    }

    private fun applyTwoPairsLayout(faceLayout: DigitalFaceLayout) {
        val lp = view.layoutParams as RelativeLayout.LayoutParams
        lp.addRule(RelativeLayout.ALIGN_BASELINE)
        if (faceLayout == DigitalFaceLayout.TWO_PAIRS_HORIZONTAL) {
            when (view.id) {
                ClockViewIds.HOUR_DIGIT_PAIR -> {
                    lp.addRule(RelativeLayout.CENTER_VERTICAL)
                    lp.addRule(RelativeLayout.ALIGN_PARENT_START)
                }
                ClockViewIds.MINUTE_DIGIT_PAIR -> {
                    lp.addRule(RelativeLayout.CENTER_VERTICAL)
                    lp.addRule(RelativeLayout.END_OF, ClockViewIds.HOUR_DIGIT_PAIR)
                }
                else -> throw Exception("cannot apply two pairs layout to view ${view.id}")
            }
        } else {
            when (view.id) {
                ClockViewIds.HOUR_DIGIT_PAIR -> {
                    lp.addRule(RelativeLayout.CENTER_HORIZONTAL)
                    lp.addRule(RelativeLayout.ALIGN_PARENT_TOP)
                }
                ClockViewIds.MINUTE_DIGIT_PAIR -> {
                    lp.addRule(RelativeLayout.CENTER_HORIZONTAL)
                    lp.addRule(RelativeLayout.BELOW, ClockViewIds.HOUR_DIGIT_PAIR)
                }
                else -> throw Exception("cannot apply two pairs layout to view ${view.id}")
            }
        }
        view.layoutParams = lp
    }

    private fun applyFourDigitsLayout(faceLayout: DigitalFaceLayout) {
        val lp = view.layoutParams as RelativeLayout.LayoutParams
        when (faceLayout) {
            DigitalFaceLayout.FOUR_DIGITS_ALIGN_CENTER ->
                when (view.id) {
                    ClockViewIds.HOUR_FIRST_DIGIT -> {
                        lp.addRule(RelativeLayout.ALIGN_PARENT_START)
                        lp.addRule(RelativeLayout.ALIGN_PARENT_TOP)
                    }
                    ClockViewIds.HOUR_SECOND_DIGIT -> {
                        lp.addRule(RelativeLayout.END_OF, ClockViewIds.HOUR_FIRST_DIGIT)
                        lp.addRule(RelativeLayout.ALIGN_TOP, ClockViewIds.HOUR_FIRST_DIGIT)
                    }
                    ClockViewIds.MINUTE_FIRST_DIGIT -> {
                        lp.addRule(RelativeLayout.ALIGN_START, ClockViewIds.HOUR_FIRST_DIGIT)
                        lp.addRule(RelativeLayout.BELOW, ClockViewIds.HOUR_FIRST_DIGIT)
                    }
                    ClockViewIds.MINUTE_SECOND_DIGIT -> {
                        lp.addRule(RelativeLayout.ALIGN_START, ClockViewIds.HOUR_SECOND_DIGIT)
                        lp.addRule(RelativeLayout.BELOW, ClockViewIds.HOUR_SECOND_DIGIT)
                    }
                    else -> throw Exception("cannot apply four digits layout to view ${view.id}")
                }
            DigitalFaceLayout.FOUR_DIGITS_HORIZONTAL ->
                when (view.id) {
                    ClockViewIds.HOUR_FIRST_DIGIT -> {
                        lp.addRule(RelativeLayout.CENTER_VERTICAL)
                        lp.addRule(RelativeLayout.ALIGN_PARENT_START)
                    }
                    ClockViewIds.HOUR_SECOND_DIGIT -> {
                        lp.addRule(RelativeLayout.CENTER_VERTICAL)
                        lp.addRule(RelativeLayout.END_OF, ClockViewIds.HOUR_FIRST_DIGIT)
                    }
                    ClockViewIds.MINUTE_FIRST_DIGIT -> {
                        lp.addRule(RelativeLayout.CENTER_VERTICAL)
                        lp.addRule(RelativeLayout.END_OF, ClockViewIds.HOUR_SECOND_DIGIT)
                    }
                    ClockViewIds.MINUTE_SECOND_DIGIT -> {
                        lp.addRule(RelativeLayout.CENTER_VERTICAL)
                        lp.addRule(RelativeLayout.END_OF, ClockViewIds.MINUTE_FIRST_DIGIT)
                    }
                    else -> throw Exception("cannot apply FOUR_DIGITS_HORIZONTAL to view ${view.id}")
                }
            else -> throw IllegalArgumentException("applyFourDigitsLayout function should not have parameters as ${layer.faceLayout}")
        }
        if (lp != view.layoutParams) view.layoutParams = lp
    }

    fun refreshTime() {
        timespec.updateTime()
        val text = timespec.getDigitString()
        if (digitView.getText() == text) return
        digitView.setText(text)
        digitView.refreshTime()
        logger.d({ "refreshTime: new text=$str1" }) { str1 = text }
    }

    companion object {
        private val TAG = SimpleDigitalHandLayerController::class.simpleName!!
    }
}
