package com.glooboo.umessage.helpers

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.R
import com.glooboo.umessage.testing.offMainThread
import com.glooboo.umessage.testing.resetSharedMessagesDatabase
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/**
 * J1: a chat's own notifications are untagged. A tagged notice with the same id (the Spam notice of the chat, a held
 * message) is another notification and must not be taken for the chat's.
 */
@RunWith(AndroidJUnit4::class)
class NotificationTagLookupTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val manager get() = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    @After
    fun tearDown() {
        resetSharedMessagesDatabase()
    }

    /** A tagged notice with [id] that carries one message line, as a message notification would. */
    private fun taggedNotice(id: Int) {
        val style = NotificationCompat.MessagingStyle(Person.Builder().setName("Me").build())
            .addMessage(TAGGED_LINE, 1_000L, Person.Builder().setName("Other").build())
        val notice = NotificationCompat.Builder(app, "test").setSmallIcon(R.drawable.ic_messenger)
            .setStyle(style).build()
        manager.notify(TAG, id, notice)
    }

    private fun untagged() = manager.activeNotifications.single { it.tag == null }.notification

    @Test
    fun aTaggedNoticeWithTheChatsIdLendsNoLinesToTheChat() {
        taggedNotice(THREAD.hashCode())

        offMainThread {
            NotificationHelper(app).showMessageNotification(
                messageId = 1L, isMms = false, address = ADDRESS, body = BODY, threadId = THREAD,
                bitmap = null, sender = "Alex",
            )
        }

        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(untagged())!!
        assertThat(style.messages.map { it.text.toString() }).containsExactly(BODY)
    }

    @Test
    fun aTaggedNoticeWithTheSameIdDoesNotQuietAFailedSend() {
        taggedNotice(NotificationIds.failedSend(THREAD))

        NotificationHelper(app).showSendingFailedNotification("Alex", THREAD, automatic = true)

        // Nothing of this chat was showing yet, so the failure alerts.
        @Suppress("DEPRECATION")
        assertThat(untagged().priority).isEqualTo(NotificationCompat.PRIORITY_MAX)
    }

    private companion object {
        const val THREAD = 12L
        const val TAG = "spamless_blocked_chat"
        const val ADDRESS = "+15550001234"
        const val BODY = "See you at noon"
        const val TAGGED_LINE = "A line of another notice"
    }
}
