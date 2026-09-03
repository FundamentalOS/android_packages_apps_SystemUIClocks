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
import android.graphics.Rect
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.text.util.LocalePreferences
import com.android.app.animation.Interpolators
import com.android.systemui.animation.GSFAxes
import com.android.systemui.customization.clocks.utils.ContextUtils.getSafeStatusBarHeight
import com.android.systemui.log.core.MessageBuffer
import com.android.systemui.plugins.keyguard.data.model.AlarmData
import com.android.systemui.plugins.keyguard.data.model.WeatherData
import com.android.systemui.plugins.keyguard.data.model.ZenData
import com.android.systemui.plugins.keyguard.ui.clocks.ClockPositionAnimationArgs
import com.android.systemui.plugins.keyguard.ui.clocks.ClockViewIds
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

/** Shared plumbing for the weather clock: time text plus glyph-font weather / alarm / DND icons. */
abstract class WeatherDigitalClockViewBase(context: Context, private val assets: AssetLoader, messageBuffer: MessageBuffer) :
    DigitalClockFaceView(context, messageBuffer) {
    val weatherIconView = SimpleDigitalClockTextView(context, messageBuffer)
    val alarmDndIconView = SimpleDigitalClockTextView(context, messageBuffer)
    private val iconViews = listOf(weatherIconView, alarmDndIconView)
    lateinit var timeView: SimpleDigitalClockTextView
    override var digitalClockTextViewMap = mutableMapOf<Int, SimpleDigitalClockTextView>()
    val translateMap = mutableMapOf<Int, Point>()
    protected var onClickAction: ((View) -> Unit)? = null
        private set
    var interpolatedMeasuredWidth = -1

    private var weatherData: WeatherData? = null
    private var alarmData: AlarmData? = null
    private var zenData: ZenData? = null

    init {
        layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        for (view in iconViews) {
            view.id = generateViewId()
            addView(view)
            view.setWillNotDraw(true)
        }
        setWillNotDraw(false)
    }

    protected open fun refreshTemperature(data: WeatherData) {}

    private fun updateIconData(weather: WeatherData? = null, alarm: AlarmData? = null, zen: ZenData? = null) {
        weatherData = weather ?: weatherData
        alarmData = alarm ?: alarmData
        zenData = zen ?: zenData
        refreshIconViews()
    }

    private fun refreshIconViews() {
        weatherIconView.setText(weatherData?.state?.icon ?: "")
        weatherIconView.contentDescription = weatherData?.description
        weatherIconView.refreshText()

        val icons = StringBuilder()
        val description = StringBuilder()
        alarmData?.let { alarm ->
            if (alarm.nextAlarmMillis != null) {
                icons.append(ALARM_CHARACTER)
                alarm.descriptionId?.let { description.append(assets.getString(it)) }
            }
        }
        zenData?.let { zen ->
            if (zen.zenMode != ZenData.ZenMode.OFF) {
                icons.append(DND_CHARACTER)
                zen.descriptionId?.let {
                    if (description.isNotEmpty()) description.append(", ")
                    description.append(assets.getString(it))
                }
            }
        }
        alarmDndIconView.setText(icons.toString())
        alarmDndIconView.contentDescription = description.toString()
        alarmDndIconView.refreshText()
        invalidate()
    }

    override fun onViewAdded(child: View?) {
        if (child == null) return
        getLogger().onViewAdded(child)
        super.onViewAdded(child)
        if (child.id == ClockViewIds.TIME_FULL_FORMAT) {
            timeView = child as SimpleDigitalClockTextView
            for (icon in iconViews) {
                icon.applyStyles(
                    assets,
                    timeView.textStyle.copy(
                        fontFamily = WEATHER_CLOCK_FONT_FAMILY,
                        borderWidth = "0dp",
                        fontSizeScale = getWeatherClockFontSizeScale(timeView.textStyle.fontSizeScale ?: 1f),
                        fontVariation = WEATHER_CLOCK_FONT_VARIATION_LS,
                    ),
                    timeView.aodStyle.copy(
                        borderWidth = "0dp",
                        fontVariation = WEATHER_CLOCK_FONT_VARIATION_AOD,
                        transitionDuration = 700L,
                        transitionInterpolator = InterpolatorEnum.EMPHASIZED,
                    ),
                )
            }
        }
    }

    override fun refreshTime() {
        super.refreshTime()
        invalidate()
        contentDescription = generateContentDescription()
    }

    open fun generateContentDescription(): String =
        listOf(timeView, weatherIconView, alarmDndIconView).mapNotNull { it.contentDescription }.joinToString(" ")

    override fun onWeatherDataChanged(data: WeatherData) {
        getLogger().i({ "onWeatherDataChanged -> $str1" }) { str1 = data.toString() }
        onClickAction = data.touchAction
        updateIconData(weather = data)
        refreshTemperature(data)
        contentDescription = generateContentDescription()
    }

    override fun onAlarmDataChanged(data: AlarmData) {
        getLogger().i({ "onAlarmDataChanged -> $str1" }) { str1 = data.toString() }
        updateIconData(alarm = data)
        contentDescription = generateContentDescription()
    }

    override fun onZenDataChanged(data: ZenData) {
        getLogger().i({ "onZenDataChanged -> $str1" }) { str1 = data.toString() }
        updateIconData(zen = data)
        contentDescription = generateContentDescription()
    }

    companion object {
        private const val ALARM_CHARACTER = "p"
        private const val DND_CHARACTER = "o"
        private const val WEATHER_CLOCK_FONT_FAMILY = "FrameWeatherVF.ttf"
        private val WEATHER_CLOCK_FONT_VARIATION_LS = "'${GSFAxes.WEIGHT.tag}' 1000"
        private val WEATHER_CLOCK_FONT_VARIATION_AOD = "'${GSFAxes.WEIGHT.tag}' 0"

        fun getWeatherClockFontSizeScale(scale: Float): Float = scale * 1.25f
    }
}

/** Large weather clock: time, rotated date and temperature, and icons framing the smartspace. */
class WeatherDigitalClockViewLarge(context: Context, val assets: AssetLoader, messageBuffer: MessageBuffer) :
    WeatherDigitalClockViewBase(context, assets, messageBuffer) {
    val temperatureView = SimpleDigitalClockTextView(context, messageBuffer)
    lateinit var dateView: SimpleDigitalClockTextView
    val statusBarHeight = context.getSafeStatusBarHeight()
    private var prevWidth = -1
    private var locale: Locale = Locale.getDefault()
    private var _tempUnit: String? = null
    private var weatherData: WeatherData? = null

    override val hasCustomWeatherDataDisplay = true
    override val isAlignedWithScreen = true
    override val hasCustomPositionUpdatedAnimation = true
    override val useCustomClockScene = true

    private val onTouchListener =
        View.OnTouchListener { v, event ->
            if (event.action != MotionEvent.ACTION_DOWN) return@OnTouchListener false
            // The temperature is drawn rotated; its touch rect is expressed in rotated coordinates.
            val tempRect =
                translateMap[temperatureView.id]?.let {
                    Rect(it.y, -it.x - temperatureView.width, it.y + temperatureView.height, -it.x)
                }
            val iconRect =
                translateMap[weatherIconView.id]?.let {
                    Rect(it.x, it.y, it.x + weatherIconView.width, it.y + weatherIconView.height)
                }
            val x = event.x.toInt()
            val y = event.y.toInt()
            if ((tempRect?.contains(x, y) == true) || (iconRect?.contains(x, y) == true)) {
                onClickAction?.let {
                    it(v)
                    return@OnTouchListener true
                }
            }
            false
        }

    init {
        temperatureView.id = generateViewId()
        addView(temperatureView)
        temperatureView.setWillNotDraw(true)
        setOnTouchListener(onTouchListener)
    }

    override fun onLocaleChanged(locale: Locale) {
        if (this.locale == locale) return
        this.locale = locale
        _tempUnit = null
        weatherData?.let { onWeatherDataChanged(it) }
    }

    private fun getTemperatureUnit(): String =
        _tempUnit ?: LocalePreferences.getTemperatureUnit(locale, false).also { _tempUnit = it }

    override fun onViewAdded(child: View?) {
        if (child == null) return
        getLogger().onViewAdded(child)
        super.onViewAdded(child)
        translateMap[child.id] = Point(0, 0)
        if (child.id == ClockViewIds.DATE_FORMAT) {
            dateView = child as SimpleDigitalClockTextView
            dateView.setSingleLine()
            dateView.layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            temperatureView.applyStyles(assets, dateView.textStyle, dateView.aodStyle)
            temperatureView.setSingleLine()
        }
        if (child.id == ClockViewIds.TIME_FULL_FORMAT) {
            timeView.layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            timeView.setSingleLine()
            temperatureView.verticalAlignment = VerticalAlignment.BOTTOM
            temperatureView.layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            weatherIconView.verticalAlignment = VerticalAlignment.TOP
            weatherIconView.layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            alarmDndIconView.verticalAlignment = VerticalAlignment.TOP
            alarmDndIconView.horizontalAlignment = HorizontalAlignment.RIGHT
            alarmDndIconView.layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        child.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun refreshTemperature(data: WeatherData) {
        temperatureView.setText("${getPreferredTemperature(data)}°")
        temperatureView.contentDescription = temperatureView.getText()
        temperatureView.refreshText()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        prevWidth = if (prevWidth == -1) measuredWidth else interpolatedMeasuredWidth
        interpolatedMeasuredWidth = measuredWidth
        updateTranslateMap()
    }

    override fun refreshTime() {
        super.refreshTime()
        updateTranslateMap()
    }

    private fun updateTranslateMap() {
        val densityOffset = max(0f, context.resources.displayMetrics.density / 2.625f - 1f)
        val date = translateMap[dateView.id]!!
        date.x = ((-(CLOCK_OFFSET_BELOW_SMARTSPACE + densityOffset)) * timeView.textBounds.height() - dateView.textBounds.width()).toInt()
        date.y = (interpolatedMeasuredWidth * CLOCK_OUTER_FRAME_HORIZONTAL_MARGIN).toInt()

        val icon = translateMap[weatherIconView.id]!!
        icon.x = (date.y + dateView.textBounds.height() + WEATHER_ICON_MARGIN_LEFT * weatherIconView.textBounds.width()).toInt()
        icon.y = ((CLOCK_OFFSET_BELOW_SMARTSPACE + densityOffset) * timeView.textBounds.height()).toInt()

        val alarm = translateMap[alarmDndIconView.id]!!
        alarm.x = (interpolatedMeasuredWidth - alarmDndIconView.measuredWidth - interpolatedMeasuredWidth * CLOCK_OUTER_FRAME_HORIZONTAL_MARGIN).toInt()
        alarm.y = ((CLOCK_OFFSET_BELOW_SMARTSPACE + densityOffset) * timeView.textBounds.height()).toInt()

        val time = translateMap[timeView.id]!!
        time.x = (interpolatedMeasuredWidth * CLOCK_OUTER_FRAME_HORIZONTAL_MARGIN).toInt()
        time.y = statusBarHeight

        val temp = translateMap[temperatureView.id]!!
        temp.x = (-measuredHeight + CLOCK_OUTER_FRAME_BOTTOM_MARGIN * measuredHeight).toInt()
        temp.y = (interpolatedMeasuredWidth - temperatureView.measuredHeight - CLOCK_OUTER_FRAME_HORIZONTAL_MARGIN * interpolatedMeasuredWidth).toInt()
    }

    override fun onPositionAnimated(args: ClockPositionAnimationArgs) {
        val progress = Interpolators.EMPHASIZED.getInterpolation(args.fraction)
        interpolatedMeasuredWidth = (prevWidth * (1 - progress) + measuredWidth * progress).toInt()
        updateTranslateMap()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.saveBlock().use {
            canvas.rotate(-90f)
            translateMap[dateView.id]!!.let { canvas.translate(it.x.toFloat(), it.y.toFloat()) }
            dateView.draw(canvas)
        }
        canvas.saveBlock().use {
            canvas.rotate(-90f)
            translateMap[temperatureView.id]!!.let { canvas.translate(it.x.toFloat(), it.y.toFloat()) }
            temperatureView.draw(canvas)
        }
        canvas.saveBlock().use {
            translateMap[weatherIconView.id]!!.let { canvas.translate(it.x.toFloat(), it.y.toFloat()) }
            weatherIconView.draw(canvas)
        }
        canvas.saveBlock().use {
            translateMap[alarmDndIconView.id]!!.let { canvas.translate(it.x.toFloat(), it.y.toFloat()) }
            alarmDndIconView.draw(canvas)
        }
        canvas.saveBlock().use {
            translateMap[timeView.id]!!.let { canvas.translate(it.x.toFloat(), it.y.toFloat()) }
            timeView.draw(canvas)
        }
    }

    fun getPreferredTemperature(data: WeatherData): Int {
        val unit = getTemperatureUnit()
        val result = getPreferredTemperature(data, unit)
        getLogger().i({ "$str1 got $int1 from $str2" }) {
            str1 = unit
            int1 = result
            str2 = data.toString()
        }
        return result
    }

    override fun generateContentDescription(): String =
        listOf(timeView, dateView, weatherIconView, alarmDndIconView, temperatureView)
            .mapNotNull { it.contentDescription }
            .joinToString(" ")

    companion object {
        private const val WEATHER_ICON_MARGIN_LEFT = 0.3f
        private const val CLOCK_OFFSET_BELOW_SMARTSPACE = 5
        private const val CLOCK_OUTER_FRAME_BOTTOM_MARGIN = 0.33
        private const val CLOCK_OUTER_FRAME_HORIZONTAL_MARGIN = 0.04

        fun getPreferredTemperature(data: WeatherData, unit: String): Int =
            when (unit) {
                LocalePreferences.TemperatureUnit.CELSIUS -> convertTempUnit(data.temperature, data.useCelsius, true)
                LocalePreferences.TemperatureUnit.FAHRENHEIT -> convertTempUnit(data.temperature, data.useCelsius, false)
                LocalePreferences.TemperatureUnit.KELVIN ->
                    convertCelsiusToKelvin(convertTempUnit(data.temperature, data.useCelsius, true))
                "" -> data.temperature
                else -> data.temperature
            }

        private fun convertTempUnit(temp: Int, isCelsius: Boolean, toCelsius: Boolean): Int {
            if (isCelsius == toCelsius) return temp
            return if (isCelsius) convertCelsiusToFahrenheit(temp) else convertFahrenheitToCelsius(temp)
        }

        private fun convertFahrenheitToCelsius(temp: Int): Int = ((temp - 32f) * 5f / 9f).roundToInt()

        private fun convertCelsiusToFahrenheit(temp: Int): Int = (temp * 9f / 5f + 32f).roundToInt()

        private fun convertCelsiusToKelvin(temp: Int): Int = (temp - 273.15).roundToInt()
    }
}

/** Small weather clock: time followed by the weather glyph. */
class WeatherDigitalClockViewSmall(context: Context, private val assets: AssetLoader, messageBuffer: MessageBuffer) :
    WeatherDigitalClockViewBase(context, assets, messageBuffer) {
    override val hasCustomWeatherDataDisplay = true

    init {
        setOnClickListener { v -> onClickAction?.let { it(v) } }
        alarmDndIconView.visibility = GONE
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(-1, heightMeasureSpec)
        val width =
            if (weatherIconView.measuredWidth > 0) {
                (timeView.textBounds.width() + weatherIconView.textBounds.width() * WEATHER_ICON_MARGIN_LEFT + weatherIconView.measuredWidth).toInt()
            } else {
                timeView.textBounds.width()
            }
        setMeasuredDimension(width, measuredHeight)
    }

    override fun onViewAdded(child: View?) {
        if (child == null) return
        getLogger().onViewAdded(child)
        super.onViewAdded(child)
        if (child.id == ClockViewIds.TIME_FULL_FORMAT) {
            timeView.layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT)
            weatherIconView.horizontalAlignment = HorizontalAlignment.CENTER
            weatherIconView.verticalAlignment = VerticalAlignment.CENTER
            weatherIconView.layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        timeView.draw(canvas)
        val offset = timeView.textBounds.width() + weatherIconView.textBounds.width() * WEATHER_ICON_MARGIN_LEFT
        canvas.translate(offset, 0f)
        weatherIconView.draw(canvas)
        canvas.translate(-offset, 0f)
    }

    companion object {
        private const val WEATHER_ICON_MARGIN_LEFT = 0.3f
    }
}
