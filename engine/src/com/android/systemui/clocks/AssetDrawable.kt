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

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.drawable.Drawable
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Crossfades a layer's light / dark / doze artwork. Tints are applied as a MULTIPLY color filter
 * so white regions of the artwork take the tint while black regions stay black.
 */
class AssetDrawable(private val loader: AssetLoader, private val asset: AssetReference) : Drawable() {
    private val lightAsset: Drawable? = loader.tryReadDrawableAsset(asset.light)
    private val darkAsset: Drawable? = loader.tryReadDrawableAsset(asset.dark)
    private val dozeAsset: Drawable? = loader.tryReadDrawableAsset(asset.doze)

    private var masterAlpha = 255
    private var lightFraction = 0f
    private var dozeFraction = 0f

    init {
        updateTints()
        lightFraction = 1f
    }

    fun updateTints() {
        trySetTint(lightAsset, asset.lightTint)
        trySetTint(darkAsset, asset.darkTint)
        trySetTint(dozeAsset, asset.dozeTint)
        invalidateSelf()
    }

    private fun trySetTint(drawable: Drawable?, tintRef: String?) {
        if (drawable == null || tintRef == null) return
        val color = loader.tryReadColor(tintRef)
        if (color != null) {
            drawable.setColorFilter(color, PorterDuff.Mode.MULTIPLY)
        } else {
            drawable.colorFilter = null
        }
    }

    fun setLightFraction(fraction: Float) {
        if (lightFraction == fraction) return
        lightFraction = fraction
        invalidateSelf()
    }

    fun setDozeFraction(fraction: Float) {
        if (dozeFraction == fraction) return
        dozeFraction = fraction
        invalidateSelf()
    }

    override fun getIntrinsicHeight(): Int =
        max(
            max(lightAsset?.intrinsicHeight ?: 0, darkAsset?.intrinsicHeight ?: 0),
            dozeAsset?.intrinsicHeight ?: 0,
        )

    override fun getIntrinsicWidth(): Int =
        max(
            max(lightAsset?.intrinsicWidth ?: 0, darkAsset?.intrinsicWidth ?: 0),
            dozeAsset?.intrinsicWidth ?: 0,
        )

    override fun draw(canvas: Canvas) {
        if (masterAlpha <= 0) return
        tryDraw(canvas, lightAsset, lightFraction * (1 - dozeFraction))
        tryDraw(canvas, darkAsset, (1 - lightFraction) * (1 - dozeFraction))
        tryDraw(canvas, dozeAsset, dozeFraction)
    }

    private fun tryDraw(canvas: Canvas, drawable: Drawable?, fraction: Float) {
        if (drawable == null || fraction <= 0f) return
        drawable.alpha = (masterAlpha * fraction).roundToInt()
        drawable.draw(canvas)
    }

    override fun getAlpha(): Int = masterAlpha

    override fun setAlpha(alpha: Int) {
        masterAlpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {}

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    override fun onStateChange(state: IntArray): Boolean {
        updateChildBounds(bounds)
        return false
    }

    override fun onLevelChange(level: Int): Boolean {
        updateChildBounds(bounds)
        return false
    }

    override fun onBoundsChange(bounds: Rect) {
        updateChildBounds(bounds)
    }

    override fun onLayoutDirectionChanged(layoutDirection: Int): Boolean {
        var changed = lightAsset?.setLayoutDirection(layoutDirection) ?: false
        changed = (darkAsset?.setLayoutDirection(layoutDirection) ?: false) || changed
        changed = (dozeAsset?.setLayoutDirection(layoutDirection) ?: false) || changed
        updateChildBounds(bounds)
        return changed
    }

    private fun updateChildBounds(bounds: Rect) {
        lightAsset?.bounds = bounds
        darkAsset?.bounds = bounds
        dozeAsset?.bounds = bounds
    }
}
