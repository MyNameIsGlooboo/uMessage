package com.glooboo.umessage.ui.forwarded

import com.glooboo.umessage.spam.ForwardLogEntry
import com.glooboo.umessage.spam.ForwardRule
import com.glooboo.umessage.spam.ForwardStatus
import com.glooboo.umessage.spam.data.ForwardResend.RetryResult
import com.glooboo.umessage.ui.spam.ForwardGatePolicy
import com.google.common.truth.Truth.assertThat
import java.time.ZoneId
import org.junit.Test

/** "Send again?" counts only the forwards that will really go out (round 2). */
class RetryPreviewTest {

    private val utc = ZoneId.of("UTC")
    private val now = 10L * 24 * 60 * 60 * 1000 + 12L * 60 * 60 * 1000
    private val rule = ForwardRule(id = 1, senderType = null, targets = listOf("+15550001111"))

    private fun entry(id: Long, status: ForwardStatus, ruleId: Long = 1, at: Long = now - 60_000) =
        ForwardLogEntry(id, at, at, ruleId, "BankX", at, false, "+15550001111", null, status, null, 1)

    private fun state(
        entries: List<ForwardLogEntry>,
        rules: List<ForwardRule> = listOf(rule),
        paused: Boolean = false,
        dailyLimit: Int = 0,
    ) = ForwardedUiState(loading = false, rules = rules, entries = entries, paused = paused, dailyLimit = dailyLimit)

    private fun ForwardedUiState.preview() = retryPreview(retryAllIds(now), now, utc)

    @Test
    fun everyFailedForwardGoesOutWhenNothingStopsIt() {
        val s = state(listOf(entry(2, ForwardStatus.FAILED), entry(1, ForwardStatus.FAILED)))
        assertThat(s.preview()).isEqualTo(RetryPreview(willSend = 2))
    }

    @Test
    fun pausedSendsNothing() {
        val s = state(listOf(entry(1, ForwardStatus.FAILED)), paused = true)
        assertThat(s.preview()).isEqualTo(RetryPreview(paused = 1))
    }

    @Test
    fun aDeletedOrDisabledRuleSendsNothing() {
        val failed = listOf(entry(2, ForwardStatus.FAILED), entry(1, ForwardStatus.FAILED, ruleId = 9))
        assertThat(state(failed, rules = listOf(rule.copy(enabled = false))).preview())
            .isEqualTo(RetryPreview(ruleOff = 2))
    }

    @Test
    fun theDailyLimitCountsEachRetryThatGoesOut() {
        val rows = listOf(
            entry(4, ForwardStatus.FAILED),
            entry(3, ForwardStatus.FAILED),
            entry(2, ForwardStatus.FAILED),
            entry(1, ForwardStatus.SENT),
        )
        // One sent today and a limit of 2: one retry fits, the other two are stopped.
        assertThat(state(rows, dailyLimit = 2).preview()).isEqualTo(RetryPreview(willSend = 1, limited = 2))
    }

    @Test
    fun theHourlyCapIsCountedToo() {
        val sent = (1L..19L).map { entry(100 + it, ForwardStatus.SENT) }
        val rows = listOf(entry(2, ForwardStatus.FAILED), entry(1, ForwardStatus.FAILED)) + sent
        // For each failed row, the 19 sends and the other failed try fill the rule's 20 for the hour.
        assertThat(state(rows).preview()).isEqualTo(RetryPreview(limited = 2))
    }

    @Test
    fun resumingAsksForTheLockAndPausingNever() {
        assertThat(ForwardGatePolicy.pauseChangeNeedsCheck(paused = false, lockEnabled = true)).isTrue()
        assertThat(ForwardGatePolicy.pauseChangeNeedsCheck(paused = true, lockEnabled = true)).isFalse()
        assertThat(ForwardGatePolicy.pauseChangeNeedsCheck(paused = false, lockEnabled = false)).isFalse()
    }

    @Test
    fun retriedEventKeepsWhyEachRetryWasNotSent() {
        val event = retriedEvent(
            listOf(
                RetryResult.SENT,
                RetryResult.LIMITED,
                RetryResult.LIMITED,
                RetryResult.PAUSED,
                RetryResult.RULE_OFF,
                RetryResult.FAILED,
                RetryResult.ORIGINAL_MISSING,
            ),
        )
        assertThat(event).isEqualTo(
            ForwardedEvent.Retried(sent = 1, failed = 1, missing = 1, paused = 1, ruleOff = 1, limited = 2),
        )
    }

    @Test
    fun singleRetryAtTheDailyLimitIsCountedAsLimited() {
        val event = retriedEvent(listOf(RetryResult.LIMITED))
        assertThat(event.limited).isEqualTo(1)
        assertThat(event.paused + event.ruleOff).isEqualTo(0)
    }
}
