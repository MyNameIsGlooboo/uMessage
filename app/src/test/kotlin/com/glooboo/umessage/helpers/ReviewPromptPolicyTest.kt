package com.glooboo.umessage.helpers

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ReviewPromptPolicyTest {
    private val day = ReviewPromptPolicy.DAY_MS
    private val now = 1_000L * day

    private fun ask(
        firstInstall: Long = now - 7 * day,
        launchDays: Int = 5,
        promptsShown: Int = 0,
        lastPromptAt: Long = 0L,
        onMessagesList: Boolean = true,
        busy: Boolean = false,
        recentSendFailure: Boolean = false,
    ) = ReviewPromptPolicy.shouldAsk(
        now, firstInstall, launchDays, promptsShown, lastPromptAt, onMessagesList, busy, recentSendFailure,
    )

    @Test
    fun asksWhenEveryConditionHoldsAtTheBoundaries() = assertThat(ask()).isTrue()

    @Test
    fun installAgeBoundaryIsSevenDays() {
        assertThat(ask(firstInstall = now - 7 * day + 1)).isFalse()
        assertThat(ask(firstInstall = now - 7 * day)).isTrue()
        assertThat(ask(firstInstall = now - 30 * day)).isTrue()
    }

    @Test
    fun unknownInstallTimeNeverAsks() {
        assertThat(ask(firstInstall = 0L)).isFalse()
    }

    @Test
    fun launchDaysBoundaryIsFive() {
        assertThat(ask(launchDays = 4)).isFalse()
        assertThat(ask(launchDays = 5)).isTrue()
        assertThat(ask(launchDays = 40)).isTrue()
    }

    @Test
    fun neverMoreThanTwoPrompts() {
        assertThat(ask(promptsShown = 1, lastPromptAt = now - 200 * day)).isTrue()
        assertThat(ask(promptsShown = 2, lastPromptAt = now - 200 * day)).isFalse()
        assertThat(ask(promptsShown = 3, lastPromptAt = now - 900 * day)).isFalse()
    }

    @Test
    fun secondPromptNeedsOneHundredTwentyDays() {
        assertThat(ask(promptsShown = 1, lastPromptAt = now - 120 * day + 1)).isFalse()
        assertThat(ask(promptsShown = 1, lastPromptAt = now - 120 * day)).isTrue()
    }

    @Test
    fun clockBeforeLastPromptDoesNotAsk() {
        assertThat(ask(promptsShown = 1, lastPromptAt = now + day)).isFalse()
    }

    @Test
    fun onlyOnTheMessagesList() = assertThat(ask(onMessagesList = false)).isFalse()

    @Test
    fun notWhileBusy() = assertThat(ask(busy = true)).isFalse()

    @Test
    fun notAfterARecentSendFailure() = assertThat(ask(recentSendFailure = true)).isFalse()

    @Test
    fun recentFailureWindowIsTenMinutes() {
        val minute = 60_000L
        val t = 100L * minute
        assertThat(ReviewPromptPolicy.isRecentFailure(t, 0L)).isFalse()
        assertThat(ReviewPromptPolicy.isRecentFailure(t, t - 10 * minute + 1)).isTrue()
        assertThat(ReviewPromptPolicy.isRecentFailure(t, t - 10 * minute)).isFalse()
        assertThat(ReviewPromptPolicy.isRecentFailure(t, t + 1)).isFalse()
    }
}
