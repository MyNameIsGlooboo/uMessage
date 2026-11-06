package com.glooboo.umessage.helpers.smartreply

import com.glooboo.umessage.helpers.smartreply.SmartReplyPolicy.Candidate
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** I59: when chips may be offered at all. Invented numbers and texts only. */
class SmartReplyPolicyTest {

    private val now = 1_700_000_000_000L
    private val minute = 60_000L
    private val hour = 60 * minute

    private fun candidate(
        address: String = "07121234567",
        body: String = "Are you coming?",
        receivedMillis: Long = now - minute,
        isReceived: Boolean = true,
        isGroup: Boolean = false,
        knownSender: Boolean = true,
    ) = Candidate(address, body, receivedMillis, isReceived, isGroup, knownSender)

    private fun allows(candidate: Candidate) = SmartReplyPolicy.allows(candidate, now)

    @Test
    fun aRecentTextFromAKnownPersonIsAllowed() {
        assertThat(allows(candidate())).isTrue()
        assertThat(allows(candidate(address = "+14155550123"))).isTrue()
    }

    @Test
    fun onlyReceivedTextsInOnePersonThreadsOfKnownSenders() {
        assertThat(allows(candidate(isReceived = false))).isFalse()
        assertThat(allows(candidate(isGroup = true))).isFalse()
        assertThat(allows(candidate(knownSender = false))).isFalse()
    }

    @Test
    fun serviceSendersAreNeverAnswered() {
        assertThat(allows(candidate(address = "BankX"))).isFalse()
        assertThat(allows(candidate(address = "5000123"))).isFalse()
        assertThat(allows(candidate(address = " "))).isFalse()
    }

    @Test
    fun textsOlderThanSixHoursOrFromTheFutureAreSkipped() {
        assertThat(allows(candidate(receivedMillis = now - 5 * hour))).isTrue()
        assertThat(allows(candidate(receivedMillis = now - SmartReplyPolicy.MAX_AGE_MILLIS - 1))).isFalse()
        assertThat(allows(candidate(receivedMillis = now + 5 * minute))).isFalse()
        assertThat(allows(candidate(receivedMillis = now + 10_000L))).isTrue()
    }

    @Test
    fun linksMoneyAndLongNumbersAreSkipped() {
        assertThat(SmartReplyPolicy.bodyAllowed("see you soon")).isTrue()
        assertThat(SmartReplyPolicy.bodyAllowed("see https://example.com/a")).isFalse()
        assertThat(SmartReplyPolicy.bodyAllowed("see example.com")).isFalse()
        assertThat(SmartReplyPolicy.bodyAllowed("send 50 USD")).isFalse()
        assertThat(SmartReplyPolicy.bodyAllowed("code 1234")).isFalse()
        assertThat(SmartReplyPolicy.bodyAllowed("code ۱۲۳۴")).isFalse()
        assertThat(SmartReplyPolicy.bodyAllowed("at 5 pm")).isTrue()
    }

    @Test
    fun emptyAndVeryLongTextsAreSkipped() {
        assertThat(SmartReplyPolicy.bodyAllowed("  ")).isFalse()
        assertThat(SmartReplyPolicy.bodyAllowed("a ".repeat(100))).isFalse()
    }
}
