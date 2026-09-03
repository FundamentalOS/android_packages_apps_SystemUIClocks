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
package com.android.systemui.clocks.lottie

import com.android.systemui.clocks.BaseSimpleClockProvider
import com.android.systemui.clocks.DigitalHandLayer
import com.android.systemui.clocks.LottieTextStyle
import com.android.systemui.clocks.SimpleClockLayerController
import com.android.systemui.clocks.SimpleDigitalHandLayerController
import com.android.systemui.plugins.annotations.Requires
import com.android.systemui.plugins.keyguard.ui.clocks.ClockProviderPlugin

/** Serves the DIGITAL_CLOCK_METRO design, whose digits are lottie animations. */
@Requires(target = ClockProviderPlugin::class, version = ClockProviderPlugin.VERSION)
class MetroClockProvider : BaseSimpleClockProvider(allowList = listOf("DIGITAL_CLOCK_METRO"), lazilyLoadClocks = false) {
    init {
        SimpleClockLayerController.Factory.register(DigitalHandLayer::class, LottieTextStyle::class) {
            ctx, assets, layer, isLargeClock, buffer ->
            SimpleDigitalHandLayerController(
                ctx,
                assets,
                layer as DigitalHandLayer,
                SimpleDigitalClockLottieView(ctx, isLargeClock, buffer),
                buffer,
            )
        }
    }
}
