package com.glooboo.umessage.ui.thread

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.models.Attachment
import com.glooboo.umessage.models.MessageKey
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDateTime
import java.time.ZoneOffset

/** The send and schedule rules split out of ThreadActivity (TU-50). */
@RunWith(AndroidJUnit4::class)
class ThreadSendControllerTest {

    private val sent = mutableListOf<OutgoingMessage>()
    private val errors = mutableListOf<Throwable>()

    private fun controller(answer: (OutgoingMessage) -> Boolean) =
        ThreadSendController({ sent += it; answer(it) }) { errors += it }

    @Test
    fun aHandedOffComposerMessageEmptiesTheComposer() {
        val result = controller { true }.send(OutgoingMessage("hi", listOf("111"), 1), fromComposer = true)
        assertThat(result).isEqualTo(ThreadSendController.Result(handedOff = true, clearComposer = true, completed = true))
    }

    @Test
    fun aMessageSentFromABubbleNeverTouchesTheComposer() {
        val result = controller { true }.send(OutgoingMessage("hi", listOf("111"), 1), fromComposer = false)
        assertThat(result.handedOff).isTrue()
        assertThat(result.clearComposer).isFalse()
    }

    @Test
    fun aSendThatSavedNothingKeepsTheComposer() {
        val result = controller { false }.send(OutgoingMessage("hi", listOf("111"), 1), fromComposer = true)
        assertThat(result).isEqualTo(ThreadSendController.Result(handedOff = false, clearComposer = false, completed = true))
        assertThat(errors).isEmpty()
    }

    @Test
    fun aThrowingSendIsReportedAndKeepsTheComposer() {
        val result = controller { throw IllegalStateException("no radio") }
            .send(OutgoingMessage("hi", listOf("111"), 1), fromComposer = true)
        assertThat(result).isEqualTo(ThreadSendController.Result(handedOff = false, clearComposer = false, completed = false))
        assertThat(errors.single()).hasMessageThat().isEqualTo("no radio")
    }

    @Test
    fun aGroupMessageGoesToEveryRecipientInOneSend() {
        val recipients = ThreadRecipients.fromNumbers(listOf("111", "222", "333"))
        val attachment = Attachment(null, -1L, "content://x/1", "image/jpeg", 0, 0, "a.jpg")
        controller { true }.send(
            OutgoingMessage("hello all", recipients.addresses, 2, listOf(attachment), MessageKey(9, true)),
            fromComposer = true,
        )
        assertThat(sent.single().addresses).containsExactly("111", "222", "333").inOrder()
        assertThat(sent.single().resendOf).isEqualTo(MessageKey(9, true))
    }

    @Test
    fun retryTextKeepsTheResendTargetUntilTheUserEdits() {
        val tracker = ResendTracker()
        tracker.prepare(MessageKey(57, false), "failed text")
        tracker.onTextChanged("failed text")
        assertThat(tracker.target).isEqualTo(MessageKey(57, false))

        tracker.onTextChanged("failed text, edited")
        assertThat(tracker.target).isNull()

        tracker.prepare(MessageKey(58, true), "again")
        tracker.clear()
        assertThat(tracker.target).isNull()
    }

    @Test
    fun scheduleTimesMustBeASecondAhead() {
        assertThat(isSchedulableTime(millis = 10_000, nowMillis = 9_000)).isTrue()
        assertThat(isSchedulableTime(millis = 10_000, nowMillis = 9_001)).isFalse()
    }

    @Test
    fun theBannerShowsTheYearOnlyForALaterYear() {
        fun at(year: Int, month: Int = 6) = LocalDateTime.of(year, month, 1, 12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        assertThat(isInLaterYear(at(2027, 1), at(2026, 12), ZoneOffset.UTC)).isTrue()
        assertThat(isInLaterYear(at(2026, 12), at(2026, 1), ZoneOffset.UTC)).isFalse()
        assertThat(isInLaterYear(at(2025), at(2026), ZoneOffset.UTC)).isFalse()
    }

    @Test
    fun aScheduledMessageForANewThreadUsesItsOwnIdAsTheThread() {
        val recipients = ThreadRecipients.fromNumbers(listOf("111", "222"))
        val attachment = Attachment(null, -1L, "content://x/1", "image/jpeg", 0, 0, "a.jpg")
        val message = buildScheduledMessage(
            text = "later", recipients = recipients, subscriptionId = 3, messageId = 77L, millis = 5_000_000L,
            threadId = 12L, threadHasMessages = false, isMms = true, attachments = listOf(attachment),
        )
        assertThat(message.threadId).isEqualTo(77L)
        assertThat(message.isScheduled).isTrue()
        assertThat(message.date).isEqualTo(5_000)
        assertThat(message.participants.map { it.phoneNumbers.first().normalizedNumber }).containsExactly("111", "222")
        assertThat(message.attachment!!.attachments.single().messageId).isEqualTo(77L)

        val existing = buildScheduledMessage(
            text = "later", recipients = recipients, subscriptionId = 3, messageId = 78L, millis = 5_000_000L,
            threadId = 12L, threadHasMessages = true, isMms = false, attachments = emptyList(),
        )
        assertThat(existing.threadId).isEqualTo(12L)
    }
}
