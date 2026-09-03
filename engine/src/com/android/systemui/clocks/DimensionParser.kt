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
import android.util.TypedValue
import java.util.regex.Pattern

/** Parses dimension strings like `4dp` / `5.2dp` / `12px` into pixels. */
class DimensionParser(private val ctx: Context) {
    fun convert(dimension: String): Float {
        val (value, unit) = parse(dimension)
        return TypedValue.applyDimension(unit, value, ctx.resources.displayMetrics)
    }

    fun parse(dimension: String): Pair<Float, Int> {
        val matcher = PATTERN.matcher(dimension)
        if (!matcher.matches()) throw NumberFormatException("Failed to parse '$dimension'")
        val value = matcher.group(1)?.toFloat() ?: throw NumberFormatException("Bad value in '$dimension'")
        val unit = UNITS[matcher.group(3) ?: ""] ?: throw NumberFormatException("Bad unit in '$dimension'")
        return value to unit
    }

    companion object {
        private val PATTERN = Pattern.compile("(\\d+(\\.\\d+)?)([a-z]+)")
        private val UNITS =
            mapOf(
                "dp" to TypedValue.COMPLEX_UNIT_DIP,
                "dip" to TypedValue.COMPLEX_UNIT_DIP,
                "sp" to TypedValue.COMPLEX_UNIT_SP,
                "px" to TypedValue.COMPLEX_UNIT_PX,
                "pt" to TypedValue.COMPLEX_UNIT_PT,
                "mm" to TypedValue.COMPLEX_UNIT_MM,
                "in" to TypedValue.COMPLEX_UNIT_IN,
            )
    }
}
