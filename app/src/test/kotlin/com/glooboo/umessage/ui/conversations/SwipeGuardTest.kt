package com.glooboo.umessage.ui.conversations

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SwipeGuardTest {
    private fun at(angle: Double, length: Float = 100f): Pair<Float, Float> =
        (length * Math.cos(Math.toRadians(angle))).toFloat() to (length * Math.sin(Math.toRadians(angle))).toFloat()

    @Test fun flatSwipeStarts() = at(10.0).let { assertTrue(isHorizontalSwipeIntent(it.first, it.second)) }
    @Test fun twentyFiveDegreesStarts() = at(25.0).let { assertTrue(isHorizontalSwipeIntent(it.first, it.second)) }
    @Test fun thirtyFiveDegreesIsScroll() = at(35.0).let { assertFalse(isHorizontalSwipeIntent(it.first, it.second)) }
    @Test fun diagonalIsScroll() = at(45.0).let { assertFalse(isHorizontalSwipeIntent(it.first, it.second)) }
    @Test fun steepIsScroll() = at(70.0).let { assertFalse(isHorizontalSwipeIntent(it.first, it.second)) }
    @Test fun verticalIsScroll() = assertFalse(isHorizontalSwipeIntent(0f, 100f))
    @Test fun negativeDirectionsCount() = assertTrue(isHorizontalSwipeIntent(-100f, -20f))

    private val width = 1000f
    private val fling = 2000f

    @Test fun longDragCommits() = assertTrue(shouldCommitSwipe(450f, width, 0f, fling))
    @Test fun longDragLeftCommits() = assertTrue(shouldCommitSwipe(-450f, width, 0f, fling))
    @Test fun shortSlowDragSnapsBack() = assertFalse(shouldCommitSwipe(200f, width, 100f, fling))
    @Test fun shortFlingBelowMinimumSnapsBack() = assertFalse(shouldCommitSwipe(100f, width, 5000f, fling))
    @Test fun flingPastMinimumCommits() = assertTrue(shouldCommitSwipe(200f, width, 3000f, fling))
    @Test fun flingBackwardsSnapsBack() = assertFalse(shouldCommitSwipe(200f, width, -3000f, fling))
    @Test fun noWidthNeverCommits() = assertFalse(shouldCommitSwipe(200f, 0f, 3000f, fling))
}
