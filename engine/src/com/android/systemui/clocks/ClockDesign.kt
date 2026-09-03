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

import android.content.theming.ThemeStyle
import android.graphics.Point
import android.view.animation.Interpolator
import com.android.app.animation.Interpolators
import com.android.systemui.plugins.keyguard.ui.clocks.ClockViewIds
import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonSerializationContext
import com.google.gson.JsonSerializer
import java.lang.reflect.Type

/**
 * Declarative clock description, loaded from `assets/clocks/<ID>.json`.
 *
 * The constructors mirror the JSON schema exactly (including which classes expose a no-arg
 * constructor) because Gson only applies Kotlin default values when it can call a no-arg
 * constructor; otherwise fields absent from the JSON stay null/0.
 */
data class ClockDesign(
    val id: String,
    val name: String? = null,
    val description: String? = null,
    val thumbnail: String? = null,
    val large: ClockFace? = null,
    val small: ClockFace? = null,
    /** [ThemeStyle] value used to build the monet palette (defaults to CLOCK). */
    val colorPalette: Int? = null,
) {
    class Serializer : JsonDeserializer<ClockDesign>, JsonSerializer<ClockDesign> {
        override fun deserialize(
            json: JsonElement?,
            typeOfT: Type?,
            context: JsonDeserializationContext?,
        ): ClockDesign {
            requireNotNull(json)
            requireNotNull(context)
            val obj = json.asJsonObject
            val palette = obj.get("colorPalette")?.asString
            return ClockDesign(
                id = obj.get("id")?.asString ?: "",
                name = obj.get("name")?.asString,
                description = obj.get("description")?.asString,
                thumbnail = obj.get("thumbnail")?.asString,
                large = context.deserialize(obj.get("large"), ClockFace::class.java),
                small = context.deserialize(obj.get("small"), ClockFace::class.java),
                colorPalette = palette?.let { ThemeStyle.valueOf(it) },
            )
        }

        override fun serialize(
            src: ClockDesign?,
            typeOfSrc: Type?,
            context: JsonSerializationContext?,
        ): JsonElement {
            requireNotNull(src)
            requireNotNull(context)
            return JsonObject().apply {
                addProperty("id", src.id)
                addProperty("name", src.name)
                addProperty("description", src.description)
                addProperty("thumbnail", src.thumbnail)
                add("large", context.serialize(src.large))
                add("small", context.serialize(src.small))
                addProperty("colorPalette", src.colorPalette?.let { ThemeStyle.toString(it) })
            }
        }
    }
}

data class ClockFace(
    val layers: List<ClockLayer> = listOf(),
    val layerBounds: LayerBounds = LayerBounds.FIT,
    val wallpaper: String? = null,
    val faceLayout: DigitalFaceLayout? = null,
    val pickerScale: ClockFaceScaleInPicker = ClockFaceScaleInPicker(1f, 1f),
)

data class ClockFaceScaleInPicker(val scaleX: Float, val scaleY: Float)

enum class LayerBounds {
    FIT,
    FILL,
    STRETCH,
}

interface ClockLayer {
    val layerBounds: LayerBounds?
}

data class AssetReference(
    val light: String,
    val dark: String,
    val doze: String? = null,
    val lightTint: String? = null,
    val darkTint: String? = null,
    val dozeTint: String? = null,
)

data class AssetLayer(val asset: AssetReference, override val layerBounds: LayerBounds? = null) :
    ClockLayer

enum class AnalogTimespec {
    SECONDS,
    MINUTES,
    HOURS,
    HOURS_OF_DAY,
    DAY_OF_WEEK,
    DAY_OF_MONTH,
    DAY_OF_YEAR,
    WEEK,
    MONTH,
    TIMER,
}

enum class AnalogTickMode {
    SWEEP,
    TICK,
}

data class AnalogHandLayer(
    val timespec: AnalogTimespec,
    val tickMode: AnalogTickMode,
    val asset: AssetReference,
    val timer: Int? = null,
    val clock_pivot: Point = Point(0, 0),
    val asset_pivot: Point? = null,
    val length: Float = 1f,
    override val layerBounds: LayerBounds? = null,
) : ClockLayer

data class AnimatedHandLayer(
    val timespec: AnalogTimespec,
    val asset: AssetReference,
    val timer: Int? = null,
    override val layerBounds: LayerBounds? = null,
) : ClockLayer

enum class DigitalTimespec(private val hourViewId: Int, private val minuteViewId: Int) {
    TIME_FULL_FORMAT(ClockViewIds.TIME_FULL_FORMAT, ClockViewIds.TIME_FULL_FORMAT),
    DIGIT_PAIR(ClockViewIds.HOUR_DIGIT_PAIR, ClockViewIds.MINUTE_DIGIT_PAIR),
    FIRST_DIGIT(ClockViewIds.HOUR_FIRST_DIGIT, ClockViewIds.MINUTE_FIRST_DIGIT),
    SECOND_DIGIT(ClockViewIds.HOUR_SECOND_DIGIT, ClockViewIds.MINUTE_SECOND_DIGIT),
    DATE_FORMAT(ClockViewIds.DATE_FORMAT, ClockViewIds.DATE_FORMAT);

    fun getViewId(isHour: Boolean): Int = if (isHour) hourViewId else minuteViewId
}

enum class DigitalFaceLayout {
    TWO_PAIRS_VERTICAL,
    TWO_PAIRS_HORIZONTAL,
    FOUR_DIGITS_ALIGN_CENTER,
    FOUR_DIGITS_HORIZONTAL,
}

enum class RenderType {
    CHANGE_WEIGHT,
    HOLLOW_TEXT,
    STROKE_TEXT,
    OUTER_OUTLINE_TEXT,
}

enum class InterpolatorEnum(factory: () -> Interpolator) {
    STANDARD({ Interpolators.STANDARD }),
    EMPHASIZED({ Interpolators.EMPHASIZED });

    val interpolator: Interpolator by lazy(factory)
}

enum class HorizontalAlignment {
    LEFT,
    RIGHT,
    CENTER,
}

enum class VerticalAlignment {
    TOP,
    BOTTOM,
    BASELINE,
    CENTER,
}

data class DigitalAlignment(
    val horizontalAlignment: HorizontalAlignment?,
    val verticalAlignment: VerticalAlignment?,
)

data class DigitalMarginRatio(
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = 0f,
    val bottom: Float = 0f,
)

interface TextStyle {
    val fontSizeScale: Float?
}

data class FontTextStyle(
    val fontFamily: String? = null,
    val lineHeight: Float? = null,
    val borderWidth: String? = null,
    val borderWidthScale: Float? = null,
    val fillColorLight: String? = null,
    val fillColorDark: String? = null,
    override val fontSizeScale: Float? = null,
    var fontVariation: String? = null,
    var fontFeatureSettings: String? = null,
    val renderType: RenderType = RenderType.STROKE_TEXT,
    val outlineColor: String? = null,
    val transitionDuration: Long = 300L,
    val transitionInterpolator: InterpolatorEnum? = null,
) : TextStyle

data class LottieTextStyle(
    val numbers: List<String> = listOf(),
    val spacing: String = "0dp",
    val colon: String? = null,
    val fillColorLightMap: Map<String, String>? = null,
    val fillColorDarkMap: Map<String, String>? = null,
    override val fontSizeScale: Float? = null,
    val paddingVertical: String = "0dp",
    val paddingHorizontal: String = "0dp",
) : TextStyle

data class DigitalHandLayer(
    val timespec: DigitalTimespec,
    val style: TextStyle,
    val aodStyle: TextStyle?,
    val timer: Int? = null,
    override val layerBounds: LayerBounds? = null,
    var faceLayout: DigitalFaceLayout? = null,
    val dateTimeFormat: String,
    val alignment: DigitalAlignment?,
    val marginRatio: DigitalMarginRatio? = DigitalMarginRatio(),
) : ClockLayer

data class ComposedDigitalHandLayer(
    val customizedView: String? = null,
    val digitalLayers: List<DigitalHandLayer> = listOf(),
    override val layerBounds: LayerBounds? = null,
) : ClockLayer

fun DigitalHandLayer.lookupDigitalLayerId(): Int = timespec.getViewId("h" in dateTimeFormat)
