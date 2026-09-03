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

import com.android.systemui.plugins.keyguard.ui.clocks.ClockViewIds
import java.text.SimpleDateFormat

/** Checks that a parsed [ClockDesign] references only resources that exist and is well-formed. */
class ClockDesignValidator(
    private val parser: DimensionParser,
    private val loader: AssetLoader,
    private val idExists: (String) -> Boolean,
) {
    val errors = mutableListOf<String>()
    val isValid: Boolean
        get() = errors.size <= 0

    fun validate(design: ClockDesign) {
        check(!idExists(design.id), "Duplicate clock id: ${design.id}")
        validateResource(design.thumbnail)
        design.large?.let { validate(it, true) }
        design.small?.let { validate(it, false) }
    }

    private fun validate(face: ClockFace, isLargeClock: Boolean) {
        face.layers.forEach { validate(it) }
        face.wallpaper?.let { validateResource(it) }
        validateDigitalTimespecSetValid(face)
    }

    private fun validate(layer: ClockLayer) {
        when (layer) {
            is AssetLayer -> validateAsset(layer.asset)
            is DigitalHandLayer -> validateDigitalClockHandLayer(layer)
            is AnalogHandLayer -> {
                check(isPresent(layer.timespec), "Timespec not specified for analog hand")
                validateAsset(layer.asset)
            }
            is AnimatedHandLayer -> validateAsset(layer.asset)
            is ComposedDigitalHandLayer -> layer.digitalLayers.forEach { validateDigitalClockHandLayer(it) }
            else -> throw IllegalArgumentException("Unrecognized layer: ${layer::class.simpleName}")
        }
    }

    private fun validateAsset(asset: AssetReference) {
        validateResource(asset.light)
        validateResource(asset.dark)
        validateResource(asset.doze)
        validateColor(asset.lightTint)
        validateColor(asset.darkTint)
        validateColor(asset.dozeTint)
    }

    private fun validateResource(ref: String?) {
        if (ref == null) return
        check(loader.assetExists(ref), "Couldn't find resource at $ref")
    }

    private fun validateColor(ref: String?) {
        if (ref == null) return
        check(loader.tryReadColor(ref) != null, "Couldn't find color at $ref")
    }

    /**
     * Gson hydrates the design classes without running their constructors, so a field declared
     * non-null in Kotlin can still be null when the JSON omits it; check through a nullable view.
     */
    private fun <T> isPresent(value: T?): Boolean = value != null

    private fun check(condition: Boolean, message: String) {
        if (!condition) errors.add(message)
    }

    private fun validateDigitalTimespecSetValid(face: ClockFace) {
        if (face.layers.none { it is DigitalHandLayer }) return
        val ids = mutableSetOf<Int>()
        for (layer in face.layers) {
            if (layer is DigitalHandLayer && layer.timespec != DigitalTimespec.DATE_FORMAT) {
                ids.add(layer.lookupDigitalLayerId())
            }
        }
        check(VALID_DIGITAL_TIMESPEC_SETS.contains(ids), "Digital clock lack some elements, current set is $ids")
    }

    private fun validateDigitalClockHandLayer(layer: DigitalHandLayer) {
        check(isPresent(layer.timespec), "Timespec not specified for digital hand")
        validateDigitalClockTimeFormat(layer)
        val style = layer.style
        check(isPresent(style), "Style not specified for digital hand")
        when (style) {
            is FontTextStyle -> {
                check(style.fontFamily != null, "Style must specify font")
                check(
                    (style.fillColorLight != null) == (style.fillColorDark != null),
                    "Style should specify both a light and dark fill color, or neither",
                )
            }
            is LottieTextStyle -> {
                check(isPresent(style.numbers) && style.numbers.size == 10, "Style has must have exactly 10 number drawables")
                check(
                    (style.fillColorDarkMap != null) == (style.fillColorLightMap != null),
                    "Style should specify both a light and dark fill color, or neither",
                )
            }
            else -> errors.add("Unrecognized text style type: $style")
        }
    }

    private fun validateDigitalClockTimeFormat(layer: DigitalHandLayer) {
        try {
            SimpleDateFormat(layer.dateTimeFormat)
        } catch (ex: IllegalArgumentException) {
            throw IllegalArgumentException("DateTimeFormat for ${layer.timespec} is not valid")
        }
    }

    companion object {
        private val FOUR_DIGITS_CLOCK_IDS =
            setOf(
                ClockViewIds.HOUR_FIRST_DIGIT,
                ClockViewIds.HOUR_SECOND_DIGIT,
                ClockViewIds.MINUTE_FIRST_DIGIT,
                ClockViewIds.MINUTE_SECOND_DIGIT,
            )
        private val TWO_PAIRS_CLOCK_IDS = setOf(ClockViewIds.HOUR_DIGIT_PAIR, ClockViewIds.MINUTE_DIGIT_PAIR)
        private val FULL_FORMAT = setOf(ClockViewIds.TIME_FULL_FORMAT)
        private val VALID_DIGITAL_TIMESPEC_SETS = setOf(FOUR_DIGITS_CLOCK_IDS, TWO_PAIRS_CLOCK_IDS, FULL_FORMAT)
    }
}
