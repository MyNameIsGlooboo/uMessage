package com.glooboo.umessage.messaging

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.extensions.messagesDB
import com.glooboo.umessage.extensions.rescheduleAllScheduledMessages
import com.glooboo.umessage.helpers.SCHEDULED_MESSAGE_ID
import com.glooboo.umessage.helpers.THREAD_ID
import com.glooboo.umessage.models.Message
import com.glooboo.umessage.receivers.ScheduledMessageReceiver
import com.glooboo.umessage.testing.FakeSmsMmsProvider
import com.glooboo.umessage.testing.ShadowRecordingSmsManager
import com.glooboo.umessage.testing.offMainThread
import com.glooboo.umessage.testing.resetSharedMessagesDatabase
import com.google.common.truth.Truth.assertThat
import org.fossify.commons.models.PhoneNumber
import org.fossify.commons.models.SimpleContact
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowAlarmManager
import org.robolectric.shadows.ShadowLooper

/**
 * Every path that sets or removes a scheduled message's alarm must build the same PendingIntent, so deleting the
 * message really removes its alarm, and an alarm that still fires for a message that is gone does nothing.
 */
@RunWith(AndroidJUnit4::class)
class ScheduledAlarmTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val alarmManager get() = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private val alarms get() = shadowOf(alarmManager).scheduledAlarms
    private val notifications get() =
        shadowOf(context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).allNotifications

    @Before
    fun setUp() {
        ShadowRecordingSmsManager.reset()
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
    }

    @After
    fun tearDown() {
        ShadowAlarmManager.reset()
        resetSharedMessagesDatabase()
    }

    private fun scheduled(id: Long = 1_234_567_890L, threadId: Long = id, type: Int = Telephony.Sms.MESSAGE_TYPE_QUEUED) =
        Message(
            id = id, body = "Happy birthday!", type = type, status = Telephony.Sms.STATUS_NONE,
            participants = arrayListOf(
                SimpleContact(1, 1, "Sara", "", arrayListOf(PhoneNumber("111", 0, "", "111")), ArrayList(), ArrayList())
            ),
            date = ((System.currentTimeMillis() + 3_600_000L) / 1000).toInt(), read = false, threadId = threadId,
            isMMS = false, attachment = null, senderPhoneNumber = "", senderName = "", senderPhotoUri = "",
            subscriptionId = -1, isScheduled = true
        )

    private fun fire(messageId: Long, threadId: Long? = null) {
        val intent = Intent().putExtra(SCHEDULED_MESSAGE_ID, messageId)
        if (threadId != null) intent.putExtra(THREAD_ID, threadId)
        offMainThread { ScheduledMessageReceiver().handleIntent(context, intent) }
        ShadowLooper.idleMainLooper()
    }

    @Test
    fun deletingAScheduledMessageRemovesItsAlarm() {
        val message = scheduled()
        context.scheduleMessage(message, warnIfInexact = false)
        assertThat(alarms).hasSize(1)

        context.cancelScheduleSendPendingIntent(message.id)

        assertThat(alarms).isEmpty()
    }

    @Test
    fun anAlarmSetByAnOlderBuildIsRemovedToo() {
        // Wave 2 also put the thread id in the intent; extras do not take part in the match.
        val message = scheduled()
        val old = Intent(context, ScheduledMessageReceiver::class.java)
            .putExtra(THREAD_ID, message.threadId).putExtra(SCHEDULED_MESSAGE_ID, message.id)
        val oldPending = PendingIntent.getBroadcast(
            context, message.id.toInt(), old, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, message.millis(), oldPending)

        context.cancelScheduleSendPendingIntent(message.id)

        assertThat(alarms).isEmpty()
    }

    @Test
    fun rescheduleOnAppStartReplacesTheAlarmAndCancelStillMatches() {
        val message = scheduled()
        offMainThread { context.messagesDB.insertOrUpdate(message) }
        context.scheduleMessage(message, warnIfInexact = false)

        offMainThread { context.rescheduleAllScheduledMessages() }
        assertThat(alarms).hasSize(1)

        context.cancelScheduleSendPendingIntent(message.id)
        assertThat(alarms).isEmpty()
    }

    @Test
    fun anAlarmForADeletedMessageDoesNothing() {
        FakeSmsMmsProvider.installSms()

        fire(1_234_567_890L, threadId = 1_234_567_890L)

        assertThat(ShadowRecordingSmsManager.textSends).isEmpty()
        assertThat(notifications).isEmpty()
    }

    @Test
    fun anAlarmForAHeldMessageDoesNotSendIt() {
        FakeSmsMmsProvider.installSms()
        val held = scheduled(type = Telephony.Sms.MESSAGE_TYPE_FAILED)
        offMainThread { context.messagesDB.insertOrUpdate(held) }

        fire(held.id)

        assertThat(ShadowRecordingSmsManager.textSends).isEmpty()
        assertThat(offMainThread { context.messagesDB.getAllScheduledMessages() }).hasSize(1)
    }

    @Test
    fun aMessageMovedToATemporaryThreadIsStillSent() {
        // The alarm of an older build carries the thread id the message had when it was scheduled.
        FakeSmsMmsProvider.installSms()
        val message = scheduled(threadId = 1_111_111_111L)
        offMainThread { context.messagesDB.insertOrUpdate(message) }

        fire(message.id, threadId = 42L)

        assertThat(ShadowRecordingSmsManager.textSends.map { it.destination }).containsExactly("111")
    }

    @Test
    fun withoutExactAlarmAccessTheMessageIsStillScheduled() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)

        val exact = context.scheduleMessage(scheduled(), warnIfInexact = false)

        assertThat(exact).isFalse()
        assertThat(alarms).hasSize(1)
    }
}
