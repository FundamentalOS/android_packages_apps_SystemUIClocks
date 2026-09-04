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

import android.content.Context
import android.content.res.Resources
import android.view.View
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.unit.dp
import androidx.constraintlayout.widget.ConstraintSet
import androidx.constraintlayout.widget.ConstraintSet.BOTTOM
import androidx.constraintlayout.widget.ConstraintSet.END
import androidx.constraintlayout.widget.ConstraintSet.PARENT_ID
import androidx.constraintlayout.widget.ConstraintSet.START
import androidx.constraintlayout.widget.ConstraintSet.TOP
import androidx.constraintlayout.widget.ConstraintSet.WRAP_CONTENT
import com.android.compose.animation.scene.ElementContentScope
import com.android.compose.animation.scene.ElementKey
import com.android.compose.animation.scene.MovableElementContentScope
import com.android.systemui.clocks.AssetLoader
import com.android.systemui.customization.clocks.DefaultClockFaceLayout
import com.android.systemui.customization.clocks.R as clocksR
import com.android.systemui.customization.clocks.utils.ContextUtils.getSafeStatusBarHeight
import com.android.systemui.plugins.keyguard.ui.clocks.AodClockBurnInModel
import com.android.systemui.plugins.keyguard.ui.clocks.ClockFaceLayout
import com.android.systemui.plugins.keyguard.ui.clocks.ClockPreviewConfig
import com.android.systemui.plugins.keyguard.ui.clocks.ClockViewIds
import com.android.systemui.plugins.keyguard.ui.composable.elements.BaseLockscreenElement
import com.android.systemui.plugins.keyguard.ui.composable.elements.LockscreenElement
import com.android.systemui.plugins.keyguard.ui.composable.elements.LockscreenElementKeys
import com.android.systemui.plugins.keyguard.ui.composable.elements.LockscreenScope
import com.android.systemui.plugins.keyguard.ui.composable.elements.MovableLockscreenElement
import kotlin.math.roundToInt

/**
 * Pins the large word clock to the start of the lockscreen, a fixed distance below the keyguard's
 * small clock guideline. The keyguard hangs the date row and the smartspace off the clock
 * container, so they follow below the words.
 */
class WordClockFaceLayoutLarge(
    val view: WordClockViewLarge,
    private val assets: AssetLoader,
    private val context: Context,
    private val resources: Resources = context.resources,
) : ClockFaceLayout {
    private val density: Float
        get() = resources.displayMetrics.density

    @Deprecated("Unsupported with flexiglass. Move to composables.")
    override val views: List<View>
        get() = listOf(view)

    override val elements: List<BaseLockscreenElement> by lazy { listOf(LargeWordsElement(), LargeWordRegionElement()) }

    private inner class LargeWordsElement : MovableLockscreenElement {
        override val key = LockscreenElementKeys.Clock.Large
        override val context: Context = view.context

        @Composable
        override fun LockscreenScope<MovableElementContentScope>.LockscreenElement() {
            DefaultClockFaceLayout.clockView(view, Modifier.wrapContentSize().then(this.context.burnInModifier))
        }
    }

    /** The words, then the date / weather row and the smartspace cards. */
    private inner class LargeWordRegionElement : LockscreenElement {
        override val key: ElementKey = LockscreenElementKeys.Region.Clock.Large
        override val context: Context = view.context

        @Composable
        override fun LockscreenScope<ElementContentScope>.LockscreenElement() {
            val padding = dimensionResource(clocksR.dimen.clock_padding_start)
            Layout(
                content = {
                    LockscreenElement(LockscreenElementKeys.Clock.Large, Modifier.padding(start = padding + START_INSET_DP.dp))
                    LockscreenElement(LockscreenElementKeys.Smartspace.DWA.LargeClock.Above, Modifier.padding(horizontal = padding))
                    LockscreenElement(
                        LockscreenElementKeys.Smartspace.Cards,
                        Modifier.heightIn(min = dimensionResource(clocksR.dimen.enhanced_smartspace_height)),
                    )
                }
            ) { measurables, constraints ->
                check(measurables.size == 3)
                val childConstraints = constraints.copy(minWidth = 0, minHeight = 0)
                val words = measurables[0].measure(childConstraints)
                val dateWeather = measurables[1].measure(childConstraints)
                val smartspace = measurables[2].measure(childConstraints)
                val top = topOffset(resources)
                layout(constraints.maxWidth, constraints.maxHeight) {
                    words.placeRelative(0, top)
                    dateWeather.placeRelative(0, top + words.measuredHeight)
                    smartspace.placeRelative(0, top + words.measuredHeight + dateWeather.measuredHeight)
                }
            }
        }
    }

    @Deprecated("Unsupported with flexiglass. Move to composables.")
    override fun applyConstraints(constraints: ConstraintSet): ConstraintSet {
        val large = ClockViewIds.LOCKSCREEN_CLOCK_VIEW_LARGE
        val small = ClockViewIds.LOCKSCREEN_CLOCK_VIEW_SMALL
        pinTopStart(constraints, resources, fallbackTop())
        // Both faces are positioned from the keyguard's small clock guideline. The keyguard hangs
        // the date row, and below it the notifications, off the small clock container; hang that
        // container off this face instead. When the small face shows, this face is gone and
        // collapses onto the guideline, so the small container lands exactly where the keyguard
        // put it.
        val guideline = assets.getResourcesId(SMALL_CLOCK_GUIDELINE_TOP)
        val guideBegin = constraints.getConstraint(guideline)?.layout?.guideBegin ?: -1
        if (guideline != 0 && guideBegin >= 0) {
            constraints.connect(large, TOP, guideline, BOTTOM, topOffset(resources))
            constraints.connect(small, TOP, large, BOTTOM)
        }
        return constraints
    }

    /** Where the small clock guideline sits when the constraint set has none (preview). */
    private fun fallbackTop(): Int = fallbackTop(assets, context)

    @Deprecated("Unsupported with flexiglass. Move to composables.")
    override fun applyPreviewConstraints(clockPreviewConfig: ClockPreviewConfig, constraints: ConstraintSet): ConstraintSet {
        pinTopStart(constraints, resources, fallbackTop())
        return constraints
    }

    @Deprecated("Unsupported with flexiglass. Move to composables.")
    override fun applyExternalDisplayPresentationConstraints(constraints: ConstraintSet): ConstraintSet {
        pinTopStart(constraints, resources, resources.getDimensionPixelSize(clocksR.dimen.clock_padding_start))
        return constraints
    }

    override fun applyAodBurnIn(aodBurnInModel: AodClockBurnInModel) {
        // The keyguard already moves the large clock container vertically together with the date
        // row, but it leaves the large clock out of the horizontal burn-in shift; follow the date
        // row sideways too. This face is never scaled (useAlternateSmartspaceAODTransition), yet
        // the keyguard can apply its large-clock burn-in scale before it knows that and never
        // resets it, which shrank the words and pushed them off the date column: undo it here.
        view.translationX = aodBurnInModel.translationX
        view.scaleX = 1f
        view.scaleY = 1f
    }

    companion object {
        /** Extra start inset beyond the keyguard's clock padding; 0 keeps the words on the date column. */
        const val START_INSET_DP = 0f
        /** First line of the large face below the keyguard's small clock guideline. */
        const val TOP_OFFSET_DP = 219f
        const val SMALL_CLOCK_GUIDELINE_TOP = "small_clock_guideline_top"
        const val KEYGUARD_CLOCK_TOP_MARGIN = "keyguard_clock_top_margin"

        /** Start inset of the words beyond the keyguard's clock padding. */
        fun startInset(resources: Resources): Int =
            resources.getDimensionPixelSize(clocksR.dimen.clock_padding_start) +
                (START_INSET_DP * resources.displayMetrics.density).roundToInt()

        /** Distance from the keyguard's small clock guideline to the large face's first line. */
        fun topOffset(resources: Resources): Int = (TOP_OFFSET_DP * resources.displayMetrics.density).roundToInt()

        /** Top of the large face when the constraint set carries no small clock guideline. */
        fun fallbackTop(assets: AssetLoader, context: Context): Int {
            val clockTopMargin =
                assets.getDimensionPixelSize(KEYGUARD_CLOCK_TOP_MARGIN)
                    ?: (18 * context.resources.displayMetrics.density).roundToInt()
            return context.getSafeStatusBarHeight() + clockTopMargin + topOffset(context.resources)
        }

        /**
         * Pins the large clock container to the top start. Shared with [WordClockFaceLayoutSmall]
         * because the default small-face layout also constrains the large container (it assumes a
         * centred single-view clock) and runs after this face's layout in the preview.
         */
        fun pinTopStart(constraints: ConstraintSet, resources: Resources, topMargin: Int) {
            val id = ClockViewIds.LOCKSCREEN_CLOCK_VIEW_LARGE
            constraints.constrainWidth(id, WRAP_CONTENT)
            constraints.constrainHeight(id, WRAP_CONTENT)
            constraints.constrainMaxHeight(id, 0)
            constraints.clear(id, END)
            constraints.clear(id, BOTTOM)
            constraints.connect(id, START, PARENT_ID, START, startInset(resources))
            constraints.connect(id, TOP, PARENT_ID, TOP, topMargin)
        }
    }
}

/**
 * Small face: the default single-view layout, except that the large container is re-pinned after
 * the default preview / external-display constraints, which would otherwise centre it.
 */
class WordClockFaceLayoutSmall(view: WordClockViewSmall, private val assets: AssetLoader, private val context: Context) :
    DefaultClockFaceLayout(view) {
    @Deprecated("Unsupported with flexiglass. Move to composables.")
    override fun applyConstraints(constraints: ConstraintSet): ConstraintSet {
        super.applyConstraints(constraints)
        // The large layout hangs the small clock container off the large face so that the date row
        // and the notifications sit below the words. The keyguard applies the target face's layout
        // last, so while this face is the target put the container back on the keyguard's
        // guideline: the large face is kept visible while it fades out, and hanging off it would
        // push the container (and the date row under it) below the incoming notification for the
        // length of the fade, then snap it up.
        val guideline = assets.getResourcesId(WordClockFaceLayoutLarge.SMALL_CLOCK_GUIDELINE_TOP)
        val guideBegin = constraints.getConstraint(guideline)?.layout?.guideBegin ?: -1
        if (guideline != 0 && guideBegin >= 0) {
            constraints.connect(ClockViewIds.LOCKSCREEN_CLOCK_VIEW_SMALL, TOP, guideline, BOTTOM)
        }
        return constraints
    }

    @Deprecated("Unsupported with flexiglass. Move to composables.")
    override fun applyPreviewConstraints(clockPreviewConfig: ClockPreviewConfig, constraints: ConstraintSet): ConstraintSet {
        super.applyPreviewConstraints(clockPreviewConfig, constraints)
        WordClockFaceLayoutLarge.pinTopStart(constraints, context.resources, WordClockFaceLayoutLarge.fallbackTop(assets, context))
        return constraints
    }

    @Deprecated("Unsupported with flexiglass. Move to composables.")
    override fun applyExternalDisplayPresentationConstraints(constraints: ConstraintSet): ConstraintSet {
        super.applyExternalDisplayPresentationConstraints(constraints)
        WordClockFaceLayoutLarge.pinTopStart(
            constraints,
            context.resources,
            context.resources.getDimensionPixelSize(clocksR.dimen.clock_padding_start),
        )
        return constraints
    }
}
