package com.glooboo.umessage.ui.conversations

import kotlin.math.abs
import kotlin.math.tan

/**
 * A row swipe only starts when the finger moves this close to horizontal; anything steeper is a scroll (Gmail uses
 * about 35).
 */
const val MAX_SWIPE_ANGLE_DEGREES = 30f

/** Share of the row width a drag must reach to commit without a fling. */
const val SWIPE_COMMIT_FRACTION = 0.4f

/** A fling may commit a shorter drag, but never less than this share of the row width. */
const val SWIPE_FLING_MIN_FRACTION = 0.15f

/** Horizontal speed, in dp per second, that counts as a deliberate fling. */
const val SWIPE_FLING_VELOCITY_DP = 700f

/** True when the movement ([dx], [dy]) lies within [maxAngleDegrees] of the horizontal axis. */
fun isHorizontalSwipeIntent(dx: Float, dy: Float, maxAngleDegrees: Float = MAX_SWIPE_ANGLE_DEGREES): Boolean =
    dx != 0f && abs(dy) <= abs(dx) * tan(Math.toRadians(maxAngleDegrees.toDouble())).toFloat()

/**
 * Whether releasing at [offsetPx] with horizontal [velocityPxPerSecond] completes the swipe. A long drag always does;
 * a fling in the swipe's direction does once the row has moved a minimum distance.
 */
fun shouldCommitSwipe(
    offsetPx: Float,
    widthPx: Float,
    velocityPxPerSecond: Float,
    flingVelocityPx: Float,
): Boolean {
    if (widthPx <= 0f || offsetPx == 0f) return false
    val travel = abs(offsetPx) / widthPx
    if (travel >= SWIPE_COMMIT_FRACTION) return true
    val sameDirection = velocityPxPerSecond * offsetPx > 0f
    return sameDirection && abs(velocityPxPerSecond) >= flingVelocityPx && travel >= SWIPE_FLING_MIN_FRACTION
}
