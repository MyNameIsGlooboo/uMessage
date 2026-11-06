package com.glooboo.umessage.receivers

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.extensions.getMessagesDB
import com.glooboo.umessage.extensions.messagesDB
import com.glooboo.umessage.helpers.SCHEDULED_MESSAGE_ID
import com.glooboo.umessage.messaging.RepeatCalendar
import com.glooboo.umessage.messaging.RepeatRule
import com.glooboo.umessage.messaging.newScheduledRepeat
import com.glooboo.umessage.messaging.scheduleMessage
import com.glooboo.umessage.models.Message
import com.glooboo.umessage.testing.FakeSmsMmsProvider
import com.glooboo.umessage.testing.ShadowRecordingSmsManager
import com.glooboo.umessage.testing.offMainThread
import com.glooboo.umessage.testing.resetSharedMessagesDatabase
import com.glooboo.umessage.ui.thread.ThreadScheduleController
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.fossify.commons.models.PhoneNumber
import org.fossify.commons.models.SimpleContact
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowAlarmManager
import org.robolectric.shadows.ShadowLooper

/** B04: a repeating message books its next copy once it was sent, and a failed one pauses instead of looping. */
@RunWith(AndroidJUnit4::class)
class ScheduledRepeatReceiverTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val day = 24 * 60 * 60_000L
    private val alarms get() = shadowOf(context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).scheduledAlarms

    @Before
    fun setUp() {
        resetSharedMessagesDatabase()
        ShadowRecordingSmsManager.reset()
        FakeSmsMmsProvider.installSms()
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
    }

    @After
    fun tearDown() {
        ShadowAlarmManager.reset()
        resetSharedMessagesDatabase()
    }

    private fun repeating(id: Long, dueMillis: Long, rule: RepeatRule = RepeatRule.DAILY): Message {
        val message = Message(
            id = id, body = "Take the pill", type = Telephony.Sms.MESSAGE_TYPE_QUEUED,
            status = Telephony.Sms.STATUS_NONE,
            participants = arrayListOf(
                SimpleContact(1, 1, "Sara", "", arrayListOf(PhoneNumber("111", 0, "", "111")), ArrayList(), ArrayList())
            ),
            date = (dueMillis / 1000).toInt(), read = false, threadId = id, isMMS = false, attachment = null,
            senderPhoneNumber = "", senderName = "", senderPhotoUri = "", subscriptionId = -1, isScheduled = true,
        )
        offMainThread {
            context.messagesDB.insertOrUpdate(message)
            runBlocking {
                context.getMessagesDB().ScheduledRepeatsDao()
                    .insertOrUpdate(newScheduledRepeat(id, rule, RepeatCalendar.GREGORIAN, dueMillis))
            }
        }
        return message
    }

    private fun fire(messageId: Long) {
        val intent = Intent().putExtra(SCHEDULED_MESSAGE_ID, messageId)
        offMainThread { ScheduledMessageReceiver().handleIntent(context, intent) }
        ShadowLooper.idleMainLooper()
    }

    private fun rows() = offMainThread { context.messagesDB.getAllScheduledMessages() }

    private fun repeatOf(id: Long) = offMainThread {
        runBlocking { context.getMessagesDB().ScheduledRepeatsDao().get(id) }
    }

    @Test
    fun afterASendTheNextCopyIsBookedLater() {
        val due = System.currentTimeMillis() - 30_000L
        repeating(4001L, due)

        fire(4001L)

        assertThat(ShadowRecordingSmsManager.textSends).hasSize(1)
        val next = rows().single()
        assertThat(next.id).isNotEqualTo(4001L)
        assertThat(next.body).isEqualTo("Take the pill")
        assertThat(next.millis()).isGreaterThan(System.currentTimeMillis())
        assertThat(next.millis() / 1000).isEqualTo((due + day) / 1000)
        assertThat(repeatOf(next.id)?.rule).isEqualTo("DAILY")
        assertThat(repeatOf(4001L)).isNull()
        assertThat(alarms).hasSize(1)
    }

    @Test
    fun aFailedSendPausesTheRepeat() {
        ShadowRecordingSmsManager.failingDestinations.add("111")
        repeating(4002L, System.currentTimeMillis() - 30_000L)

        fire(4002L)

        // The radio refused it: the failed message stays in its thread for Retry, and no next copy is booked.
        assertThat(rows()).isEmpty()
        assertThat(repeatOf(4002L)).isNull()
        assertThat(alarms).isEmpty()
        // A second alarm for it does nothing either.
        fire(4002L)
        assertThat(rows()).isEmpty()
        assertThat(ShadowRecordingSmsManager.textSends).isEmpty()
    }

    @Test
    fun aPhoneThatWasOffSendsOneLateMessageAndBooksTheNextInTheFuture() {
        repeating(4003L, System.currentTimeMillis() - 3 * day - 60_000L)

        fire(4003L)

        assertThat(ShadowRecordingSmsManager.textSends).hasSize(1)
        val next = rows().single()
        assertThat(next.millis()).isGreaterThan(System.currentTimeMillis())
        assertThat(next.millis()).isLessThan(System.currentTimeMillis() + day + 60_000L)
    }

    @Test
    fun aMessageWithoutARepeatBooksNothing() {
        repeating(4004L, System.currentTimeMillis() - 30_000L, RepeatRule.NONE)

        fire(4004L)

        assertThat(ShadowRecordingSmsManager.textSends).hasSize(1)
        assertThat(rows()).isEmpty()
    }

    @Test
    fun deletingAllOfARepeatRemovesTheRule() {
        val message = repeating(4005L, System.currentTimeMillis() + day)
        context.scheduleMessage(message, warnIfInexact = false)

        offMainThread { ThreadScheduleController(context).cancel(4005L) }

        assertThat(rows()).isEmpty()
        assertThat(repeatOf(4005L)).isNull()
        assertThat(alarms).isEmpty()
    }
}
