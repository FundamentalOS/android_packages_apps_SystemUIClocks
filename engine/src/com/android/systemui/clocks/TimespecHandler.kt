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

import android.icu.text.DateFormat
import android.icu.text.SimpleDateFormat
import android.icu.util.Calendar
import android.icu.util.TimeZone
import android.icu.util.ULocale
import java.util.Locale

abstract class TimespecHandler(val cal: Calendar) {
    /** Test hook: freeze the clock at a fixed time. */
    var fakeTimeMills: Long? = null

    fun setTimeZone(timeZone: TimeZone) {
        cal.timeZone = timeZone
        onTimeZoneChanged()
    }

    fun updateTime() {
        cal.timeInMillis = fakeTimeMills ?: System.currentTimeMillis()
    }

    protected open fun onTimeZoneChanged() {}
}

class AnalogTimespecHandler(
    val timespec: AnalogTimespec,
    val tickMode: AnalogTickMode,
    val timer: Int?,
    cal: Calendar = Calendar.getInstance(),
) : TimespecHandler(cal) {
    val isSweeping: Boolean
        get() = tickMode == AnalogTickMode.SWEEP

    /** Fraction of a full turn for the current time. */
    val rotation: Float
        get() =
            when (timespec) {
                AnalogTimespec.SECONDS -> queryCal(Calendar.SECOND)
                AnalogTimespec.MINUTES -> queryCal(Calendar.MINUTE)
                AnalogTimespec.HOURS -> queryCal(Calendar.HOUR)
                AnalogTimespec.HOURS_OF_DAY -> queryCal(Calendar.HOUR_OF_DAY)
                AnalogTimespec.DAY_OF_WEEK -> queryCal(Calendar.DAY_OF_WEEK)
                AnalogTimespec.DAY_OF_MONTH -> queryCal(Calendar.DAY_OF_MONTH)
                AnalogTimespec.DAY_OF_YEAR -> queryCal(Calendar.DAY_OF_YEAR)
                AnalogTimespec.WEEK -> queryCal(Calendar.WEEK_OF_YEAR)
                AnalogTimespec.MONTH -> queryCal(Calendar.MONTH)
                AnalogTimespec.TIMER -> throw IllegalArgumentException("Timer unimplemented")
            }

    private fun queryCal(field: Int, depth: Int = 0): Float {
        val value = cal.get(field)
        val min = cal.getActualMinimum(field)
        val range = (cal.getActualMaximum(field) - min + 1).toFloat()
        if (range <= 0f) return 0f

        val fraction = (value - min) / range
        if (!isSweeping || depth >= 2) return fraction
        val subField = SWEEP_MAP[field] ?: return fraction
        return fraction + queryCal(subField, depth + 1) / range
    }

    companion object {
        /** For sweeping hands, the next finer calendar field that contributes a partial step. */
        private val SWEEP_MAP =
            mapOf(
                Calendar.SECOND to Calendar.MILLISECOND,
                Calendar.MINUTE to Calendar.SECOND,
                Calendar.HOUR to Calendar.MINUTE,
                Calendar.HOUR_OF_DAY to Calendar.MINUTE,
                Calendar.DAY_OF_WEEK to Calendar.HOUR_OF_DAY,
                Calendar.DAY_OF_MONTH to Calendar.HOUR_OF_DAY,
                Calendar.DAY_OF_YEAR to Calendar.HOUR_OF_DAY,
                Calendar.WEEK_OF_YEAR to Calendar.DAY_OF_WEEK,
                Calendar.MONTH to Calendar.DAY_OF_MONTH,
            )
    }
}

class DigitalTimespecHandler(
    val timespec: DigitalTimespec,
    private val timeFormat: String,
    cal: Calendar = Calendar.getInstance(),
) : TimespecHandler(cal) {
    var is24Hr: Boolean = false
        set(value) {
            field = value
            applyPattern()
        }

    private var dateFormat: DateFormat = updateSimpleDateFormat(Locale.getDefault())
    private var contentDescriptionFormat: DateFormat? = getContentDescriptionFormat(Locale.getDefault())

    init {
        applyPattern()
    }

    override fun onTimeZoneChanged() {
        dateFormat.timeZone = TimeZone.getTimeZone(cal.timeZone.id)
        contentDescriptionFormat?.timeZone = TimeZone.getTimeZone(cal.timeZone.id)
        applyPattern()
    }

    fun updateLocale(locale: Locale) {
        dateFormat = updateSimpleDateFormat(locale)
        contentDescriptionFormat = getContentDescriptionFormat(locale)
        onTimeZoneChanged()
    }

    private fun updateSimpleDateFormat(locale: Locale): DateFormat {
        // Time is always rendered with the literal json pattern; only dates get localized.
        return if (locale.language == Locale.ENGLISH.language || timespec != DigitalTimespec.DATE_FORMAT) {
            SimpleDateFormat(timeFormat, timeFormat, ULocale.forLocale(locale))
        } else {
            SimpleDateFormat.getInstanceForSkeleton(timeFormat, locale)
        }
    }

    private fun getContentDescriptionFormat(locale: Locale): DateFormat? =
        when (timespec) {
            DigitalTimespec.TIME_FULL_FORMAT -> SimpleDateFormat.getInstanceForSkeleton("hh:mm", locale)
            DigitalTimespec.DATE_FORMAT -> SimpleDateFormat.getInstanceForSkeleton("EEEE MMMM d", locale)
            else -> null
        }

    private fun applyPattern() {
        val pattern = if (is24Hr) timeFormat.replace("hh", "h").replace("h", "HH") else timeFormat
        if (timespec != DigitalTimespec.DATE_FORMAT) {
            (dateFormat as SimpleDateFormat).applyPattern(pattern)
            (contentDescriptionFormat as? SimpleDateFormat)?.applyPattern(if (is24Hr) "HH:mm" else "hh:mm")
        }
    }

    private fun getSingleDigit(): String {
        val isFirst = timespec == DigitalTimespec.FIRST_DIGIT
        val text = dateFormat.format(cal.time).toString()
        return if (isFirst) text.substring(0, text.length - 1) else text.substring(text.length - 1)
    }

    fun getDigitString(): String =
        when (timespec) {
            DigitalTimespec.TIME_FULL_FORMAT -> dateFormat.format(cal.time).toString()
            DigitalTimespec.DATE_FORMAT -> dateFormat.format(cal.time).toString().uppercase(Locale.ROOT)
            DigitalTimespec.FIRST_DIGIT,
            DigitalTimespec.SECOND_DIGIT -> getSingleDigit()
            DigitalTimespec.DIGIT_PAIR -> dateFormat.format(cal.time).toString()
        }

    fun getContentDescription(): String? =
        when (timespec) {
            DigitalTimespec.TIME_FULL_FORMAT,
            DigitalTimespec.DATE_FORMAT -> contentDescriptionFormat?.format(cal.time).toString()
            else -> null
        }
}
