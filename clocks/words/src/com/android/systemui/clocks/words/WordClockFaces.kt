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

import com.android.systemui.clocks.CustomClockFaces
import com.android.systemui.plugins.keyguard.ui.clocks.ClockViewIds

/** Registers the word clock's faces with the engine (`customizedView` names used by the design). */
object WordClockFaces {
    const val LARGE = "WordClock"
    const val SMALL = "WordClockSmall"

    fun register() {
        CustomClockFaces.registerView(LARGE) { ctx, assets, messageBuffer -> WordClockViewLarge(ctx, assets, messageBuffer) }
        CustomClockFaces.registerView(SMALL) { ctx, assets, messageBuffer -> WordClockViewSmall(ctx, assets, messageBuffer) }
        CustomClockFaces.registerLayout { view, assets, ctx, isLargeClock ->
            when (view) {
                is WordClockViewLarge -> {
                    view.id = ClockViewIds.LOCKSCREEN_CLOCK_VIEW_LARGE
                    WordClockFaceLayoutLarge(view, assets, ctx)
                }
                is WordClockViewSmall -> {
                    view.id = ClockViewIds.LOCKSCREEN_CLOCK_VIEW_SMALL
                    WordClockFaceLayoutSmall(view, assets, ctx)
                }
                else -> null
            }
        }
    }
}
