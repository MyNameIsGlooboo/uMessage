package com.glooboo.umessage.ui.thread

import com.glooboo.umessage.helpers.TextSize
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ThreadPinchZoomTest {

    @Test
    fun aSmallPinchChangesNothing() {
        val steps = PinchZoomSteps()
        assertThat(steps.onZoom(TextSize.DEFAULT, 1.1f)).isNull()
        assertThat(steps.onZoom(TextSize.DEFAULT, 0.95f)).isNull()
    }

    @Test
    fun spreadingMovesOneStepUp() {
        val steps = PinchZoomSteps()
        assertThat(steps.onZoom(TextSize.DEFAULT, 1.1f)).isNull()
        assertThat(steps.onZoom(TextSize.DEFAULT, 1.2f)).isEqualTo(TextSize.LARGE)
    }

    @Test
    fun pinchingMovesOneStepDown() {
        assertThat(PinchZoomSteps().onZoom(TextSize.DEFAULT, 0.7f)).isEqualTo(TextSize.SMALL)
    }

    @Test
    fun aLongPinchContinuesStepByStep() {
        val steps = PinchZoomSteps()
        var size = TextSize.SMALL
        repeat(3) { size = steps.onZoom(size, 1.3f) ?: size }
        assertThat(size).isEqualTo(TextSize.LARGEST)
    }

    @Test
    fun aVeryLongPinchWalksAllSevenLevels() {
        val steps = PinchZoomSteps()
        var size = TextSize.XSMALL
        repeat(TextSize.entries.size) { size = steps.onZoom(size, 1.3f) ?: size }
        assertThat(size).isEqualTo(TextSize.GIANT)
        repeat(TextSize.entries.size) { size = steps.onZoom(size, 0.7f) ?: size }
        assertThat(size).isEqualTo(TextSize.XSMALL)
    }

    @Test
    fun theEndsHoldAndPinchingBackWorksAtOnce() {
        val steps = PinchZoomSteps()
        assertThat(steps.onZoom(TextSize.GIANT, 2f)).isNull()
        assertThat(steps.onZoom(TextSize.GIANT, 0.75f)).isEqualTo(TextSize.HUGE)
        assertThat(PinchZoomSteps().onZoom(TextSize.XSMALL, 0.2f)).isNull()
    }

    @Test
    fun aNewPinchStartsFresh() {
        val steps = PinchZoomSteps()
        steps.onZoom(TextSize.DEFAULT, 1.2f)
        steps.reset()
        assertThat(steps.onZoom(TextSize.DEFAULT, 1.1f)).isNull()
    }
}
