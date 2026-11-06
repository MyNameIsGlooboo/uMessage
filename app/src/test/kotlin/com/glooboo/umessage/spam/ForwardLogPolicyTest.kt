package com.glooboo.umessage.spam

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ForwardLogPolicyTest {

    @Test
    fun numbersWrittenDifferentlyAreTheSame() {
        assertThat(ForwardLogPolicy.sameNumber("+1 555 000 1111", "5550001111")).isTrue()
        assertThat(ForwardLogPolicy.sameNumber("07121234567", "+447121234567")).isTrue()
        assertThat(ForwardLogPolicy.sameNumber("+15550001111", "+15550002222")).isFalse()
        assertThat(ForwardLogPolicy.sameNumber("123", "0123")).isFalse()
    }

    @Test
    fun senderIdsCompareAsText() {
        assertThat(ForwardLogPolicy.sameNumber("VM-HDFC", "vm-hdfc")).isTrue()
        assertThat(ForwardLogPolicy.sameNumber("VM-HDFC", "VM-HDFD")).isFalse()
        assertThat(ForwardLogPolicy.sameNumber("BANKX", "bankx")).isTrue()
    }

    private fun row(id: Long, rule: Long, at: Long, status: ForwardStatus, attempts: Int = 1, updatedAt: Long = at) =
        ForwardLogEntry(id, at, updatedAt, rule, "BankX", 0, false, "+15550001111", null, status, null, attempts)

    @Test
    fun sendsAreCountedByWhenTheyWereMadeOrRetried() {
        val rows = listOf(
            row(1, rule = 1, at = 100, status = ForwardStatus.SENT),
            row(2, rule = 1, at = 50, status = ForwardStatus.FAILED),
            // Made long ago but retried in the window: counts.
            row(3, rule = 2, at = 10, status = ForwardStatus.SENT, attempts = 2, updatedAt = 120),
            // Made long ago, only its status changed later (a radio failure): does not count.
            row(4, rule = 2, at = 10, status = ForwardStatus.FAILED, attempts = 1, updatedAt = 120),
            // A skip never counts.
            row(5, rule = 1, at = 110, status = ForwardStatus.SKIPPED, attempts = 0),
        )
        assertThat(ForwardLogPolicy.countsByRule(rows, since = 40)).containsExactly(1L, 2, 2L, 1)
        // The daily count leaves failed sends out: they cost nothing.
        assertThat(ForwardLogPolicy.sentCount(rows, since = 40)).isEqualTo(2)
    }
}
