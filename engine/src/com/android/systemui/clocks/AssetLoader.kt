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
import android.content.res.ColorStateList
import android.content.res.Resources
import android.content.theming.ThemeStyle
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.util.TypedValue
import com.android.internal.graphics.ColorUtils
import com.android.internal.graphics.cam.Cam
import com.android.internal.graphics.cam.CamUtils
import com.android.systemui.customization.clocks.TypefaceCache
import com.android.systemui.log.core.Logger
import com.android.systemui.log.core.MessageBuffer
import com.android.systemui.monet.ColorScheme
import com.android.systemui.monet.TonalPalette
import java.io.IOException
import kotlin.math.abs

/**
 * Resolves the string references used by a clock design:
 * - `#aarrggbb` literal colors
 * - `@[pkg:]type/name` resources, looked up first in the plugin apk then in the host
 * - `@android:color/system_<palette>_<shade>[+/-<offset>]` dynamic colors, resolved from the
 *   clock's own monet [ColorScheme] (built from the picker seed color, or the system theme's
 *   primary key color when following the wallpaper)
 * - bare asset paths relative to [baseDir] (fonts, lotties, raw drawables)
 */
class AssetLoader
private constructor(
    private val pluginCtx: Context,
    private val hostCtx: Context,
    private val baseDir: String,
    private var colorScheme: ColorScheme?,
    var seedColor: Int?,
    private var overrideChroma: Float?,
    val typefaceCache: TypefaceCache,
    private val getThemeSeedColor: (Context) -> Int,
    messageBuffer: MessageBuffer,
) {
    val logger = Logger(messageBuffer, TAG)

    /** [ThemeStyle] of the current palette; CLOCK unless the design overrides it. */
    var style: Int = ThemeStyle.CLOCK
        private set

    private val resources: List<Pair<Resources, String>> =
        listOf(
            pluginCtx.resources to pluginCtx.packageName,
            hostCtx.resources to hostCtx.packageName,
        )

    constructor(
        pluginCtx: Context,
        hostCtx: Context,
        baseDir: String,
        messageBuffer: MessageBuffer,
        getThemeSeedColor: ((Context) -> Int)? = null,
    ) : this(
        pluginCtx,
        hostCtx,
        baseDir,
        null,
        null,
        null,
        TypefaceCache(messageBuffer, TYPEFACE_ANIMATION_FRAME_COUNT) {
            Typeface.createFromAsset(pluginCtx.assets, it)
        },
        getThemeSeedColor ?: Companion::getThemeSeedColor,
        messageBuffer,
    )

    fun listAssets(path: String): List<String> =
        pluginCtx.resources.assets.list(baseDir + path)?.toList() ?: listOf()

    fun tryReadString(ref: String?): String? = tryRead(ref, ::readString)

    fun readString(ref: String): String {
        val (res, id) = resolveResourceId(ref) ?: throw IOException("Failed to parse string: $ref")
        return res.getString(id)
    }

    fun tryReadColor(ref: String?): Int? = ref?.let { tryRead(it, ::readColor) }

    fun readColor(ref: String): Int {
        if (ref.startsWith("#")) {
            return Color.parseColor(ref)
        }

        tryParseColorFromScheme(ref)?.let {
            logColor("ColorScheme: $ref", it)
            return checkChroma(it)
        }

        val (res, id, tone) =
            resolveColorResourceId(ref) ?: throw IOException("Failed to parse color: $ref")
        val color = res.getColor(id)
        if (tone == null || TonalPalette.SHADE_KEYS.contains(tone.toInt())) {
            logColor("Resources: $ref", color)
            return checkChroma(color)
        }

        val result = ColorStateList.valueOf(color).withLStar((1000f - tone) / 10f).defaultColor
        logColor("Resources (interpolated tone): $ref", result)
        return checkChroma(result)
    }

    /** Low-chroma seeds get their chroma boosted so the clock stays visibly tinted. */
    private fun checkChroma(color: Int): Int {
        val chroma = overrideChroma ?: return color
        val cam = Cam.fromInt(color)
        val result = ColorUtils.CAMToColor(cam.hue, chroma, CamUtils.lstarFromInt(color))
        logColor("Chroma override", result)
        return result
    }

    /**
     * `@android:color/system_<palette>_<shade>` resolved from [colorScheme]. A trailing `+N`/`-N`
     * is a relative tone: relative to the seed tone when a custom seed color is set, otherwise
     * relative to the literal base shade.
     */
    private fun tryParseColorFromScheme(ref: String): Int? {
        val scheme = colorScheme
        if (scheme == null) {
            logger.w("No color scheme available")
            return null
        }

        val (pkg, type, name) = parseResourceId(ref)
        if (pkg != "android" || type != "color") {
            logger.w("Failed to parse package from $ref")
            return null
        }

        val parts = name.split('_')
        if (parts.size != 3) {
            logger.w("Failed to find palette and shade from $name")
            return null
        }

        val palette =
            when (parts[1]) {
                "accent1" -> scheme.accent1
                "accent2" -> scheme.accent2
                "accent3" -> scheme.accent3
                "neutral1" -> scheme.neutral1
                "neutral2" -> scheme.neutral2
                else -> return null
            }

        val shadeStr = parts[2]
        if (!shadeStr.contains("+") && !shadeStr.contains("-")) {
            val shade = shadeStr.toIntOrNull()
            if (shade == null) {
                logger.w("Failed to parse tone from $shadeStr")
                return null
            }
            return palette.allShadesMapped[shade] ?: palette.getAtTone(shade.toFloat())
        }

        val signIdx = shadeStr.indexOfLast { it == '-' || it == '+' }
        val baseTone =
            if (seedColor != null) scheme.seedTone.toFloat()
            else shadeStr.substring(0, signIdx).toFloatOrNull()
        val relativeTone = shadeStr.substring(signIdx).toFloatOrNull()
        if (baseTone == null) {
            logger.w("Failed to parse base tone from $shadeStr")
            return null
        }
        if (relativeTone == null) {
            logger.w("Failed to parse relative tone from $shadeStr")
            return null
        }
        return palette.getAtTone(baseTone + relativeTone)
    }

    fun readTextAsset(path: String): String =
        pluginCtx.resources.assets.open(baseDir + path).use { String(it.readBytes(), Charsets.UTF_8) }

    fun tryReadDrawableAsset(ref: String?): Drawable? = tryRead(ref, ::readDrawableAsset)

    fun readDrawableAsset(ref: String): Drawable {
        val drawable =
            if (ref.startsWith("@")) {
                val (res, id) = resolveResourceId(ref) ?: throw IOException("Failed to parse $ref to an id")
                res.getDrawable(id)
            } else {
                if (ref.endsWith("xml")) throw IOException("Cannot load xml files from assets")
                pluginCtx.resources.assets.open(baseDir + ref).use {
                    Drawable.createFromResourceStream(pluginCtx.resources, TypedValue(), it, null)
                }
            }
        return drawable ?: throw IOException("Failed to load: $baseDir$ref")
    }

    /** `@[pkg:]type/name` -> (pkg?, type, name) */
    fun parseResourceId(ref: String): Triple<String?, String, String> {
        if (!ref.startsWith("@")) throw IOException("Invalid resource id: $ref; Must start with '@'")
        val parts = ref.drop(1).split('/', ':')
        return when (parts.size) {
            2 -> Triple(null, parts[0], parts[1])
            3 -> Triple(parts[0], parts[1], parts[2])
            else -> throw IOException("Failed to parse resource string: $ref")
        }
    }

    /**
     * Resolves a color resource, snapping non-standard shades (and any `+/-` relative shade) to
     * the nearest system palette resource. Returns the resource plus the exact requested tone so
     * the caller can interpolate when the snapped resource isn't the exact shade.
     */
    fun resolveColorResourceId(ref: String): Triple<Resources, Int, Float?>? {
        val (pkg, type, rawName) = parseResourceId(ref)
        var name = rawName
        val shadeIdx = name.indexOfLast { it == '_' }
        val isRelative = name.contains("-") || name.contains("+")

        val tone: Float? =
            if (pkg != "android") {
                null
            } else if (isRelative) {
                val signIdx = name.indexOfLast { it == '-' || it == '+' }
                val base = name.substring(shadeIdx + 1, signIdx).toFloatOrNull()
                val rel = name.substring(signIdx).toFloatOrNull()
                if (base == null || rel == null) {
                    logger.w("Failed to parse relative tone from $name")
                    return null
                }
                base + rel
            } else {
                val abs = name.substring(shadeIdx + 1).toFloatOrNull()
                if (abs == null) {
                    logger.w("Failed to parse absolute tone from $name")
                    return null
                }
                abs
            }

        if (tone != null && (isRelative || !TonalPalette.SHADE_KEYS.contains(tone.toInt()))) {
            val nearest = TonalPalette.SHADE_KEYS.minBy { abs(it - tone) }
            val snapped = name.substring(0, shadeIdx + 1) + nearest
            logger.i("Converted $name to $snapped")
            name = snapped
        }

        val (res, id) = resolveResourceId(pkg, type, name) ?: return null
        return Triple(res, id, tone)
    }

    fun resolveResourceId(ref: String): Pair<Resources, Int>? {
        val (pkg, type, name) = parseResourceId(ref)
        return resolveResourceId(pkg, type, name)
    }

    fun resolveResourceId(pkg: String?, type: String, name: String): Pair<Resources, Int>? {
        for ((res, resPkg) in resources) {
            val id = res.getIdentifier(name, type, pkg ?: resPkg)
            if (id != 0) return res to id
        }
        return null
    }

    private fun <T, R> tryRead(value: T?, reader: (T) -> R): R? {
        if (value == null) return null
        return try {
            reader(value)
        } catch (ex: IOException) {
            logger.w("Failed to read $value", ex)
            null
        }
    }

    fun assetExists(ref: String): Boolean {
        return try {
            if (ref.startsWith("@")) {
                resolveResourceId(ref) != null || resolveColorResourceId(ref) != null
            } else {
                pluginCtx.resources.assets.open(baseDir + ref).close()
                true
            }
        } catch (ex: IOException) {
            false
        }
    }

    fun copy(
        pluginCtx: Context? = null,
        hostCtx: Context? = null,
        messageBuffer: MessageBuffer? = null,
    ): AssetLoader =
        AssetLoader(
            pluginCtx ?: this.pluginCtx,
            hostCtx ?: this.hostCtx,
            baseDir,
            colorScheme,
            seedColor,
            overrideChroma,
            typefaceCache,
            getThemeSeedColor,
            messageBuffer ?: logger.buffer,
        )

    fun setSeedColor(seedColor: Int?, style: Int?) {
        this.seedColor = seedColor
        refreshColorPalette(style)
    }

    fun refreshColorPalette(style: Int?) {
        this.style = style ?: ThemeStyle.CLOCK
        val seed =
            seedColor
                ?: getThemeSeedColor(hostCtx).also { logColor("Theme Seed Color", it) }
        val scheme = ColorScheme(seed, false, this.style)
        colorScheme = scheme
        val cam = Cam.fromInt(scheme.seed)
        overrideChroma =
            if (cam != null && cam.chroma < LOW_CHROMA_LIMIT) cam.chroma * LOW_CHROMA_SCALE else null
    }

    fun getResourcesId(name: String): Int = getResource("id", name) { _, id -> id }

    fun getString(name: String): String = getResource("string", name) { res, id -> res.getString(id) }

    private fun <T> getResource(type: String, name: String, reader: (Resources, Int) -> T): T {
        val (res, id) = resolveResourceId(null, type, name) ?: throw Exception("Cannot find id of $name from $TAG")
        if (id == -1) throw Exception("Cannot find id of $id from $TAG")
        return reader(res, id)
    }

    private fun logColor(label: String, color: Int) {
        if (!DEBUG_COLOR) return
        val cam = Cam.fromInt(color)
        val tone = CamUtils.lstarFromInt(color)
        logger.i("$label -> (hue: ${cam.hue}, chroma: ${cam.chroma}, tone: $tone)")
    }

    fun getDefaultColor(isDarkTheme: Boolean): Int =
        readColor(if (isDarkTheme) DEFAULT_LIGHT_COLOR else DEFAULT_DARK_COLOR)

    companion object {
        private val TAG = AssetLoader::class.simpleName!!
        private const val DEBUG_COLOR = true
        private const val LOW_CHROMA_LIMIT = 15
        private const val LOW_CHROMA_SCALE = 1.5f
        private const val TYPEFACE_ANIMATION_FRAME_COUNT = 30
        private const val DEFAULT_LIGHT_COLOR = "@android:color/system_accent1_100+0"
        private const val DEFAULT_DARK_COLOR = "@android:color/system_accent2_600+0"

        /** Wallpaper-derived primary key color of the host's current theme. */
        private fun getThemeSeedColor(ctx: Context): Int =
            ctx.resources.getColor(android.R.color.system_palette_key_color_primary_light)
    }
}
