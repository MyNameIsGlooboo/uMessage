package com.glooboo.umessage.messaging

import android.app.Application
import android.provider.Telephony.Sms
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.receivers.SendStatusReceiver
import com.glooboo.umessage.testing.FakeSmsMmsProvider
import com.glooboo.umessage.testing.ShadowRecordingSmsManager
import com.google.common.truth.Truth.assertThat
import com.klinker.android.send_message.Settings
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

@RunWith(AndroidJUnit4::class)
class SendSmsMessageTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var provider: FakeSmsMmsProvider

    @Before
    fun setUp() {
        ShadowRecordingSmsManager.reset()
        provider = FakeSmsMmsProvider.installSms()
    }

    private fun send(text: String, vararg addresses: String, resend: Long? = null) = MessagingUtils(app).sendSmsMessage(
        text = text,
        addresses = linkedSetOf(*addresses),
        subId = Settings.DEFAULT_SUBSCRIPTION_ID,
        requireDeliveryReport = false,
        messageId = resend
    )

    private fun summaryId(): Long =
        provider.smsIds().first { (_, address) -> address.contains(MessagingUtils.ADDRESS_SEPARATOR) }.first

    @Test
    fun resendingAFailedGroupSmsRetriesOnlyTheRecipientWhoMissedIt() {
        // Ali (222) did not get "Dinner at 8?"; Sara (111) did. Resending from the group thread must go to Ali only,
        // turn Ali's own failed row into the new attempt and keep the group row as the group row.
        ShadowRecordingSmsManager.failingDestinations.add("222")
        assertThrows(SmsException::class.java) { send("Dinner at 8?", "111", "222") }
        val summary = summaryId()
        ShadowRecordingSmsManager.reset()

        send("Dinner at 8?", "111", "222", resend = summary)

        assertThat(ShadowRecordingSmsManager.textSends.map { it.destination }).containsExactly("222")
        assertThat(provider.smsTypes()).containsExactly(
            "111${MessagingUtils.ADDRESS_SEPARATOR}222" to Sms.MESSAGE_TYPE_SENT,
            "111" to Sms.MESSAGE_TYPE_OUTBOX,
            "222" to Sms.MESSAGE_TYPE_OUTBOX,
        ).inOrder()
    }

    @Test
    fun resendingAGroupSmsThatFailsAgainKeepsEachRecipientsOwnStatus() {
        ShadowRecordingSmsManager.failingDestinations.addAll(listOf("111", "222"))
        assertThrows(SmsException::class.java) { send("Dinner at 8?", "111", "222") }
        val summary = summaryId()
        ShadowRecordingSmsManager.reset()
        ShadowRecordingSmsManager.failingDestinations.add("222")

        assertThrows(SmsException::class.java) { send("Dinner at 8?", "111", "222", resend = summary) }

        assertThat(ShadowRecordingSmsManager.textSends.map { it.destination }).containsExactly("111")
        assertThat(provider.smsTypes()).containsExactly(
            "111${MessagingUtils.ADDRESS_SEPARATOR}222" to Sms.MESSAGE_TYPE_FAILED,
            "111" to Sms.MESSAGE_TYPE_OUTBOX,
            "222" to Sms.MESSAGE_TYPE_FAILED,
        ).inOrder()
    }

    @Test
    fun groupSmsKeepsSendingAfterARecipientFails() {
        // RM-05 / T6: sending to Ali fails; Sara and Reza must still get the message.
        ShadowRecordingSmsManager.failingDestinations.add("222")

        val error = assertThrows(SmsException::class.java) { send("Dinner at 8?", "111", "222", "333") }

        assertThat(error.errorCode).isEqualTo(SmsException.ERROR_SENDING_MESSAGE)
        assertThat(ShadowRecordingSmsManager.textSends.map { it.destination }).containsExactly("111", "333").inOrder()
        val types = provider.smsTypes().toMap()
        assertThat(types["222"]).isEqualTo(Sms.MESSAGE_TYPE_FAILED)
        assertThat(types["111"]).isEqualTo(Sms.MESSAGE_TYPE_OUTBOX)
        assertThat(types["333"]).isEqualTo(Sms.MESSAGE_TYPE_OUTBOX)
    }

    @Test
    fun groupSummaryRowIsNotLeftAsSentWhenARecipientFails() {
        ShadowRecordingSmsManager.failingDestinations.add("222")

        assertThrows(SmsException::class.java) { send("Dinner at 8?", "111", "222") }

        val summary = provider.smsTypes().first { it.first == "111${MessagingUtils.ADDRESS_SEPARATOR}222" }
        assertThat(summary.second).isEqualTo(Sms.MESSAGE_TYPE_FAILED)
    }

    @Test
    fun groupSmsWithNoFailureThrowsNothingAndKeepsTheSummarySent() {
        send("Dinner at 8?", "111", "222")

        assertThat(ShadowRecordingSmsManager.textSends.map { it.destination }).containsExactly("111", "222")
        val summary = provider.smsTypes().first { it.first.contains(MessagingUtils.ADDRESS_SEPARATOR) }
        assertThat(summary.second).isEqualTo(Sms.MESSAGE_TYPE_SENT)
    }

    @Test
    fun singleRecipientFailureIsReportedAndMarkedFailed() {
        ShadowRecordingSmsManager.failingDestinations.add("111")

        val error = assertThrows(SmsException::class.java) { send("Hi", "111") }

        assertThat(error.errorCode).isEqualTo(SmsException.ERROR_SENDING_MESSAGE)
        assertThat(provider.smsTypes()).containsExactly("111" to Sms.MESSAGE_TYPE_FAILED)
    }

    @Test
    fun everyPartOfALongMessageCarriesThePartCount() {
        // The sent receiver can only tell "all parts sent" from "some sent" if each part says how many there are.
        val text = "x".repeat(ShadowRecordingSmsManager.PART_LENGTH * 3)

        send(text, "111")

        val sent = ShadowRecordingSmsManager.textSends.single()
        assertThat(sent.parts).hasSize(3)
        assertThat(sent.sentIntents).hasSize(3)
        sent.sentIntents.forEach {
            assertThat(shadowOf(it).savedIntent.getIntExtra(SendStatusReceiver.EXTRA_PART_COUNT, -1)).isEqualTo(3)
        }
    }
}
