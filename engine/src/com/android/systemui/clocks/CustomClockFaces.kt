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
import com.android.systemui.plugins.keyguard.ui.clocks.ClockFaceLayout

/**
 * Registry through which a clock apk adds its own composed face views (referenced by
 * `customizedView` in its design JSON) and face layouts on top of the engine's built-ins. Each apk
 * has its own copy of the engine, so registrations never leak between plugins.
 */
object CustomClockFaces {
    fun interface ViewFactory {
        fun create(ctx: Context, assets: AssetLoader, messageBuffer: MessageBuffer): DigitalClockFaceView
    }

    /** Returns the layout for [view] (assigning its well-known view id if it needs one), or null. */
    fun interface LayoutFactory {
        fun create(view: View, assets: AssetLoader, ctx: Context, isLargeClock: Boolean): ClockFaceLayout?
    }

    private val viewFactories = mutableMapOf<String, ViewFactory>()
    private val layoutFactories = mutableListOf<LayoutFactory>()

    fun registerView(customizedView: String, factory: ViewFactory) {
        viewFactories[customizedView] = factory
    }

    fun registerLayout(factory: LayoutFactory) {
        if (factory !in layoutFactories) layoutFactories += factory
    }

    fun createView(customizedView: String?, ctx: Context, assets: AssetLoader, messageBuffer: MessageBuffer): DigitalClockFaceView? =
        customizedView?.let { viewFactories[it]?.create(ctx, assets, messageBuffer) }

    fun createLayout(view: View, assets: AssetLoader, ctx: Context, isLargeClock: Boolean): ClockFaceLayout? =
        layoutFactories.firstNotNullOfOrNull { it.create(view, assets, ctx, isLargeClock) }
}
