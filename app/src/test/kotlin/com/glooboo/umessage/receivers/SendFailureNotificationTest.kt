package com.glooboo.umessage.receivers

import android.app.Activity
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Telephony
import android.telephony.SmsManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.extensions.messagesDB
import com.glooboo.umessage.extensions.rescheduleAllScheduledMessages
import com.glooboo.umessage.helpers.SCHEDULED_MESSAGE_ID
import com.glooboo.umessage.helpers.THREAD_ID
import com.glooboo.umessage.models.Message
import com.glooboo.umessage.testing.FakeSmsMmsProvider
import com.glooboo.umessage.testing.ShadowRecordingSmsManager
import com.glooboo.umessage.testing.offMainThread
import com.glooboo.umessage.testing.resetSharedMessagesDatabase
import com.google.common.truth.Truth.assertThat
import org.fossify.commons.models.PhoneNumber
import org.fossify.commons.models.SimpleContact
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowLooper

/** Failures that happen while the user is away from the app must end up as a notification, not only a toast. */
@RunWith(AndroidJUnit4::class)
class SendFailureNotificationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val notifications get() =
        shadowOf(context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).allNotifications

    @After
    fun tearDown() = resetSharedMessagesDatabase()

    @Test
    fun failedMmsPostsANotification() {
        // RM-10: the MMS fails after the user left the app.
        FakeSmsMmsProvider.installMms()
        val uri = context.contentResolver.insert(Telephony.Mms.CONTENT_URI, ContentValues().apply {
            put(Telephony.Mms.THREAD_ID, 42L)
            put(Telephony.Mms.MESSAGE_BOX, Telephony.Mms.MESSAGE_BOX_OUTBOX)
        })!!
        val intent = Intent().putExtra("content_uri", uri.toString())

        offMainThread { MmsSentReceiver().handle(context, intent, SmsManager.MMS_ERROR_UNSPECIFIED) }
        ShadowLooper.idleMainLooper()

        assertThat(notifications).hasSize(1)
    }

    @Test
    fun sentMmsPostsNoNotification() {
        FakeSmsMmsProvider.installMms()
        val uri = context.contentResolver.insert(Telephony.Mms.CONTENT_URI, ContentValues().apply {
            put(Telephony.Mms.THREAD_ID, 42L)
        })!!

        offMainThread { MmsSentReceiver().handle(context, Intent().putExtra("content_uri", uri.toString()), Activity.RESULT_OK) }
        ShadowLooper.idleMainLooper()

        assertThat(notifications).isEmpty()
    }

    private fun scheduleBirthdayMessage(): Message {
        val message = Message(
            id = 7001L, body = "Happy birthday!", type = Telephony.Sms.MESSAGE_TYPE_QUEUED,
            status = Telephony.Sms.STATUS_NONE,
            participants = arrayListOf(
                SimpleContact(1, 1, "Sara", "", arrayListOf(PhoneNumber("111", 0, "", "111")), ArrayList(), ArrayList())
            ),
            date = (System.currentTimeMillis() / 1000).toInt(), read = false, threadId = 7001L, isMMS = false, attachment = null,
            senderPhoneNumber = "", senderName = "", senderPhotoUri = "", subscriptionId = -1, isScheduled = true
        )
        offMainThread { context.messagesDB.insertOrUpdate(message) }
        return message
    }

    private fun fire(message: Message) {
        val intent = Intent().putExtra(THREAD_ID, message.threadId).putExtra(SCHEDULED_MESSAGE_ID, message.id)
        offMainThread { ScheduledMessageReceiver().handleIntent(context, intent) }
        ShadowLooper.idleMainLooper()
    }

    private fun scheduledIds() = offMainThread { context.messagesDB.getAllScheduledMessages().map { it.id } }

    @Test
    fun scheduledMessageRefusedByTheRadioPostsANotification() {
        // RM-25: the send fails at 08:00 while the app is closed. The message is saved as failed in the thread, so
        // it can be resent from there, and the user is told.
        val provider = FakeSmsMmsProvider.installSms()
        ShadowRecordingSmsManager.reset()
        ShadowRecordingSmsManager.failingDestinations.add("111")
        val message = scheduleBirthdayMessage()

        fire(message)

        assertThat(notifications).hasSize(1)
        assertThat(provider.smsTypes()).containsExactly("111" to Telephony.Sms.MESSAGE_TYPE_FAILED)
        assertThat(scheduledIds()).isEmpty()
    }

    @Test
    fun scheduledMessageThatCannotBeSavedIsKeptAndPostsANotification() {
        Robolectric.setupContentProvider(RefusingProvider::class.java, Telephony.Sms.CONTENT_URI.authority)
        ShadowRecordingSmsManager.reset()
        val message = scheduleBirthdayMessage()

        fire(message)

        assertThat(notifications).hasSize(1)
        assertThat(ShadowRecordingSmsManager.textSends).isEmpty()
        assertThat(scheduledIds()).containsExactly(message.id)
    }

    @Test
    fun failedScheduledMessageIsHeldAndNeverRescheduled() {
        // Owner decision on RM-25: a failed scheduled message is kept but held; app start, boot or a time change
        // must not send it again by itself.
        Robolectric.setupContentProvider(RefusingProvider::class.java, Telephony.Sms.CONTENT_URI.authority)
        ShadowRecordingSmsManager.reset()
        val message = scheduleBirthdayMessage()
        fire(message)

        val held = offMainThread { context.messagesDB.getAllScheduledMessages().single() }
        assertThat(held.type).isEqualTo(Telephony.Sms.MESSAGE_TYPE_FAILED)

        offMainThread { context.rescheduleAllScheduledMessages() }

        assertThat(alarms.scheduledAlarms).isEmpty()
    }

    @Test
    fun pendingScheduledMessageIsStillRescheduled() {
        scheduleBirthdayMessage()

        offMainThread { context.rescheduleAllScheduledMessages() }

        assertThat(alarms.scheduledAlarms).hasSize(1)
    }

    private val alarms get() = shadowOf(context.getSystemService(Context.ALARM_SERVICE) as AlarmManager)

    @Test
    fun scheduledMessageSentFinePostsNoNotification() {
        FakeSmsMmsProvider.installSms()
        ShadowRecordingSmsManager.reset()
        val message = scheduleBirthdayMessage()

        fire(message)

        assertThat(notifications).isEmpty()
        assertThat(ShadowRecordingSmsManager.textSends.map { it.destination }).containsExactly("111")
        assertThat(scheduledIds()).isEmpty()
    }

    /** A telephony provider that cannot save anything, like a full disk. */
    class RefusingProvider : FakeSmsMmsProvider() {
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    }
}
