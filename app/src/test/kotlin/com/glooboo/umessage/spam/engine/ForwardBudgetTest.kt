package com.glooboo.umessage.spam.engine

import com.glooboo.umessage.spam.ForwardLogEntry
import com.glooboo.umessage.spam.ForwardRule
import com.glooboo.umessage.spam.ForwardStatus
import com.glooboo.umessage.spam.RuleType
import com.google.common.truth.Truth.assertThat
import java.time.ZoneOffset
import org.junit.Test

/** M2 and M4: both cost limits, for new forwards and for retries, failing closed when the log cannot be read. */
class ForwardBudgetTest {

    private val zone = ZoneOffset.UTC
    private val day = 24L * 60 * 60 * 1000

    /** Noon on a day, so the hour before it is the same day. */
    private val now = 20_000 * day + day / 2

    private fun send(rule: Long, target: String = "+1555000$rule") = PlannedForward(rule, target, "text", null)

    private fun row(
        id: Long,
        rule: Long = 1,
        at: Long = now - 60_000,
        status: ForwardStatus = ForwardStatus.SENT,
    ) = ForwardLogEntry(id, at, at, rule, "BankX", at, false, "+15550001111", null, status, null, 1)

    private val rule =
        ForwardRule(id = 1, senderType = RuleType.NUMBER, senderPattern = "1234567", targets = listOf("+15550001111"))

    @Test
    fun anUnreadableLogStopsEverySend() {
        val split = ForwardBudget.split(listOf(send(1), send(2)), rows = null, now = now, dailyLimit = 0, zone = zone)

        assertThat(split.allowed).isEmpty()
        assertThat(split.rateLimited).hasSize(2)
    }

    @Test
    fun theHourlyCapCountsTheRulesSendsOfTheLastHour() {
        val rows = (1..ForwardRateLimit.MAX_PER_RULE_PER_HOUR).map { row(it.toLong()) } +
            row(100, at = now - 2 * ForwardRateLimit.WINDOW_MS)
        val split = ForwardBudget.split(listOf(send(1), send(2)), rows, now, dailyLimit = 0, zone = zone)

        assertThat(split.allowed.map { it.ruleId }).containsExactly(2L)
        assertThat(split.rateLimited.map { it.ruleId }).containsExactly(1L)
    }

    @Test
    fun theDailyLimitCountsTodaysSentForwardsButNotFailedOrSkippedOnes() {
        val rows = listOf(
            row(1, rule = 5, at = now - 3 * ForwardRateLimit.WINDOW_MS),
            row(2, rule = 6, at = now - 3 * ForwardRateLimit.WINDOW_MS),
            row(3, status = ForwardStatus.FAILED),
            row(4, status = ForwardStatus.SKIPPED),
            // Yesterday.
            row(5, at = now - day),
        )
        val split = ForwardBudget.split(listOf(send(1), send(2), send(3)), rows, now, dailyLimit = 3, zone = zone)

        assertThat(split.allowed.map { it.ruleId }).containsExactly(1L)
        assertThat(split.overDay.map { it.ruleId }).containsExactly(2L, 3L).inOrder()
        assertThat(ForwardBudget.dailyLimitReached(rows, now, 2, zone)).isTrue()
        assertThat(ForwardBudget.dailyLimitReached(rows, now, 3, zone)).isFalse()
        assertThat(ForwardBudget.dailyLimitReached(rows, now, 0, zone)).isFalse()
    }

    @Test
    fun aRetryIsRefusedWhilePausedOrForADeletedOrDisabledRule() {
        val failed = row(9, status = ForwardStatus.FAILED)

        fun block(rule: ForwardRule?, paused: Boolean, rows: List<ForwardLogEntry>) =
            ForwardBudget.retryBlock(failed, rule, paused, rows, now, dailyLimit = 0)

        assertThat(block(rule, paused = true, rows = emptyList())).isEqualTo(ForwardBudget.RetryBlock.PAUSED)
        assertThat(block(null, paused = false, rows = emptyList())).isEqualTo(ForwardBudget.RetryBlock.RULE_OFF)
        assertThat(block(rule.copy(enabled = false), paused = false, rows = emptyList()))
            .isEqualTo(ForwardBudget.RetryBlock.RULE_OFF)
        assertThat(block(rule, paused = false, rows = listOf(failed))).isNull()
    }

    @Test
    fun aRetryObeysBothLimitsAndFailsClosed() {
        val failed = row(9, status = ForwardStatus.FAILED)
        val today = listOf(row(1, rule = 4), row(2, rule = 4), failed)
        assertThat(ForwardBudget.retryBlock(failed, rule, false, today, now, dailyLimit = 2, zone = zone))
            .isEqualTo(ForwardBudget.RetryBlock.LIMIT)
        assertThat(ForwardBudget.retryBlock(failed, rule, false, today, now, dailyLimit = 3, zone = zone)).isNull()

        val hour = (1..ForwardRateLimit.MAX_PER_RULE_PER_HOUR).map { row(100L + it) } + failed
        assertThat(ForwardBudget.retryBlock(failed, rule, false, hour, now, dailyLimit = 0, zone = zone))
            .isEqualTo(ForwardBudget.RetryBlock.LIMIT)

        assertThat(ForwardBudget.retryBlock(failed, rule, false, rows = null, now = now, dailyLimit = 0, zone = zone))
            .isEqualTo(ForwardBudget.RetryBlock.LIMIT)
    }

    @Test
    fun retryAllCoversOnlyTheLastDaysFailedForwards() {
        val entries = listOf(
            row(1, status = ForwardStatus.FAILED),
            row(2, status = ForwardStatus.SENT),
            row(3, status = ForwardStatus.FAILED, at = now - ForwardBudget.RETRY_ALL_WINDOW_MS - 1),
            row(4, status = ForwardStatus.SKIPPED),
        )

        assertThat(ForwardBudget.retryAllIds(entries, now)).containsExactly(1L)
    }
}
