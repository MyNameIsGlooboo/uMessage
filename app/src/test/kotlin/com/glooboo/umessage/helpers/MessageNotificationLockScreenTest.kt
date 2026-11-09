package com.glooboo.umessage.helpers

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.R
import com.glooboo.umessage.extensions.config
import com.glooboo.umessage.activities.CopyCodeActivity
import com.glooboo.umessage.activities.ThreadActivity
import com.glooboo.umessage.testing.offMainThread
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/** SB-16 / RM-33: what a message notification shows on a secure lock screen and once the phone is unlocked. */
@RunWith(AndroidJUnit4::class)
class MessageNotificationLockScreenTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun post(setting: Int, body: String = "Are you free tonight?"): Notification {
        context.config.lockScreenVisibilitySetting = setting
        offMainThread {
            NotificationHelper(context).showMessageNotification(
                messageId = 1L, isMms = false, address = "+15550100", body = body, threadId = 77L,
                bitmap = null, sender = "Ali",
            )
        }
        return shadowOf(manager).allNotifications.single()
    }

    private fun Notification.title() = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
    private fun Notification.text() = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()

    @Test
    fun senderOnlyHidesTheTextOnTheLockScreen() {
        val n = post(LOCK_SCREEN_SENDER)

        assertThat(n.visibility).isEqualTo(NotificationCompat.VISIBILITY_PRIVATE)
        assertThat(n.publicVersion.title()).isEqualTo("Ali")
        assertThat(n.publicVersion.text()).isEqualTo(context.getString(R.string.new_message))
        // Unlocked, the shade still shows the message.
        assertThat(n.text()).isEqualTo("Are you free tonight?")
    }

    @Test
    fun nothingStillShowsTheMessageOnceUnlocked() {
        val n = post(LOCK_SCREEN_NOTHING)

        assertThat(n.visibility).isEqualTo(NotificationCompat.VISIBILITY_PRIVATE)
        assertThat(n.title()).isEqualTo("Ali")
        assertThat(n.text()).isEqualTo("Are you free tonight?")
        // The lock screen gets a meaningful but anonymous line: no sender, no text.
        assertThat(n.publicVersion.title()).isEqualTo(context.getString(R.string.app_name))
        assertThat(n.publicVersion.text()).isEqualTo(context.getString(R.string.new_message))
    }

    @Test
    fun senderAndMessageIsPublic() {
        val n = post(LOCK_SCREEN_SENDER_MESSAGE)

        assertThat(n.visibility).isEqualTo(NotificationCompat.VISIBILITY_PUBLIC)
        assertThat(n.text()).isEqualTo("Are you free tonight?")
    }

    @Test
    fun replyAndCopyCodeNeedAnUnlockedPhone() {
        context.notifyPrefs.copyCodeOnTap = false
        val n = post(LOCK_SCREEN_NOTHING, body = "Your code is 482913")

        val byTitle = n.actions.associateBy { it.title.toString() }
        val copy = byTitle.getValue(context.getString(R.string.onb_notif_copy_code))
        val reply = byTitle.getValue(context.getString(R.string.reply))
        assertThat(copy.isAuthenticationRequired).isTrue()
        assertThat(reply.isAuthenticationRequired).isTrue()
    }

    // M1: with Android's default lock-screen setting the private notification shows on the lock screen too, so the
    // posted text itself hides the code; "Copy code" still copies the real one.
    @Test
    fun hideCodesMasksThePostedTextAndCopyKeepsTheCode() {
        context.notifyPrefs.copyCodeOnTap = false
        context.w3aPrefs.hideCodesOnLockScreen = true
        val n = post(LOCK_SCREEN_SENDER_MESSAGE, body = "Your code is 482913")

        assertThat(n.text()).doesNotContain("482913")
        assertThat(n.text()).contains(CodeMasker.BULLETS)
        assertThat(n.publicVersion.text()).doesNotContain("482913")
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)
        assertThat(style!!.messages.last().text.toString()).doesNotContain("482913")
        val copy = n.actions.single { it.title.toString() == context.getString(R.string.onb_notif_copy_code) }
        // N6: starts the app's invisible copy activity directly, in a task of its own, so copying never opens the app.
        assertThat(shadowOf(copy.actionIntent).isActivityIntent).isTrue()
        assertThat(shadowOf(copy.actionIntent).flags and PendingIntent.FLAG_IMMUTABLE).isNotEqualTo(0)
        val intent = shadowOf(copy.actionIntent).savedIntent
        assertThat(intent.component?.className).isEqualTo(CopyCodeActivity::class.java.name)
        assertThat(intent.getStringExtra(CopyCodeActivity.EXTRA_CODE)).isEqualTo("482913")
        assertThat(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK).isNotEqualTo(0)
    }

    private fun Notification.contentIntentComponent() =
        shadowOf(contentIntent).savedIntent.component?.className

    // Tap to copy: a code notification copies the code on tap, and "Open" replaces "Copy code".
    @Test
    fun aTapOnACodeNotificationCopiesTheCodeAndOpenReplacesCopyCode() {
        val n = post(LOCK_SCREEN_SENDER, body = "Your code is 482913")

        assertThat(n.contentIntentComponent()).isEqualTo(CopyCodeActivity::class.java.name)
        val intent = shadowOf(n.contentIntent).savedIntent
        assertThat(intent.getStringExtra(CopyCodeActivity.EXTRA_CODE)).isEqualTo("482913")
        assertThat(intent.getIntExtra(CopyCodeActivity.EXTRA_NOTIFICATION_ID, -1)).isEqualTo(77L.hashCode())
        assertThat(intent.getBooleanExtra(CopyCodeActivity.EXTRA_REQUIRE_UNLOCK, true)).isFalse()
        val titles = n.actions.map { it.title.toString() }
        assertThat(titles).contains(context.getString(R.string.otp_notif_open_action))
        assertThat(titles).doesNotContain(context.getString(R.string.onb_notif_copy_code))
        val open = n.actions.single { it.title.toString() == context.getString(R.string.otp_notif_open_action) }
        assertThat(shadowOf(open.actionIntent).savedIntent.component?.className)
            .isEqualTo(ThreadActivity::class.java.name)
    }

    @Test
    fun withHiddenCodesTheTapWaitsForTheUnlock() {
        context.w3aPrefs.hideCodesOnLockScreen = true
        val n = post(LOCK_SCREEN_SENDER_MESSAGE, body = "Your code is 482913")

        val intent = shadowOf(n.contentIntent).savedIntent
        assertThat(intent.component?.className).isEqualTo(CopyCodeActivity::class.java.name)
        assertThat(intent.getBooleanExtra(CopyCodeActivity.EXTRA_REQUIRE_UNLOCK, false)).isTrue()
        // The real code is what is copied; the posted text still hides it.
        assertThat(intent.getStringExtra(CopyCodeActivity.EXTRA_CODE)).isEqualTo("482913")
        assertThat(n.text()).doesNotContain("482913")
    }

    @Test
    fun switchedOffATapOpensTheChatAndCopyCodeStaysAButton() {
        context.notifyPrefs.copyCodeOnTap = false
        val n = post(LOCK_SCREEN_SENDER, body = "Your code is 482913")

        assertThat(n.contentIntentComponent()).isEqualTo(ThreadActivity::class.java.name)
        val titles = n.actions.map { it.title.toString() }
        assertThat(titles).contains(context.getString(R.string.onb_notif_copy_code))
        assertThat(titles).doesNotContain(context.getString(R.string.otp_notif_open_action))
    }

    @Test
    fun aNormalMessageStillOpensTheChatOnTap() {
        val n = post(LOCK_SCREEN_SENDER)

        assertThat(n.contentIntentComponent()).isEqualTo(ThreadActivity::class.java.name)
    }

    @Test
    fun codesShowInFullWhenHidingIsOff() {
        val n = post(LOCK_SCREEN_SENDER, body = "Your code is 482913")
        assertThat(n.text()).isEqualTo("Your code is 482913")
    }

    // ID-26: codes have their own channel; other messages stay on the messages channel.
    @Test
    fun codesUseTheirOwnChannel() {
        assertThat(post(LOCK_SCREEN_SENDER, body = "Your code is 482913").channelId).isEqualTo(CODE_CHANNEL_ID)
        manager.cancelAll()
        assertThat(post(LOCK_SCREEN_SENDER).channelId).isNotEqualTo(CODE_CHANNEL_ID)
        val channel = manager.getNotificationChannel(CODE_CHANNEL_ID)
        assertThat(channel.importance).isEqualTo(NotificationManager.IMPORTANCE_HIGH)
    }
}
