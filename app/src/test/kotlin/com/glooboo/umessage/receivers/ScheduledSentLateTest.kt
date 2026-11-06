package com.glooboo.umessage.receivers

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.provider.Telephony
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.R
import com.glooboo.umessage.extensions.messagesDB
import com.glooboo.umessage.helpers.SCHEDULED_MESSAGE_ID
import com.glooboo.umessage.models.Message
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

/** RM-27: a scheduled message that goes out late says so, and why, instead of arriving late unexplained. */
@RunWith(AndroidJUnit4::class)
class ScheduledSentLateTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val lateNotifications
        get() = shadowOf(context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .allNotifications
            .filter {
                it.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ==
                    context.getString(R.string.scheduled_sent_late_title)
            }

    @Before
    fun setUp() {
        ShadowRecordingSmsManager.reset()
        FakeSmsMmsProvider.installSms()
    }

    @After
    fun tearDown() {
        ShadowAlarmManager.reset()
        resetSharedMessagesDatabase()
    }

    private fun fireScheduledFor(scheduledMillis: Long) {
        val message = Message(
            id = 4242L, body = "Meeting at 9", type = Telephony.Sms.MESSAGE_TYPE_QUEUED,
            status = Telephony.Sms.STATUS_NONE,
            participants = arrayListOf(
                SimpleContact(1, 1, "Sara", "", arrayListOf(PhoneNumber("111", 0, "", "111")), ArrayList(), ArrayList())
            ),
            date = (scheduledMillis / 1000).toInt(), read = false, threadId = 4242L, isMMS = false, attachment = null,
            senderPhoneNumber = "", senderName = "", senderPhotoUri = "", subscriptionId = -1, isScheduled = true
        )
        offMainThread { context.messagesDB.insertOrUpdate(message) }
        val intent = Intent().putExtra(SCHEDULED_MESSAGE_ID, message.id)
        offMainThread { ScheduledMessageReceiver().handleIntent(context, intent) }
        ShadowLooper.idleMainLooper()
    }

    @Test
    fun lateness() {
        assertThat(lateMinutes(scheduledMillis = 0L, nowMillis = 4 * 60_000L)).isNull()
        assertThat(lateMinutes(scheduledMillis = 0L, nowMillis = 14 * 60_000L + 5_000L)).isEqualTo(14L)
        assertThat(lateMinutes(scheduledMillis = 60_000L, nowMillis = 0L)).isNull()
    }

    @Test
    fun onTimeMessageSaysNothing() {
        fireScheduledFor(System.currentTimeMillis() - 30_000L)

        assertThat(ShadowRecordingSmsManager.textSends).hasSize(1)
        assertThat(lateNotifications).isEmpty()
    }

    @Test
    fun lateMessageWithoutExactAlarmsPointsToTheSetting() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)

        fireScheduledFor(System.currentTimeMillis() - 14 * 60_000L)

        val notification = lateNotifications.single()
        assertThat(notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString()).contains("14 min late")
        val target = shadowOf(notification.contentIntent).savedIntent
        assertThat(target.action).isEqualTo(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
    }

    @Test
    fun lateMessageWithExactAlarmsBlamesTheIdlePhone() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)

        fireScheduledFor(System.currentTimeMillis() - 30 * 60_000L)

        val text = lateNotifications.single().extras.getCharSequence(Notification.EXTRA_TEXT).toString()
        assertThat(text).isEqualTo(context.getString(R.string.scheduled_sent_late_idle, "Sara", 30))
    }
}
