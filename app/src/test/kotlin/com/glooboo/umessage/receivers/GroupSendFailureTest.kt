package com.glooboo.umessage.receivers

import android.app.Activity
import android.app.NotificationManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Telephony.Sms
import android.telephony.SmsManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.helpers.NotificationHelper
import com.glooboo.umessage.helpers.NotificationIds
import com.glooboo.umessage.helpers.cancelFailedSendNotification
import com.glooboo.umessage.helpers.hasFailedSendNotification
import com.glooboo.umessage.messaging.MessagingUtils
import com.glooboo.umessage.messaging.SentParts
import com.glooboo.umessage.testing.FakeSmsMmsProvider
import com.glooboo.umessage.testing.ShadowRecordingSmsManager
import com.glooboo.umessage.testing.offMainThread
import com.glooboo.umessage.testing.resetSharedMessagesDatabase
import com.google.common.truth.Truth.assertThat
import com.klinker.android.send_message.Settings
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * MS1: a group text sent as separate copies must not stay "Sent" in the group's chat when a copy fails on the radio.
 * MS3 and MS4: the "Message not sent" notification of a chat is one, and goes when the chat is opened.
 */
@RunWith(AndroidJUnit4::class)
class GroupSendFailureTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var provider: FakeSmsMmsProvider
    private val notifications get() =
        shadowOf(context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).allNotifications

    @Before
    fun setUp() {
        ShadowRecordingSmsManager.reset()
        provider = FakeSmsMmsProvider.installSms()
    }

    @After
    fun tearDown() = resetSharedMessagesDatabase()

    private fun typeOf(uri: Uri) = provider.intColumn("sms", uri.lastPathSegment!!.toLong(), Sms.TYPE)

    private fun insert(type: Int, address: String) = context.contentResolver.insert(
        Sms.CONTENT_URI,
        ContentValues().apply {
            put(Sms.ADDRESS, address)
            put(Sms.BODY, "Dinner at 8?")
            put(Sms.DATE, System.currentTimeMillis())
            put(Sms.TYPE, type)
        },
    )!!

    private fun report(copy: Uri, group: Uri?, resultCode: Int) {
        val intent = Intent(SendStatusReceiver.SMS_SENT_ACTION, copy).putExtra(SendStatusReceiver.EXTRA_PART_COUNT, 1)
        if (group != null) intent.putExtra(SmsStatusSentReceiver.EXTRA_GROUP_ROW_URI, group.toString())
        offMainThread { SmsStatusSentReceiver().handle(context, intent, resultCode) }
    }

    @Test
    fun aRadioFailureOfOneCopyMarksTheGroupRowFailed() {
        val group = insert(Sms.MESSAGE_TYPE_SENT, "111|222")
        val copy = insert(Sms.MESSAGE_TYPE_OUTBOX, "111")
        SentParts.start(context, copy)

        report(copy, group, SmsManager.RESULT_ERROR_GENERIC_FAILURE)

        assertThat(typeOf(copy)).isEqualTo(Sms.MESSAGE_TYPE_FAILED)
        assertThat(typeOf(group)).isEqualTo(Sms.MESSAGE_TYPE_FAILED)
    }

    @Test
    fun aCopyThatWentOutLeavesTheGroupRowAlone() {
        val group = insert(Sms.MESSAGE_TYPE_SENT, "111|222")
        val copy = insert(Sms.MESSAGE_TYPE_OUTBOX, "111")
        SentParts.start(context, copy)

        report(copy, group, Activity.RESULT_OK)

        assertThat(typeOf(copy)).isEqualTo(Sms.MESSAGE_TYPE_SENT)
        assertThat(typeOf(group)).isEqualTo(Sms.MESSAGE_TYPE_SENT)
    }

    @Test
    fun theSendCarriesTheGroupRowToTheSentReport() {
        MessagingUtils(context).sendSmsMessage(
            text = "Dinner at 8?",
            addresses = linkedSetOf("111", "222"),
            subId = Settings.DEFAULT_SUBSCRIPTION_ID,
            requireDeliveryReport = false,
        )

        val groupId = provider.smsIds()
            .first { (_, address) -> address.contains(MessagingUtils.ADDRESS_SEPARATOR) }.first
        val extras = ShadowRecordingSmsManager.textSends.flatMap { it.sentIntents }
            .map { shadowOf(it).savedIntent.getStringExtra(SmsStatusSentReceiver.EXTRA_GROUP_ROW_URI) }
        // Two copies, one sent report per text part (the test SmsManager cuts at 10 characters).
        assertThat(ShadowRecordingSmsManager.textSends).hasSize(2)
        assertThat(extras).hasSize(ShadowRecordingSmsManager.textSends.sumOf { it.sentIntents.size })
        assertThat(extras.all { it?.endsWith("/$groupId") == true }).isTrue()
    }

    @Test
    fun repeatedFailuresOfAChatLeaveOneNotificationAndOpeningTheChatClearsIt() {
        val helper = NotificationHelper(context)

        helper.showSendingFailedNotification("Sara", 7L)
        helper.showSendingFailedNotification("Sara", 7L)
        helper.showSendingFailedNotification("Sara", 7L)
        assertThat(notifications).hasSize(1)
        assertThat(context.hasFailedSendNotification()).isTrue()

        context.cancelFailedSendNotification(7L)
        assertThat(context.hasFailedSendNotification()).isFalse()
        assertThat(notifications).isEmpty()
    }

    @Test
    fun notificationIdsOfReminderAndChatNeverMeet() {
        // A reminder with id 7 used to share the id of the message notification of thread 7 (MS4).
        for (n in 1L..2_000L) {
            assertThat(NotificationIds.reminder(n)).isNotEqualTo(n.hashCode())
            assertThat(NotificationIds.failedSend(n)).isNotEqualTo(n.hashCode())
            assertThat(NotificationIds.failedSend(n)).isNotEqualTo(NotificationIds.reminder(n))
            assertThat(NotificationIds.sentLate(n)).isNotEqualTo(NotificationIds.failedSend(n))
        }
    }
}
