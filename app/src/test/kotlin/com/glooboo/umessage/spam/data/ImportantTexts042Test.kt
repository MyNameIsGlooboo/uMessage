package com.glooboo.umessage.spam.data

import com.glooboo.umessage.spam.DecisionReason
import com.glooboo.umessage.ui.spam.BlockedHints
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** SP-lowB (B9): the Blocked tab and the weekly summary use the filter's own code and link tests. */
class ImportantTexts042Test {

    private val now = 50 * WeeklyDigest.WEEK_MS

    private fun held(body: String, address: String = "+15550100") = BlockedMessageEntity(
        address = address, body = body, subscriptionId = 1, isMms = false, receivedAt = now - 1000,
        reason = DecisionReason.UNKNOWN_SENDER, ruleId = null,
    )

    @Test fun aPhishingCodeWithALinkIsNotCountedAsImportant() {
        val digest = WeeklyDigest.compute(
            listOf(
                held("Your code 482913. Verify at secure-bank.help"),
                held("Your code 482914. Verify at http://evil.example/a"),
                held("Your verification code is 482915"),
            ),
            now,
        )

        assertThat(digest).isEqualTo(WeeklyDigest(3, 1))
    }

    @Test fun theBlockedTabSeesTheSameLinksAsTheFilter() {
        assertThat(BlockedHints.hasLink("Verify at secure-bank.help")).isTrue()
        assertThat(BlockedHints.looksImportant("Your code 482913. Verify at secure-bank.help")).isFalse()
    }

    @Test fun wordsInsideOtherWordsAreNoCodeWords() {
        assertThat(BlockedHints.looksImportant("Your shipping 4821 is late")).isFalse()
        assertThat(BlockedHints.looksImportant("Barcode 482913 scanned")).isFalse()
        assertThat(BlockedHints.looksImportant("Your verification code is 482913")).isTrue()
    }
}
