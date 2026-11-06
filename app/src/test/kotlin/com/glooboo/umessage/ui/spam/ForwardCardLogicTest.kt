package com.glooboo.umessage.ui.spam

import com.glooboo.umessage.spam.ForwardLogEntry
import com.glooboo.umessage.spam.ForwardStatus
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** ID-04 (last forward on a rule card) and BR-41 (the SMS parts estimate). */
class ForwardCardLogicTest {

    private val now = 100L * ForwardRuleStats.WEEK_MS

    private fun row(id: Long, rule: Long, at: Long, status: ForwardStatus) =
        ForwardLogEntry(id, at, at, rule, "BankX", at, false, "+15550001111", null, status, null, 1)

    @Test
    fun eachRuleShowsItsLastForwardAndThisWeeksSentCount() {
        val stats = ForwardRuleStats.of(
            listOf(
                row(1, rule = 1, at = now - 1_000, status = ForwardStatus.FAILED),
                row(2, rule = 1, at = now - 2_000, status = ForwardStatus.SENT),
                row(3, rule = 1, at = now - 3_000, status = ForwardStatus.SKIPPED),
                row(4, rule = 1, at = now - ForwardRuleStats.WEEK_MS - 1, status = ForwardStatus.SENT),
                row(5, rule = 2, at = now - 5_000, status = ForwardStatus.PENDING),
            ),
            now,
        )

        assertThat(stats.keys).containsExactly(1L, 2L)
        assertThat(stats.getValue(1)).isEqualTo(ForwardRuleStats(now - 1_000, ForwardStatus.FAILED, week = 1))
        assertThat(stats.getValue(2)).isEqualTo(ForwardRuleStats(now - 5_000, ForwardStatus.PENDING, week = 1))
    }

    @Test
    fun smsPartsFollowTheGsmAndUnicodeLimits() {
        assertThat(SmsParts.count(160, unicode = false)).isEqualTo(1)
        assertThat(SmsParts.count(161, unicode = false)).isEqualTo(2)
        assertThat(SmsParts.count(306, unicode = false)).isEqualTo(2)
        assertThat(SmsParts.count(307, unicode = false)).isEqualTo(3)
        assertThat(SmsParts.count(70, unicode = true)).isEqualTo(1)
        assertThat(SmsParts.count(71, unicode = true)).isEqualTo(2)
        assertThat(SmsParts.count(SmsParts.EXAMPLE_PERSIAN, unicode = true)).isEqualTo(3)
        assertThat(SmsParts.count(0, unicode = true)).isEqualTo(1)
    }
}
