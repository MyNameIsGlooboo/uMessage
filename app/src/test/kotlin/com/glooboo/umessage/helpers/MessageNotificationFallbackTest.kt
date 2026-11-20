package com.glooboo.umessage.helpers

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.provider.Telephony
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.R
import com.glooboo.umessage.extensions.messagesDB
import com.glooboo.umessage.extensions.postReceivedMessageNotification
import com.glooboo.umessage.models.Message
import com.glooboo.umessage.testing.offMainThread
import com.glooboo.umessage.testing.resetSharedMessagesDatabase
import com.google.common.truth.Truth.assertThat
import org.fossify.commons.extensions.baseConfig
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/** N5: a received message always leaves a notification: the full one, or on any error a plain one. */
@RunWith(AndroidJUnit4::class)
class MessageNotificationFallbackTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val manager get() = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val posted get() = shadowOf(manager).allNotifications

    @After
    fun tearDown() {
        notifyFault = null
        HiddenChatsSession.reset()
        resetSharedMessagesDatabase()
    }

    private fun failAt(stage: NotifyStage) {
        notifyFault = { if (it == stage) throw IllegalStateException("test fault") }
    }

    private fun post(body: String = BODY, notify: NotifyContext = NotifyContext()): NotifyResult = offMainThread {
        NotificationHelper(app).postMessageNotification(
            messageId = 1L, isMms = false, address = ADDRESS, body = body, threadId = THREAD, bitmap = null,
            sender = SENDER, notify = notify,
        )
    }

    private fun plain(ensureChannel: (String, String) -> Unit = ::createChannel) =
        PlainMessageNotification(app, ensureChannel)

    private fun createChannel(id: String, name: String) {
        manager.createNotificationChannel(NotificationChannel(id, name, NotificationManager.IMPORTANCE_HIGH))
    }

    private fun request(channelId: String?) = PlainMessageNotification.Request(THREAD, ADDRESS, SENDER, BODY, channelId)

    private fun Notification.title() = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
    private fun Notification.text() = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
    private fun Notification.bigText() = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
    private fun Notification.messagingStyle() =
        NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(this)

    @Test
    fun fullNotificationIsPostedWhenNothingFails() {
        val result = post()

        assertThat(result).isAnyOf(NotifyResult.POSTED, NotifyResult.POSTED_NO_SHORTCUT)
        assertThat(posted.single().messagingStyle()).isNotNull()
    }

    @Test
    fun plainNotificationIsPostedWhenTheBuildThrows() {
        failAt(NotifyStage.BUILD)

        val result = post()

        assertThat(result).isEqualTo(NotifyResult.FALLBACK)
        val n = posted.single()
        assertThat(shadowOf(manager).getNotification(THREAD.hashCode())).isNotNull()
        assertThat(n.channelId).isEqualTo(NOTIFICATION_CHANNEL_ID)
        assertThat(n.title()).isEqualTo(SENDER)
        assertThat(n.text()).isEqualTo(BODY)
        assertThat(n.messagingStyle()).isNull()
        assertThat(n.actions.orEmpty()).isEmpty()
        assertThat(n.contentIntent).isNotNull()
    }

    @Test
    fun plainNotificationIsPostedWhenNotifyOfTheFullOneThrows() {
        var thrown = false
        notifyFault = {
            if (it == NotifyStage.NOTIFY && !thrown) {
                thrown = true
                throw IllegalStateException("test fault")
            }
        }

        val result = post()

        assertThat(result).isEqualTo(NotifyResult.FALLBACK)
        assertThat(posted).hasSize(1)
    }

    @Test
    fun aShortcutErrorStillPostsTheFullNotification() {
        failAt(NotifyStage.SHORTCUT)

        val result = post()

        // Only the log line says POSTED_NO_SHORTCUT.
        assertThat(result).isEqualTo(NotifyResult.POSTED)
        assertThat(posted.single().messagingStyle()).isNotNull()
    }

    @Test
    fun fallbackKeepsTheCodeChannel() {
        failAt(NotifyStage.BUILD)

        post(body = CODE_BODY)

        assertThat(posted.single().channelId).isEqualTo(CODE_CHANNEL_ID)
    }

    @Test
    fun fallbackMasksTheCodeWhenCodesAreHidden() {
        app.w3aPrefs.hideCodesOnLockScreen = true
        failAt(NotifyStage.BUILD)

        post(body = CODE_BODY)

        val n = posted.single()
        assertThat(n.text()).doesNotContain(CODE)
        assertThat(n.bigText().orEmpty()).doesNotContain(CODE)
    }

    @Test
    fun fallbackOfAHiddenChatShowsNoSenderAndNoText() {
        HiddenChatsSession.reset()
        app.baseConfig.isAppPasswordProtectionOn = true
        HiddenChats.get(app).hide(THREAD)

        // The hidden branch of the full path returns before any fault point, so the plain form is posted directly.
        val result = plain().post(request(NOTIFICATION_CHANNEL_ID), IllegalStateException("test fault"))

        assertThat(result).isEqualTo(NotifyResult.FALLBACK)
        val n = posted.single()
        assertThat(n.title()).isEqualTo(app.getString(R.string.app_name))
        assertThat(n.text()).isEqualTo(app.getString(R.string.new_message))
        val shown = listOf(n.title(), n.text(), n.bigText()).joinToString(" ")
        assertThat(shown).doesNotContain(SENDER)
        assertThat(shown).doesNotContain(BODY)
    }

    @Test
    fun modeNonePostsNothing() {
        val result = post(notify = NotifyContext.NONE)

        assertThat(result).isEqualTo(NotifyResult.SKIPPED_MODE)
        assertThat(posted).isEmpty()
    }

    @Test
    fun aReadMessagePostsNothing() {
        val message = Message(
            id = 1L, body = BODY, type = Telephony.Sms.MESSAGE_TYPE_INBOX, status = 0, participants = ArrayList(),
            date = 1, read = true, threadId = THREAD, isMMS = false, attachment = null, senderPhoneNumber = ADDRESS,
            senderName = "", senderPhotoUri = "", subscriptionId = 0,
        )
        offMainThread { app.messagesDB.insertOrUpdate(message) }

        val result = offMainThread {
            app.postReceivedMessageNotification(
                messageId = 1L, isMms = false, address = ADDRESS, senderName = SENDER, body = BODY, threadId = THREAD,
                bitmap = null,
            )
        }

        assertThat(result).isEqualTo(NotifyResult.SKIPPED_READ)
        assertThat(posted).isEmpty()
    }

    @Test
    fun fallbackSurvivesAChannelError() {
        val result = plain { _, _ -> error("channel broke") }
            .post(request("no_such_channel"), IllegalStateException("test fault"))

        assertThat(result).isEqualTo(NotifyResult.FALLBACK)
        assertThat(posted.single().channelId).isEqualTo(NOTIFICATION_CHANNEL_ID)
    }

    @Test
    fun fallbackUsesTheNormalChannelWhenNoneIsGiven() {
        val result = plain().post(request(null), IllegalStateException("test fault"))

        assertThat(result).isEqualTo(NotifyResult.FALLBACK)
        assertThat(posted.single().channelId).isEqualTo(NOTIFICATION_CHANNEL_ID)
        assertThat(manager.getNotificationChannel(NOTIFICATION_CHANNEL_ID)).isNotNull()
    }

    private companion object {
        const val THREAD = 31L
        const val ADDRESS = "+15550001234"
        const val SENDER = "Alex"
        const val BODY = "See you at noon"
        const val CODE = "482913"
        const val CODE_BODY = "Your verification code is $CODE"
    }
}
