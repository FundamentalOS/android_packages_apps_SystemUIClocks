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
package com.android.systemui.clocks.words

/** Spells the time out in English words: "It\u2019s" / "Five" / "Sixteen". */
object TimeWords {
    const val PREFIX = "It\u2019s"

    private val UNITS =
        arrayOf(
            "Zero", "One", "Two", "Three", "Four", "Five", "Six", "Seven", "Eight", "Nine",
            "Ten", "Eleven", "Twelve", "Thirteen", "Fourteen", "Fifteen", "Sixteen",
            "Seventeen", "Eighteen", "Nineteen",
        )
    private val TENS = arrayOf("", "Ten", "Twenty", "Thirty", "Forty", "Fifty")

    fun number(n: Int): String =
        when {
            n < 20 -> UNITS[n]
            n % 10 == 0 -> TENS[n / 10]
            else -> TENS[n / 10] + " " + UNITS[n % 10]
        }

    fun hour(hourOfDay: Int, is24Hour: Boolean): String {
        if (is24Hour) return number(hourOfDay)
        val h = hourOfDay % 12
        return number(if (h == 0) 12 else h)
    }

    fun minute(minute: Int): String =
        when {
            minute == 0 -> "O\u2019Clock"
            minute < 10 -> "Oh " + UNITS[minute]
            else -> number(minute)
        }
}
