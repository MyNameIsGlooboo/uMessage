package com.glooboo.umessage.messaging

import android.app.Application
import android.provider.Telephony.Sms
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.models.Message
import com.glooboo.umessage.models.ThreadItem
import com.glooboo.umessage.testing.FakeSmsMmsProvider
import com.glooboo.umessage.testing.ShadowRecordingSmsManager
import com.glooboo.umessage.ui.thread.buildThreadItems
import com.google.common.truth.Truth.assertThat
import com.klinker.android.send_message.Settings
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** With delivery reports off, a group sent as separate texts said "Delivered" (emulator check of wave 6). */
@RunWith(AndroidJUnit4::class)
class DeliveryReportTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var provider: FakeSmsMmsProvider

    @Before
    fun setUp() {
        ShadowRecordingSmsManager.reset()
        provider = FakeSmsMmsProvider.installSms()
    }

    private fun sendGroup(reports: Boolean) = MessagingUtils(app).sendSmsMessage(
        text = "Dinner at 8?",
        addresses = linkedSetOf("111", "222"),
        subId = Settings.DEFAULT_SUBSCRIPTION_ID,
        requireDeliveryReport = reports,
    )

    private fun summaryStatus(): Int? {
        val id = provider.smsIds().first { (_, address) -> address.contains(MessagingUtils.ADDRESS_SEPARATOR) }.first
        return provider.intColumn("sms", id, "status")
    }

    @Test
    fun withReportsOffNoReportIsAskedForAndTheGroupRowIsNotDelivered() {
        sendGroup(reports = false)

        assertThat(ShadowRecordingSmsManager.textSends.map { it.destination }).containsExactly("111", "222")
        assertThat(ShadowRecordingSmsManager.textSends.flatMap { it.deliveryIntents }).isEmpty()
        assertThat(summaryStatus()).isNotEqualTo(Sms.STATUS_COMPLETE)
    }

    @Test
    fun withReportsOnEachRecipientAsksForItsOwnReport() {
        sendGroup(reports = true)

        assertThat(ShadowRecordingSmsManager.textSends.map { it.deliveryIntents.size }).containsExactly(1, 1)
        // Delivery is reported per recipient; the group row never claims it on its own.
        assertThat(summaryStatus()).isNotEqualTo(Sms.STATUS_COMPLETE)
    }

    @Test
    fun theThreadSaysDeliveredOnlyWhileReportsAreOn() {
        val sent = Message(
            id = 1, body = "hi", type = Sms.MESSAGE_TYPE_SENT, status = Sms.STATUS_COMPLETE, participants = ArrayList(),
            date = 100, read = true, threadId = 1, isMMS = false, attachment = null, senderPhoneNumber = "",
            senderName = "", senderPhotoUri = "", subscriptionId = -1,
        )
        fun status(on: Boolean) = buildThreadItems(listOf(sent), emptyMap(), seen = false, deliveryReportsOn = on)
            .items.filterIsInstance<ThreadItem.ThreadSent>().single()

        assertThat(status(on = false).delivered).isFalse()
        assertThat(status(on = true).delivered).isTrue()
    }
}
