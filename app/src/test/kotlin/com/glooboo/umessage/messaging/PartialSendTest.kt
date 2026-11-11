package com.glooboo.umessage.messaging

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.models.Attachment
import com.glooboo.umessage.ui.thread.ThreadRecipients
import com.glooboo.umessage.ui.thread.buildScheduledMessage
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/** A message with several pictures that went out in part must resend only what is left (core-1). */
@RunWith(AndroidJUnit4::class)
class PartialSendTest {

    private fun picture(n: Int) = Attachment(null, 5L, "content://x/$n", "image/jpeg", 0, 0, "p$n.jpg")

    private fun scheduled(vararg pictures: Attachment) = buildScheduledMessage(
        text = "caption", recipients = ThreadRecipients.fromNumbers(listOf("111")), subscriptionId = 1, messageId = 5L,
        millis = 5_000_000L, threadId = 12L, threadHasMessages = true, isMms = true, attachments = pictures.toList(),
    )

    @Test
    fun aPartlySentOutcomeNeedsSomeSentAndSomeLeft() {
        assertThat(SendOutcome(handedOff = true, sentCount = 3, textSent = true).partlySent).isFalse()
        assertThat(SendOutcome(handedOff = false, remainingAttachments = listOf(picture(1))).partlySent).isFalse()
        assertThat(SendOutcome(false, listOf(picture(3)), sentCount = 2, textSent = true).partlySent).isTrue()
    }

    @Test
    fun theHeldMessageKeepsOnlyTheUnsentPicturesAndDropsTheTextThatWent() {
        val message = scheduled(picture(1), picture(2), picture(3))
        val outcome = SendOutcome(false, listOf(picture(2)), sentCount = 2, textSent = true)

        val left = message.withUnsentOnly(outcome)

        assertThat(left.attachment!!.attachments.map { it.uriString }).containsExactly("content://x/2")
        assertThat(left.body).isEmpty()
    }

    @Test
    fun theTextStaysWhenItsPictureDidNotGoOut() {
        val message = scheduled(picture(1), picture(2))
        val outcome = SendOutcome(false, listOf(picture(2)), sentCount = 1, textSent = false)

        val left = message.withUnsentOnly(outcome)

        assertThat(left.body).isEqualTo("caption")
        assertThat(left.attachment!!.attachments).hasSize(1)
    }

    @Test
    fun aRetryOfABundledRowSkipsPicturesAnEarlierRetryDelivered() {
        val all = listOf(picture(1), picture(2), picture(3))
        val ledger = ResendLedger().plus(listOf("content://x/1", "content://x/3"), textNowSent = false)

        assertThat(ledger.undelivered(all).map { it.uriString }).containsExactly("content://x/2")
        assertThat(ResendLedger().undelivered(all)).hasSize(3)
    }

    @Test
    fun theTextIsSentOnlyUntilItWent() {
        assertThat(ResendLedger().textToSend("hi")).isEqualTo("hi")
        assertThat(ResendLedger().plus(emptyList(), textNowSent = true).textToSend("hi")).isEmpty()
    }

    @Test
    fun aLedgerSurvivesBeingStoredAndRead() {
        val ledger = ResendLedger(setOf("content://x/1", "content://x/2"), textSent = true)

        assertThat(ResendLedger.decode(ledger.encode())).isEqualTo(ledger)
        assertThat(ResendLedger.decode(null)).isEqualTo(ResendLedger())
        assertThat(ResendLedger.decode(ResendLedger().encode())).isEqualTo(ResendLedger())
    }
}
