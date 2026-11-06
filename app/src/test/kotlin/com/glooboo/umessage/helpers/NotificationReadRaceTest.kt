package com.glooboo.umessage.helpers

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.os.Looper
import android.provider.Telephony
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.R
import com.glooboo.umessage.extensions.isMessageSavedAsRead
import com.glooboo.umessage.extensions.messagesDB
import com.glooboo.umessage.extensions.showReceivedMessageNotification
import com.glooboo.umessage.models.Message
import com.glooboo.umessage.testing.offMainThread
import com.glooboo.umessage.testing.resetSharedMessagesDatabase
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/** A notification that is posted after its chat was read is not shown, and the one that is up is taken down. */
@RunWith(AndroidJUnit4::class)
class NotificationReadRaceTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val manager get() = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    @After
    fun tearDown() {
        resetSharedMessagesDatabase()
    }

    private fun message(id: Long, read: Boolean) = Message(
        id = id, body = "Hello", type = Telephony.Sms.MESSAGE_TYPE_INBOX, status = 0, participants = ArrayList(),
        date = 1, read = read, threadId = THREAD, isMMS = false, attachment = null, senderPhoneNumber = "+15550100",
        senderName = "", senderPhotoUri = "", subscriptionId = 0,
    )

    private fun showStaleNotification() {
        val notification = NotificationCompat.Builder(app, "test").setSmallIcon(R.drawable.ic_messenger).build()
        manager.notify(THREAD.hashCode(), notification)
    }

    @Test
    fun theSavedReadFlagIsReadBack() {
        offMainThread {
            app.messagesDB.insertOrUpdate(message(1, read = true))
            app.messagesDB.insertOrUpdate(message(2, read = false))
        }
        assertThat(offMainThread { app.isMessageSavedAsRead(1, false) }).isTrue()
        assertThat(offMainThread { app.isMessageSavedAsRead(2, false) }).isFalse()
        assertThat(offMainThread { app.isMessageSavedAsRead(3, false) }).isFalse()
    }

    @Test
    fun aMessageReadBeforeItsNotificationWasPostedLeavesNoNotification() {
        offMainThread { app.messagesDB.insertOrUpdate(message(1, read = true)) }
        showStaleNotification()
        assertThat(shadowOf(manager).allNotifications).hasSize(1)

        app.showReceivedMessageNotification(
            messageId = 1, isMms = false, address = "+15550100", senderName = "Sender", body = "Hello",
            threadId = THREAD, bitmap = null,
        )
        // The saved flag is read on a background thread, then the main looper cancels.
        val until = System.currentTimeMillis() + 10_000L
        while (shadowOf(manager).allNotifications.isNotEmpty() && System.currentTimeMillis() < until) {
            Thread.sleep(50)
            shadowOf(Looper.getMainLooper()).idle()
        }

        assertThat(shadowOf(manager).allNotifications).isEmpty()
    }

    private companion object {
        const val THREAD = 7L
    }
}
