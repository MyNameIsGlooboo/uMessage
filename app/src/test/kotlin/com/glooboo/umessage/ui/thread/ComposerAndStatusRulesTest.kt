package com.glooboo.umessage.ui.thread

import android.provider.Telephony.Sms
import com.glooboo.umessage.helpers.FILE_SIZE_NONE
import com.glooboo.umessage.messaging.usableSubscriptionId
import com.glooboo.umessage.models.Message
import com.glooboo.umessage.models.ThreadItem
import com.google.common.truth.Truth.assertThat
import org.fossify.commons.models.PhoneNumber
import org.fossify.commons.models.SimpleContact
import org.junit.Test

/** Small pure rules of the thread screen from the 0.4.2 messaging review. */
class ComposerAndStatusRulesTest {

    private fun sent(isMms: Boolean, status: Int) = Message(
        id = 1, body = "hi", type = Sms.MESSAGE_TYPE_SENT, status = status, participants = ArrayList(),
        date = 100, read = true, threadId = 1, isMMS = isMms, attachment = null, senderPhoneNumber = "",
        senderName = "", senderPhotoUri = "", subscriptionId = -1,
    )

    private fun statusOf(message: Message) =
        buildThreadItems(listOf(message), emptyMap(), seen = false, deliveryReportsOn = true)
        .items.filterIsInstance<ThreadItem.ThreadSent>().single()

    private fun contact(rawId: Int, number: String) = SimpleContact(
        rawId, rawId, number, "", arrayListOf(PhoneNumber(number, 0, "", number)), ArrayList(), ArrayList(),
    )

    // MS2

    @Test
    fun aSentPictureMessageIsNeverDelivered() {
        // Mms.STATUS is empty for an outgoing MMS and reads as 0, which is "complete" for an SMS.
        val item = statusOf(sent(isMms = true, status = Sms.STATUS_COMPLETE))
        assertThat(item.delivered).isFalse()
        assertThat(item.deliveryFailed).isFalse()
    }

    @Test
    fun aSentTextWithADeliveryReportIsDelivered() {
        assertThat(statusOf(sent(isMms = false, status = Sms.STATUS_COMPLETE)).delivered).isTrue()
    }

    // I3

    @Test
    fun aFailedDeliveryReportIsShown() {
        val item = statusOf(sent(isMms = false, status = Sms.STATUS_FAILED))
        assertThat(item.deliveryFailed).isTrue()
        assertThat(item.delivered).isFalse()
        assertThat(isDeliveryFailed(Sms.STATUS_FAILED + 3)).isTrue()
    }

    @Test
    fun pendingOrMissingReportsAreNotFailures() {
        assertThat(isDeliveryFailed(Sms.STATUS_PENDING)).isFalse()
        assertThat(isDeliveryFailed(Sms.STATUS_NONE)).isFalse()
        assertThat(isDeliveryFailed(Sms.STATUS_COMPLETE)).isFalse()
    }

    // B8, B9

    @Test
    fun theStoredDraftIsPutBackIntoAnEmptyComposer() {
        assertThat(DraftText.restore("", "see you")).isEqualTo("see you")
    }

    @Test
    fun aStoredDraftDoesNotReplaceWhatIsAlreadyThere() {
        assertThat(DraftText.restore("shared link", "see you")).isEqualTo("see you\nshared link")
        assertThat(DraftText.restore("see you", "see you")).isEqualTo("see you")
        assertThat(DraftText.restore("see you at 8", "see you")).isEqualTo("see you at 8")
    }

    @Test
    fun aSharedTextIsAddedAfterTheDraft() {
        assertThat(DraftText.appendShared("", "link")).isEqualTo("link")
        assertThat(DraftText.appendShared("typed", "link")).isEqualTo("typed\nlink")
        assertThat(DraftText.appendShared("typed link", "link")).isEqualTo("typed link")
    }

    // MS5

    @Test
    fun aSecondSendFromTheComposerIsIgnoredWhileOneRuns() {
        val guard = SendGuard()
        assertThat(guard.tryStart()).isTrue()
        assertThat(guard.tryStart()).isFalse()
        guard.finish()
        assertThat(guard.tryStart()).isTrue()
    }

    // I5, I6

    @Test
    fun anEmojiThatWouldStraddleAPartBoundaryMovesToTheNextPart() {
        // 134 UTF-16 units is two parts by length alone, but 66 letters leave one unit and the emoji needs two.
        val text = "س".repeat(66) + "😀" + "س".repeat(66)
        assertThat(text.length).isEqualTo(134)
        assertThat(SmsSegments.of(text).parts).isEqualTo(3)
        assertThat(SmsSegments.of("س".repeat(134)).parts).isEqualTo(2)
    }

    @Test
    fun partCountsOfPlainTextAreUnchanged() {
        assertThat(SmsSegments.of("a".repeat(306)).parts).isEqualTo(2)
        assertThat(SmsSegments.of("a".repeat(307)).parts).isEqualTo(3)
        assertThat(SmsSegments.of("a".repeat(7 * 153)).parts).isGreaterThan(SmsSegments.MANY_PARTS)
    }

    // I4

    @Test
    fun filesThatPassOneByOneCanBeTooLargeTogether() {
        assertThat(totalTooLarge(listOf(150L, 150L), 200L)).isTrue()
        assertThat(totalTooLarge(listOf(150L), 200L)).isFalse()
        assertThat(totalTooLarge(listOf(90L, 90L), 200L)).isFalse()
        assertThat(totalTooLarge(listOf(900L, 900L), FILE_SIZE_NONE)).isFalse()
    }

    // B15

    @Test
    fun theSamePhoneWrittenTwoWaysIsOnePerson() {
        val chat = ThreadRecipients(listOf(contact(1, "+989123456789")))

        val added = chat + contact(2, "09123456789")

        assertThat(added.size).isEqualTo(1)
        assertThat(added.isGroup).isFalse()
        assertThat(ThreadRecipients(listOf(contact(1, "+989123456789"), contact(2, "09123456789"))).addresses)
            .containsExactly("+989123456789")
    }

    @Test
    fun aDifferentPhoneIsAddedAsAGroup() {
        val chat = ThreadRecipients(listOf(contact(1, "+989123456789"))) + contact(2, "+989121111111")
        assertThat(chat.size).isEqualTo(2)
        assertThat(chat.isGroup).isTrue()
    }

    // B16

    @Test
    fun aStoredSimThatIsGoneFallsBackToTheDefault() {
        assertThat(usableSubscriptionId(2, listOf(1))).isNull()
        assertThat(usableSubscriptionId(2, listOf(1, 2))).isEqualTo(2)
        assertThat(usableSubscriptionId(null, listOf(1))).isNull()
        assertThat(usableSubscriptionId(-1, listOf(1))).isEqualTo(-1)
        // The active SIMs cannot be read: the stored one is kept, as before.
        assertThat(usableSubscriptionId(2, null)).isEqualTo(2)
        assertThat(usableSubscriptionId(2, emptyList())).isEqualTo(2)
    }
}
