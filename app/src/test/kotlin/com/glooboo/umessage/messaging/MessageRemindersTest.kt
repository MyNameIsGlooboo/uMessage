package com.glooboo.umessage.messaging

import android.app.AlarmManager
import android.content.Context
import android.provider.Telephony
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.extensions.getMessagesDB
import com.glooboo.umessage.models.Message
import com.glooboo.umessage.models.MessageKey
import com.glooboo.umessage.testing.offMainThread
import com.glooboo.umessage.testing.resetSharedMessagesDatabase
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
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/** I51: the due times, the alarms and what happens to a reminder when its message goes. */
@RunWith(AndroidJUnit4::class)
class MessageRemindersTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val alarms get() = shadowOf(context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).scheduledAlarms
    private val zone = ZoneId.of("UTC")
    private val soon get() = System.currentTimeMillis() + 60_000L

    @Before
    fun setUp() {
        resetSharedMessagesDatabase()
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
    }

    @After
    fun tearDown() {
        ShadowAlarmManager.reset()
        resetSharedMessagesDatabase()
    }

    private fun message(id: Long = 77L) = Message(
        id = id, body = "Rent is due on the 5th", type = Telephony.Sms.MESSAGE_TYPE_INBOX,
        status = Telephony.Sms.STATUS_NONE,
        participants = arrayListOf(
            SimpleContact(1, 1, "Landlord", "", arrayListOf(PhoneNumber("222", 0, "", "222")), ArrayList(), ArrayList())
        ),
        date = 1_700_000_000, read = true, threadId = 9L, isMMS = false, attachment = null,
        senderPhoneNumber = "222", senderName = "Landlord", senderPhotoUri = "", subscriptionId = -1,
    )

    private fun saved() = offMainThread { runBlocking { context.getMessagesDB().MessageRemindersDao().getAll() } }

    @Test
    fun dueTimes() {
        val now = LocalDateTime.of(2026, 5, 10, 22, 30).atZone(zone).toInstant().toEpochMilli()

        val inThreeHours = reminderTime(ReminderChoice.IN_THREE_HOURS, now, zone)
        val morning = reminderTime(ReminderChoice.TOMORROW_MORNING, now, zone)

        assertThat(inThreeHours - now).isEqualTo(3 * 60 * 60_000L)
        assertThat(LocalDateTime.ofInstant(Instant.ofEpochMilli(morning), zone))
            .isEqualTo(LocalDateTime.of(2026, 5, 11, 9, 0))
    }

    @Test
    fun aReminderIsSavedWithAnAlarm() {
        val (reminder, exact) = offMainThread { context.addMessageReminder(message(), soon) }

        assertThat(exact).isTrue()
        assertThat(saved().single().snapshotBody).isEqualTo("Rent is due on the 5th")
        assertThat(alarms).hasSize(1)
        assertThat(reminder.id).isGreaterThan(0L)
    }

    @Test
    fun withoutExactAlarmAccessItFallsBackToAnInexactAlarm() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)

        val (_, exact) = offMainThread { context.addMessageReminder(message(), soon) }

        assertThat(exact).isFalse()
        assertThat(alarms).hasSize(1)
        assertThat(saved()).hasSize(1)
    }

    @Test
    fun rearmingSetsEveryAlarmAgain() {
        offMainThread {
            context.addMessageReminder(message(1L), soon)
            context.addMessageReminder(message(2L), soon)
        }
        // scheduledAlarms is a copy, so the alarms are cancelled through the manager.
        val manager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarms.forEach { alarm -> alarm.operation?.let { manager.cancel(it) } }
        assertThat(alarms).isEmpty()

        offMainThread { context.rearmReminders() }

        assertThat(alarms).hasSize(2)
    }

    @Test
    fun deletingTheMessageRemovesItsReminder() {
        offMainThread {
            context.addMessageReminder(message(1L), soon)
            context.addMessageReminder(message(2L), soon)
        }

        offMainThread { context.cancelRemindersForMessage(MessageKey(1L, false)) }

        assertThat(saved().map { it.messageId }).containsExactly(2L)
        assertThat(alarms).hasSize(1)
    }

    @Test
    fun theTargetIsFoundAgainUnderANewId() {
        val reminder = offMainThread { context.addMessageReminder(message(5L), soon) }.first

        // The same text came back under another id in the same thread.
        offMainThread { context.getMessagesDB().MessagesDao().insertOrUpdate(message(6L)) }

        assertThat(offMainThread { context.resolveReminderTarget(reminder) }).isEqualTo(MessageKey(6L, false))
    }
}
