package com.glooboo.umessage.receivers

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.R
import com.glooboo.umessage.extensions.config
import com.glooboo.umessage.extensions.getMessagesDB
import com.glooboo.umessage.helpers.LOCK_SCREEN_NOTHING
import com.glooboo.umessage.helpers.LOCK_SCREEN_SENDER_MESSAGE
import com.glooboo.umessage.helpers.SEARCHED_MESSAGE_ID
import com.glooboo.umessage.helpers.THREAD_ID
import com.glooboo.umessage.messaging.REMINDER_ID
import com.glooboo.umessage.models.MessageReminder
import com.glooboo.umessage.testing.offMainThread
import com.glooboo.umessage.testing.resetSharedMessagesDatabase
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowAlarmManager

/** I51: a due reminder posts one notification that opens the message, honours the lock screen, sends nothing. */
@RunWith(AndroidJUnit4::class)
class ReminderReceiverTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val notifications
        get() = shadowOf(context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .allNotifications

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

    private fun saveReminder(): MessageReminder = offMainThread {
        val saved = MessageReminder(
            messageId = 31L, isMMS = false, threadId = 8L, remindAt = 1L, snapshotBody = "Dentist on Monday at 9",
        )
        runBlocking { saved.copy(id = context.getMessagesDB().MessageRemindersDao().insert(saved)) }
    }

    private fun fire(reminder: MessageReminder) {
        val intent = Intent().putExtra(REMINDER_ID, reminder.id)
        offMainThread { ReminderReceiver().handleIntent(context, intent) }
    }

    @Test
    fun itPostsOneNotificationThatOpensTheThread() {
        val reminder = saveReminder()

        fire(reminder)

        val notification = notifications.single()
        assertThat(notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
            .isEqualTo("Dentist on Monday at 9")
        val target = shadowOf(notification.contentIntent).savedIntent
        assertThat(target.getLongExtra(THREAD_ID, 0L)).isEqualTo(8L)
        assertThat(target.hasExtra(SEARCHED_MESSAGE_ID)).isFalse()
    }

    @Test
    fun theReminderIsDeletedOnceItFired() {
        val reminder = saveReminder()

        fire(reminder)
        fire(reminder)

        assertThat(notifications).hasSize(1)
        val left = offMainThread { runBlocking { context.getMessagesDB().MessageRemindersDao().getAll() } }
        assertThat(left).isEmpty()
    }

    @Test
    fun theLockScreenVersionHidesTheTextUnlessTheSettingShowsIt() {
        context.config.lockScreenVisibilitySetting = LOCK_SCREEN_NOTHING
        fire(saveReminder())

        val hidden = notifications.single().publicVersion
        assertThat(hidden.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
            .isEqualTo(context.getString(R.string.reminder_open_hint))

        context.config.lockScreenVisibilitySetting = LOCK_SCREEN_SENDER_MESSAGE
        fire(saveReminder())

        assertThat(notifications.map { it.visibility }).contains(Notification.VISIBILITY_PUBLIC)
    }

    @Test
    fun aReminderThatIsGoneDoesNothing() {
        offMainThread { ReminderReceiver().handleIntent(context, Intent().putExtra(REMINDER_ID, 999L)) }

        assertThat(notifications).isEmpty()
    }
}
