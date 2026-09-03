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

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Point
import android.graphics.Rect
import android.view.View
import java.io.Closeable

/** Tracks a 0..1 animation fraction and reports direction changes. */
class AnimationState(fraction: Float) {
    var fraction: Float = fraction
        private set

    var isActive: Boolean = fraction > 0.5f
        private set

    /** @return (activeStateChanged, jumpedStraightBetweenEndpoints) */
    fun update(newFraction: Float): Pair<Boolean, Boolean> {
        if (newFraction == fraction) return isActive to false
        val wasActive = isActive
        val isJump = (fraction == 0f && newFraction == 1f) || (fraction == 1f && newFraction == 0f)
        isActive = newFraction > fraction
        fraction = newFraction
        return (wasActive != isActive) to isJump
    }
}

fun Canvas.saveBlock(): Closeable {
    val count = save()
    return Closeable { restoreToCount(count) }
}

/** Offset from a laid-out parent's centre to the centre of [targetRegion]. */
fun View.computeLayoutDiff(targetRegion: Rect, isLargeClock: Boolean): Pair<Float, Float> {
    val parent = this.parent
    if (parent is View && parent.isLaidOut && isLargeClock) {
        return (targetRegion.centerX() - parent.width / 2f) to (targetRegion.centerY() - parent.height / 2f)
    }
    return 0f to 0f
}

/** Animates a digit view's translation between two points, re-basing at the end. */
class DigitTranslateAnimator(val updateCallback: () -> Unit) {
    val updatedTranslate = Point(0, 0)
    private val baseTranslation = Point(0, 0)
    private var targetTranslation: Point? = null

    val bounceAnimator: ValueAnimator =
        ValueAnimator.ofFloat(1f).apply {
            duration = DEFAULT_ANIMATION_DURATION
            addUpdateListener {
                updateTranslation(it.animatedFraction, updatedTranslate)
                updateCallback()
            }
            addListener(
                object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) = rebase()

                    override fun onAnimationCancel(animation: Animator) = rebase()
                }
            )
        }

    fun rebase() {
        baseTranslation.x = updatedTranslate.x
        baseTranslation.y = updatedTranslate.y
    }

    fun animatePosition(
        animate: Boolean = true,
        delay: Long = 0,
        duration: Long = -1L,
        interpolator: TimeInterpolator? = null,
        targetTranslation: Point? = null,
        onAnimationEnd: Runnable? = null,
    ) {
        this.targetTranslation = targetTranslation ?: Point(0, 0)
        if (animate) {
            bounceAnimator.cancel()
            bounceAnimator.startDelay = delay
            bounceAnimator.duration = if (duration == -1L) DEFAULT_ANIMATION_DURATION else duration
            interpolator?.let { bounceAnimator.interpolator = it }
            if (onAnimationEnd != null) {
                val listener =
                    object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) {
                            onAnimationEnd.run()
                            bounceAnimator.removeListener(this)
                        }

                        override fun onAnimationCancel(animation: Animator) {
                            bounceAnimator.removeListener(this)
                        }
                    }
                bounceAnimator.addListener(listener)
            }
            bounceAnimator.start()
        } else {
            updateTranslation(1f, updatedTranslate)
            rebase()
            updateCallback()
        }
    }

    fun updateTranslation(progress: Float, out: Point) {
        val target = targetTranslation!!
        out.x = (baseTranslation.x + (target.x - baseTranslation.x) * progress).toInt()
        out.y = (baseTranslation.y + (target.y - baseTranslation.y) * progress).toInt()
    }

    companion object {
        const val DEFAULT_ANIMATION_DURATION = 500L
    }
}
