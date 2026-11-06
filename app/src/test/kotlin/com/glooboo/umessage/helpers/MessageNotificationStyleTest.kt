package com.glooboo.umessage.helpers

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.testing.offMainThread
import com.glooboo.umessage.ui.components.displayNumber
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/** N08: a one-to-one notification names the sender in one spelling only (title, sender line and collapsed prefix). */
@RunWith(AndroidJUnit4::class)
class MessageNotificationStyleTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    // The notification shows the number as the inbox writes it; the platform formatter decides the grouping.
    private val shownSender get() = displayNumber(context, SENDER)

    private fun post(isGroup: Boolean): Notification {
        offMainThread {
            NotificationHelper(context).showMessageNotification(
                messageId = 1L, isMms = false, address = "+15551000148", body = "Hello", threadId = 5L,
                bitmap = null, sender = SENDER, notify = NotifyContext(isGroup = isGroup),
            )
        }
        return shadowOf(manager).allNotifications.single()
    }

    @Test
    fun oneToOneNamesTheSenderInOneSpelling() {
        val n = post(isGroup = false)
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)!!

        assertThat(style.isGroupConversation).isFalse()
        val person = style.messages.last().person!!
        // A blank name would make Android show "Me" as the sender and the title.
        assertThat(person.name.toString()).isEqualTo(shownSender)
        assertThat(person.name.toString()).isNotEqualTo(context.getString(com.glooboo.umessage.R.string.me))
        assertThat(person.key).isEqualTo("+15551000148")
        assertThat(person.icon).isNotNull()
        assertThat(n.extras.getCharSequence(Notification.EXTRA_TITLE).toString()).isEqualTo(person.name.toString())
        assertThat(style.conversationTitle).isNull()
    }

    @Test
    fun groupKeepsTheSenderName() {
        val n = post(isGroup = true)
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)!!

        assertThat(style.isGroupConversation).isTrue()
        assertThat(style.messages.last().person!!.name.toString()).isEqualTo(shownSender)
    }
}

private const val SENDER = "+1 555 100 0148"
