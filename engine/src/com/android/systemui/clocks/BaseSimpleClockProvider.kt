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
import android.content.theming.ThemeStyle
import android.graphics.drawable.Drawable
import com.android.systemui.customization.clocks.ClockLogger
import com.android.systemui.log.core.Logger
import com.android.systemui.log.core.MessageBuffer
import com.android.systemui.plugins.keyguard.ui.clocks.ClockController
import com.android.systemui.plugins.keyguard.ui.clocks.ClockMessageBuffers
import com.android.systemui.plugins.keyguard.ui.clocks.ClockMetadata
import com.android.systemui.plugins.keyguard.ui.clocks.ClockPickerConfig
import com.android.systemui.plugins.keyguard.ui.clocks.ClockProviderPlugin
import com.android.systemui.plugins.keyguard.ui.clocks.ClockSettings
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonParseException
import java.io.FileNotFoundException

/**
 * [ClockProviderPlugin] serving the JSON designs under `assets/clocks/`. With [lazilyLoadClocks]
 * the designs in [allowList] are only parsed when first requested.
 */
abstract class BaseSimpleClockProvider(
    private val allowList: List<String>? = null,
    private val blockList: List<String>? = null,
    private val lazilyLoadClocks: Boolean = true,
) : ClockProviderPlugin {
    private val TAG = this::class.simpleName!!
    private lateinit var hostCtx: Context
    private lateinit var pluginCtx: Context
    private lateinit var logger: Logger
    private var messageBuffers: ClockMessageBuffers? = null
    protected lateinit var assets: AssetLoader
    protected lateinit var dimenParser: DimensionParser
    private val availableDesigns = mutableMapOf<String, ClockDesign>()

    override fun onCreate(hostCtx: Context, pluginCtx: Context) {
        this.hostCtx = hostCtx
        this.pluginCtx = pluginCtx
    }

    override fun initialize(buffers: ClockMessageBuffers?) {
        messageBuffers = buffers
        val buffer = buffers?.infraMessageBuffer ?: ClockLogger.DEFAULT_MESSAGE_BUFFER
        logger = Logger(buffer, TAG)

        if (!this::dimenParser.isInitialized) dimenParser = DimensionParser(pluginCtx)
        if (!this::assets.isInitialized) assets = AssetLoader(pluginCtx, hostCtx, "clocks/", buffer)

        if (!lazilyLoadClocks) {
            val gson = buildGson()
            for (file in assets.listAssets("")) {
                if (!file.endsWith(".json") || file.startsWith("lotties/")) continue
                try {
                    loadDesign(gson, file)
                } catch (ex: JsonParseException) {
                    logger.e({ "Unable to load $str1" }, ex) { str1 = file }
                    ex.printStackTrace()
                }
            }
            return
        }

        if (allowList == null) {
            for (file in assets.listAssets("")) {
                if (!file.endsWith(".json") || file.startsWith("lotties/")) continue
                val id = file.substring(file.lastIndexOf("/") + 1, file.indexOf("."))
                availableDesigns[id] = ClockDesign("")
                logger.d({ "Found lazy $str1 from $str2" }) {
                    str1 = id
                    str2 = file
                }
            }
        }
    }

    private fun hasDesign(id: String): Boolean = availableDesigns[id]?.id?.isNotBlank() ?: false

    fun loadDesign(gson: Gson, file: String): ClockDesign? {
        val validator = ClockDesignValidator(dimenParser, assets, ::hasDesign)
        try {
            val design = gson.fromJson(assets.readTextAsset(file), ClockDesign::class.java)!!
            validator.validate(design)
            if (!validator.isValid) {
                val errors = StringBuilder()
                for (error in validator.errors) errors.append("    $error").append('\n')
                logger.e({ "Clock $str1 failed validation:\n$str2" }) {
                    str1 = design.id
                    str2 = errors.toString()
                }
                return null
            }

            if (isBlocked(design.id)) {
                logger.w({ "Clock $str1 is not allowed" }) { str1 = design.id }
                return null
            }

            availableDesigns[design.id] = design
            logger.i({ "Successfully loaded $str1" }) { str1 = file }
            return design
        } catch (ex: FileNotFoundException) {
            logger.e({ "Failed to find $str1" }, ex) { str1 = file }
            return null
        }
    }

    override fun getClocks(): List<ClockMetadata> {
        if (lazilyLoadClocks && allowList != null) {
            return allowList.map { ClockMetadata(it) }
        }
        return availableDesigns.keys.map { ClockMetadata(it) }
    }

    private fun tryGetDesign(id: String): ClockDesign? {
        val design = availableDesigns[id]
        if (design != null && design.id == id) return design

        if (!lazilyLoadClocks) {
            logger.e({ "Unexpected lazy load of $str1 ($str2)" }) {
                str1 = id
                str2 = design?.id ?: "null"
            }
        }
        return loadDesign(buildGson(), "$id.json")
    }

    override fun createClock(ctx: Context, settings: ClockSettings): ClockController {
        val id = settings.clockId ?: throw IllegalArgumentException("settings.clockId not specified")
        if (isBlocked(id)) throw IllegalArgumentException("$id not supported by $TAG")
        val design = tryGetDesign(id) ?: throw IllegalArgumentException("$id could not be loaded by $TAG")

        val loader = assets.copy(hostCtx = ctx)
        loader.setSeedColor(settings.seedColor, design.colorPalette)
        return SimpleClockController(pluginCtx, loader, design, messageBuffers)
    }

    override fun getClockPickerConfig(settings: ClockSettings): ClockPickerConfig {
        val id = settings.clockId!!
        val design = tryGetDesign(id) ?: throw IllegalArgumentException("${settings.clockId} is unsupported by $TAG")

        val thumbnail: Drawable =
            design.thumbnail?.let { assets.tryReadDrawableAsset(it) }
                ?: pluginCtx.resources.getDrawable(R.drawable.placeholder_thumbnail, null)!!

        return ClockPickerConfig(
            design.id,
            design.name?.let { assets.tryReadString(it) ?: it } ?: "",
            design.description?.let { assets.tryReadString(it) ?: it } ?: "",
            thumbnail,
            isReactiveToTone = design.colorPalette == null || design.colorPalette == ThemeStyle.CLOCK,
        )
    }

    private fun isBlocked(id: String): Boolean {
        if (blockList?.contains(id) == true) return true
        return allowList != null && !allowList.contains(id)
    }

    companion object {
        fun buildGson(): Gson {
            val layerFactory =
                BaseTypeAdapterFactory(ClockLayer::class).apply {
                    registerSubclass(AssetLayer::class, "asset")
                    registerSubclass(DigitalHandLayer::class, "digital-hand")
                    registerSubclass(AnalogHandLayer::class, "analog-hand")
                    registerSubclass(AnimatedHandLayer::class, "animated-hand")
                    registerSubclass(ComposedDigitalHandLayer::class, "composed-digital-hand")
                }
            val styleFactory =
                BaseTypeAdapterFactory(TextStyle::class).apply {
                    registerSubclass(FontTextStyle::class, "font-style")
                    registerSubclass(LottieTextStyle::class, "lottie-style")
                }
            return GsonBuilder()
                .registerTypeAdapterFactory(layerFactory)
                .registerTypeAdapterFactory(styleFactory)
                .registerTypeAdapter(ClockDesign::class.java, ClockDesign.Serializer())
                .create()
        }
    }
}
