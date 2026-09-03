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
import android.content.res.Resources
import android.graphics.Point
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.dimensionResource
import androidx.constraintlayout.widget.ConstraintSet
import androidx.constraintlayout.widget.ConstraintSet.BOTTOM
import androidx.constraintlayout.widget.ConstraintSet.END
import androidx.constraintlayout.widget.ConstraintSet.PARENT_ID
import androidx.constraintlayout.widget.ConstraintSet.START
import androidx.constraintlayout.widget.ConstraintSet.TOP
import androidx.constraintlayout.widget.ConstraintSet.WRAP_CONTENT
import com.android.compose.animation.scene.ElementContentScope
import com.android.compose.animation.scene.ElementKey
import com.android.compose.animation.scene.MovableElementContentScope
import com.android.compose.animation.scene.MovableElementKey
import com.android.systemui.customization.clocks.DefaultClockFaceLayout
import com.android.systemui.customization.clocks.R as clocksR
import com.android.systemui.customization.clocks.utils.ContextUtils.getSafeStatusBarHeight
import com.android.systemui.plugins.keyguard.ui.clocks.AodClockBurnInModel
import com.android.systemui.plugins.keyguard.ui.clocks.ClockFaceLayout
import com.android.systemui.plugins.keyguard.ui.clocks.ClockPreviewConfig
import com.android.systemui.plugins.keyguard.ui.clocks.ClockViewIds
import com.android.systemui.plugins.keyguard.ui.composable.elements.BaseLockscreenElement
import com.android.systemui.plugins.keyguard.ui.composable.elements.LockscreenElement
import com.android.systemui.plugins.keyguard.ui.composable.elements.LockscreenElementKeys
import com.android.systemui.plugins.keyguard.ui.composable.elements.LockscreenScope
import com.android.systemui.plugins.keyguard.ui.composable.elements.MovableLockscreenElement

object WeatherClockElementKeys {
    val Time = MovableElementKey("WeatherClockTime", LockscreenElementKeys.ContentPicker)
    val Date = MovableElementKey("WeatherClockDate", LockscreenElementKeys.ContentPicker)
    val StateIcon = MovableElementKey("WeatherClockStateIcon", LockscreenElementKeys.ContentPicker)
    val Temperature = MovableElementKey("WeatherClockTemperature", LockscreenElementKeys.ContentPicker)
    val AlarmDND = MovableElementKey("WeatherClockAlarmDND", LockscreenElementKeys.ContentPicker)
}

/**
 * Splits the large weather clock into independently placed lockscreen elements (time, date,
 * icon, alarm/DND, temperature) around the smartspace.
 */
class WeatherClockFaceLayoutLarge(
    val view: WeatherDigitalClockViewLarge,
    private val assets: AssetLoader,
    private val context: Context,
    private val resources: Resources = context.resources,
) : ClockFaceLayout {
    init {
        view.dateView.verticalAlignment = VerticalAlignment.TOP
        view.weatherIconView.verticalAlignment = VerticalAlignment.TOP
        view.alarmDndIconView.verticalAlignment = VerticalAlignment.TOP
        view.alarmDndIconView.horizontalAlignment = HorizontalAlignment.RIGHT
        view.temperatureView.verticalAlignment = VerticalAlignment.BOTTOM
        view.timeView.verticalAlignment = VerticalAlignment.CENTER
        view.dateView.isVertical = true
        view.temperatureView.isVertical = true
        replaceViewId()
    }

    val smartspaceHeight: Int
        get() = resources.getDimensionPixelSize(clocksR.dimen.enhanced_smartspace_height)

    val smartspaceBottomMargin: Int
        get() = resources.getDimensionPixelSize(clocksR.dimen.weather_clock_smartspace_bottom_margin)

    @Deprecated("Unsupported with flexiglass. Move to composables.")
    override val views: List<View>
        get() =
            view.digitalClockTextViewMap.values.map { child ->
                view.removeView(child)
                child.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                child.setWillNotDraw(false)
                child
            }

    override val elements: List<BaseLockscreenElement> by lazy {
        for (child in view.digitalClockTextViewMap.values) {
            view.removeView(child)
            child.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            child.setWillNotDraw(false)
        }
        view.alarmDndIconView.setSingleLine(true)
        listOf(
            WeatherElement(WeatherClockElementKeys.Time, view.timeView) {
                Modifier.padding(horizontal = dimensionResource(clocksR.dimen.clock_padding_start))
            },
            WeatherElement(WeatherClockElementKeys.Date, view.dateView) {
                Modifier.padding(start = dimensionResource(clocksR.dimen.clock_padding_start))
            },
            WeatherElement(WeatherClockElementKeys.StateIcon, view.weatherIconView) {
                Modifier.padding(start = dimensionResource(clocksR.dimen.weather_date_icon_padding))
            },
            WeatherElement(WeatherClockElementKeys.AlarmDND, view.alarmDndIconView) {
                Modifier.padding(end = dimensionResource(clocksR.dimen.clock_padding_start))
            },
            WeatherElement(WeatherClockElementKeys.Temperature, view.temperatureView) {
                Modifier.padding(end = dimensionResource(clocksR.dimen.clock_padding_start))
            },
            LargeWeatherRegionElement(),
        )
    }

    private inner class WeatherElement(
        override val key: MovableElementKey,
        private val targetView: View,
        private val modifier: @Composable MovableElementContentScope.() -> Modifier,
    ) : MovableLockscreenElement {
        override val context: Context = view.context

        @Composable
        override fun LockscreenScope<MovableElementContentScope>.LockscreenElement() {
            DefaultClockFaceLayout.clockView(targetView, contentScope.modifier().then(this.context.burnInModifier))
        }
    }

    private inner class LargeWeatherRegionElement : LockscreenElement {
        override val key: ElementKey = LockscreenElementKeys.Region.Clock.Large
        override val context: Context = view.context

        @Composable
        override fun LockscreenScope<ElementContentScope>.LockscreenElement() {
            Layout(
                content = {
                    LockscreenElement(WeatherClockElementKeys.Time, Modifier)
                    LockscreenElement(WeatherClockElementKeys.Date, Modifier)
                    LockscreenElement(WeatherClockElementKeys.StateIcon, Modifier)
                    LockscreenElement(WeatherClockElementKeys.AlarmDND, Modifier)
                    LockscreenElement(WeatherClockElementKeys.Temperature, Modifier)
                    LockscreenElement(
                        LockscreenElementKeys.Smartspace.Cards,
                        Modifier.heightIn(min = dimensionResource(clocksR.dimen.enhanced_smartspace_height)),
                    )
                }
            ) { measurables, constraints ->
                check(measurables.size == 6)
                val (timeM, dateM, iconM, alarmM, tempM) = measurables
                val smartspaceM = measurables[5]
                val childConstraints = constraints.copy(minWidth = 0, minHeight = 0)
                val time = timeM.measure(childConstraints)
                val date = dateM.measure(childConstraints)
                val icon = iconM.measure(childConstraints)
                val alarm = alarmM.measure(childConstraints)
                val temp = tempM.measure(childConstraints)
                val smartspace = smartspaceM.measure(childConstraints)
                layout(constraints.maxWidth, constraints.maxHeight) {
                    time.placeRelative(0, 0)
                    smartspace.placeRelative(0, time.measuredHeight)
                    val top = time.measuredHeight + smartspace.measuredHeight
                    date.placeRelative(0, top)
                    icon.placeRelative(date.measuredWidth, top)
                    alarm.placeRelative(constraints.maxWidth - alarm.measuredWidth, top)
                    temp.placeRelative(constraints.maxWidth - temp.measuredWidth, top + date.measuredHeight - temp.measuredHeight)
                }
            }
        }
    }

    @Deprecated("Unsupported with flexiglass. Move to composables.")
    override fun applyConstraints(constraints: ConstraintSet): ConstraintSet {
        val paddingStart = resources.getDimensionPixelSize(clocksR.dimen.clock_padding_start)
        val largeClockEnd = constraints.getConstraint(ClockViewIds.LOCKSCREEN_CLOCK_VIEW_LARGE)?.layout?.endToEnd ?: -1
        val iconPadding = resources.getDimensionPixelSize(clocksR.dimen.weather_date_icon_padding)

        constraints.constrainWidth(ClockViewIds.WEATHER_CLOCK_TIME, WRAP_CONTENT)
        constraints.constrainHeight(ClockViewIds.WEATHER_CLOCK_TIME, WRAP_CONTENT)
        constraints.connect(ClockViewIds.WEATHER_CLOCK_TIME, TOP, PARENT_ID, TOP, view.statusBarHeight)
        constraints.connect(ClockViewIds.WEATHER_CLOCK_TIME, START, PARENT_ID, START, paddingStart)
        constraints.connect(assets.getResourcesId(BC_SMARTSPACE_VIEW), TOP, ClockViewIds.WEATHER_CLOCK_TIME, BOTTOM)

        constraints.constrainWidth(ClockViewIds.WEATHER_CLOCK_DATE, WRAP_CONTENT)
        constraints.constrainHeight(ClockViewIds.WEATHER_CLOCK_DATE, WRAP_CONTENT)
        constraints.connect(
            ClockViewIds.WEATHER_CLOCK_DATE,
            TOP,
            ClockViewIds.WEATHER_CLOCK_DATE_BARRIER_BOTTOM,
            BOTTOM,
            smartspaceBottomMargin,
        )
        constraints.connect(ClockViewIds.WEATHER_CLOCK_DATE, START, PARENT_ID, START, paddingStart)

        constraints.constrainWidth(view.weatherIconView.id, WRAP_CONTENT)
        constraints.constrainHeight(view.weatherIconView.id, WRAP_CONTENT)
        constraints.connect(view.weatherIconView.id, START, ClockViewIds.WEATHER_CLOCK_DATE, END, iconPadding)
        constraints.connect(view.weatherIconView.id, TOP, ClockViewIds.WEATHER_CLOCK_DATE, TOP)

        constraints.constrainWidth(view.alarmDndIconView.id, WRAP_CONTENT)
        constraints.constrainHeight(view.alarmDndIconView.id, WRAP_CONTENT)
        constraints.connect(view.alarmDndIconView.id, END, view.temperatureView.id, END)
        constraints.connect(view.alarmDndIconView.id, TOP, ClockViewIds.WEATHER_CLOCK_DATE, TOP)
        constraints.setGoneMargin(view.alarmDndIconView.id, TOP, smartspaceHeight + smartspaceBottomMargin)

        constraints.constrainWidth(view.temperatureView.id, WRAP_CONTENT)
        constraints.constrainHeight(view.temperatureView.id, WRAP_CONTENT)
        constraints.connect(view.temperatureView.id, BOTTOM, ClockViewIds.WEATHER_CLOCK_DATE, BOTTOM)
        constraints.connect(view.temperatureView.id, END, if (largeClockEnd == -1) PARENT_ID else largeClockEnd, END, paddingStart)
        return constraints
    }

    @Deprecated("Unsupported with flexiglass. Move to composables.")
    override fun applyPreviewConstraints(clockPreviewConfig: ClockPreviewConfig, constraints: ConstraintSet): ConstraintSet {
        val paddingStart = resources.getDimensionPixelSize(clocksR.dimen.clock_padding_start)
        val iconPadding = resources.getDimensionPixelSize(clocksR.dimen.weather_date_icon_padding)

        constraints.constrainWidth(ClockViewIds.WEATHER_CLOCK_TIME, WRAP_CONTENT)
        constraints.constrainHeight(ClockViewIds.WEATHER_CLOCK_TIME, WRAP_CONTENT)
        constraints.connect(ClockViewIds.WEATHER_CLOCK_TIME, TOP, PARENT_ID, TOP, context.getSafeStatusBarHeight())
        constraints.connect(ClockViewIds.WEATHER_CLOCK_TIME, START, PARENT_ID, START, paddingStart)

        constraints.constrainWidth(ClockViewIds.WEATHER_CLOCK_DATE, WRAP_CONTENT)
        constraints.constrainHeight(ClockViewIds.WEATHER_CLOCK_DATE, WRAP_CONTENT)
        constraints.connect(
            ClockViewIds.WEATHER_CLOCK_DATE,
            TOP,
            ClockViewIds.WEATHER_CLOCK_TIME,
            BOTTOM,
            resources.getDimensionPixelSize(clocksR.dimen.weather_clock_smartspace_bottom_margin) +
                resources.getDimensionPixelSize(clocksR.dimen.enhanced_smartspace_height),
        )
        constraints.connect(ClockViewIds.WEATHER_CLOCK_DATE, START, PARENT_ID, START, paddingStart)

        constraints.constrainWidth(view.weatherIconView.id, WRAP_CONTENT)
        constraints.constrainHeight(view.weatherIconView.id, WRAP_CONTENT)
        constraints.connect(view.weatherIconView.id, START, ClockViewIds.WEATHER_CLOCK_DATE, END, iconPadding)
        constraints.connect(view.weatherIconView.id, TOP, ClockViewIds.WEATHER_CLOCK_DATE, TOP)

        constraints.constrainWidth(view.temperatureView.id, WRAP_CONTENT)
        constraints.constrainHeight(view.temperatureView.id, WRAP_CONTENT)
        constraints.connect(view.temperatureView.id, BOTTOM, ClockViewIds.WEATHER_CLOCK_DATE, BOTTOM)
        constraints.connect(view.temperatureView.id, END, PARENT_ID, END, paddingStart)
        return constraints
    }

    @Deprecated("Unsupported with flexiglass. Move to composables.")
    override fun applyExternalDisplayPresentationConstraints(constraints: ConstraintSet): ConstraintSet {
        val paddingStart = resources.getDimensionPixelSize(clocksR.dimen.clock_padding_start)
        val iconPadding = resources.getDimensionPixelSize(clocksR.dimen.weather_date_icon_padding)

        constraints.constrainWidth(ClockViewIds.WEATHER_CLOCK_TIME, WRAP_CONTENT)
        constraints.constrainHeight(ClockViewIds.WEATHER_CLOCK_TIME, WRAP_CONTENT)
        constraints.connect(ClockViewIds.WEATHER_CLOCK_TIME, TOP, PARENT_ID, TOP, paddingStart)
        constraints.connect(ClockViewIds.WEATHER_CLOCK_TIME, START, PARENT_ID, START, paddingStart)

        constraints.constrainWidth(ClockViewIds.WEATHER_CLOCK_DATE, WRAP_CONTENT)
        constraints.constrainHeight(ClockViewIds.WEATHER_CLOCK_DATE, WRAP_CONTENT)
        constraints.connect(
            ClockViewIds.WEATHER_CLOCK_DATE,
            TOP,
            ClockViewIds.WEATHER_CLOCK_TIME,
            BOTTOM,
            paddingStart + resources.getDimensionPixelSize(clocksR.dimen.enhanced_smartspace_height),
        )
        constraints.connect(ClockViewIds.WEATHER_CLOCK_DATE, START, PARENT_ID, START, paddingStart)

        constraints.constrainWidth(view.weatherIconView.id, WRAP_CONTENT)
        constraints.constrainHeight(view.weatherIconView.id, WRAP_CONTENT)
        constraints.connect(view.weatherIconView.id, START, ClockViewIds.WEATHER_CLOCK_DATE, END, iconPadding)
        constraints.connect(view.weatherIconView.id, TOP, ClockViewIds.WEATHER_CLOCK_DATE, TOP)

        constraints.constrainWidth(view.temperatureView.id, WRAP_CONTENT)
        constraints.constrainHeight(view.temperatureView.id, WRAP_CONTENT)
        constraints.connect(view.temperatureView.id, BOTTOM, ClockViewIds.WEATHER_CLOCK_DATE, BOTTOM)
        constraints.connect(view.temperatureView.id, END, PARENT_ID, END, paddingStart)
        return constraints
    }

    override fun applyAodBurnIn(aodBurnInModel: AodClockBurnInModel) {
        view.timeView.translationX = aodBurnInModel.translationX
        view.timeView.translationY = aodBurnInModel.translationY
        view.dateView.translationX = aodBurnInModel.translationX
        view.dateView.translationY = aodBurnInModel.translationY
        view.weatherIconView.translationX = aodBurnInModel.translationX
        view.weatherIconView.translationY = aodBurnInModel.translationY
        view.temperatureView.translationX = -aodBurnInModel.translationX
        view.temperatureView.translationY = aodBurnInModel.translationY
        view.alarmDndIconView.translationX = -aodBurnInModel.translationX
        view.alarmDndIconView.translationY = aodBurnInModel.translationY
    }

    /** Re-keys the child views with the well-known weather clock view ids. */
    private fun replaceViewId() {
        val map = view.digitalClockTextViewMap
        view.digitalClockTextViewMap =
            mutableMapOf(
                ClockViewIds.WEATHER_CLOCK_TIME to map[view.timeView.id]!!,
                ClockViewIds.WEATHER_CLOCK_DATE to map[view.dateView.id]!!,
                ClockViewIds.WEATHER_CLOCK_ICON to map[view.weatherIconView.id]!!,
                ClockViewIds.WEATHER_CLOCK_TEMP to map[view.temperatureView.id]!!,
                ClockViewIds.WEATHER_CLOCK_ALARM_DND to map[view.alarmDndIconView.id]!!,
            )
        view.digitalClockTextViewMap.forEach { (id, child) ->
            view.translateMap.remove(child.id)
            child.id = id
            view.translateMap[child.id] = Point(0, 0)
        }
    }

    companion object {
        const val BC_SMARTSPACE_VIEW = "bc_smartspace_view"
    }
}
