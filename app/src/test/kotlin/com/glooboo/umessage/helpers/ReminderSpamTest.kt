package com.glooboo.umessage.helpers

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.R
import com.glooboo.umessage.extensions.conversationsDB
import com.glooboo.umessage.models.Conversation
import com.glooboo.umessage.models.MessageReminder
import com.glooboo.umessage.spam.SpamThread
import com.glooboo.umessage.spam.engine.SpamCause
import com.glooboo.umessage.testing.offMainThread
import com.glooboo.umessage.testing.resetSharedMessagesDatabase
import com.glooboo.umessage.ui.lock.LockScope
import com.google.common.truth.Truth.assertThat
import org.fossify.commons.extensions.baseConfig
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * H-2 (provider truth): while the app lock covers Spam, the reminder of a message in a Spam chat shows only "Reminder",
 * like the reminder of a hidden chat: no chat name, no text. Fake numbers only.
 */
@RunWith(AndroidJUnit4::class)
class ReminderSpamTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() = offMainThread {
        app.conversationsDB.insertOrUpdate(Conversation(SPAM, "", 0, true, "Prize club", "", false, SENDER))
        app.conversationsDB.insertSpamThread(SpamThread(SPAM, SENDER, SpamCause.UNKNOWN, spamSince = 1L))
        Unit
    }

    @After
    fun tearDown() {
        app.baseConfig.isAppPasswordProtectionOn = false
        LockScope.set(app, LockScope.WholeApp)
        resetSharedMessagesDatabase()
    }

    private fun show(): Notification {
        val reminder = MessageReminder(
            id = 7, messageId = 1, isMMS = false, threadId = SPAM, remindAt = 0, snapshotBody = BODY,
        )
        offMainThread { ReminderNotification(app).show(reminder, null) }
        val manager = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return shadowOf(manager).allNotifications.single()
    }

    private fun Notification.title() = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
    private fun Notification.text() = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()

    @Test
    fun whileTheLockCoversSpamTheReminderNamesNothing() {
        app.baseConfig.isAppPasswordProtectionOn = true
        LockScope.set(app, LockScope.ChosenParts)

        val shown = show()

        assertThat(shown.title()).isEqualTo(app.getString(R.string.reminder_title))
        assertThat(shown.text()).isEqualTo(app.getString(R.string.reminder_open_hint))
    }

    @Test
    fun withoutTheSpamLockTheReminderShowsTheChatAndTheText() {
        val shown = show()

        assertThat(shown.title()).isEqualTo(app.getString(R.string.reminder_title_named, "Prize club"))
        assertThat(shown.text()).isEqualTo(BODY)
    }

    private companion object {
        const val SPAM = 41L
        const val SENDER = "+15550001234"
        const val BODY = "Claim the prize"
    }
}
