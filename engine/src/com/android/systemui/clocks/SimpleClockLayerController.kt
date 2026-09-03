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
import android.view.View
import com.android.systemui.log.core.MessageBuffer
import com.android.systemui.plugins.keyguard.ui.clocks.ClockAnimations
import com.android.systemui.plugins.keyguard.ui.clocks.ClockEvents
import com.android.systemui.plugins.keyguard.ui.clocks.ClockFaceConfig
import com.android.systemui.plugins.keyguard.ui.clocks.ClockFaceEvents
import kotlin.reflect.KClass

/** One rendered layer of a clock face. */
interface SimpleClockLayerController {
    val view: View
    val config: ClockFaceConfig
    val events: ClockEvents
    val animations: ClockAnimations
    val faceEvents: ClockFaceEvents

    /** Builds the controller matching a layer's type (and text style type, for digital hands). */
    object Factory {
        private val constructorMap =
            mutableMapOf<
                Pair<KClass<out ClockLayer>, KClass<out TextStyle>?>,
                (Context, AssetLoader, ClockLayer, Boolean, MessageBuffer) -> SimpleClockLayerController,
            >()

        init {
            register(AssetLayer::class) { ctx, assets, layer, _, _ ->
                SimpleAssetLayerController(ctx, assets, layer as AssetLayer)
            }
            register(AnalogHandLayer::class) { ctx, assets, layer, _, buffer ->
                SimpleAnalogHandLayerController(ctx, assets, layer as AnalogHandLayer, buffer)
            }
            register(ComposedDigitalHandLayer::class) { ctx, assets, layer, isLarge, buffer ->
                ComposedDigitalLayerController(ctx, assets, layer as ComposedDigitalHandLayer, isLarge, buffer)
            }
            register(DigitalHandLayer::class, FontTextStyle::class) { ctx, assets, layer, _, buffer ->
                SimpleDigitalHandLayerController(
                    ctx,
                    assets,
                    layer as DigitalHandLayer,
                    SimpleDigitalClockTextView(ctx, buffer),
                    buffer,
                )
            }
        }

        fun register(
            layerClass: KClass<out ClockLayer>,
            styleClass: KClass<out TextStyle>? = null,
            ctor: (Context, AssetLoader, ClockLayer, Boolean, MessageBuffer) -> SimpleClockLayerController,
        ) {
            constructorMap[layerClass to styleClass] = ctor
        }

        fun create(
            ctx: Context,
            assets: AssetLoader,
            layer: ClockLayer,
            isLargeClock: Boolean,
            messageBuffer: MessageBuffer,
        ): SimpleClockLayerController {
            val key = layer::class to (layer as? DigitalHandLayer)?.style?.let { it::class }
            val ctor = constructorMap[key] ?: throw IllegalArgumentException("Unrecognized ClockLayer type: $key")
            return ctor(ctx, assets, layer, isLargeClock, messageBuffer)
        }
    }
}
