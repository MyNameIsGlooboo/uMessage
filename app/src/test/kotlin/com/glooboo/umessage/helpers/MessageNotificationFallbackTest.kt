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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowLog

/** N5: a received message always leaves a notification: the full one, or on any error a plain one. */
@RunWith(AndroidJUnit4::class)
class MessageNotificationFallbackTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val manager get() = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val posted get() = shadowOf(manager).allNotifications

    @Before
    fun setUp() {
        ShadowLog.reset()
    }

    @After
    fun tearDown() {
        notifyFault = null
        HiddenChatsSession.reset()
        resetSharedMessagesDatabase()
    }

    private fun failAt(stage: NotifyStage) {
        notifyFault = { if (it == stage) error("test fault") }
    }

    /** The uMessageNotify lines written so far. */
    private fun notifyLines() = ShadowLog.getLogsForTag(NotifyLog.TAG).map { it.msg }

    /** Waits for [done], for the post that runs on its own worker thread. */
    private fun awaitUntil(done: () -> Boolean) {
        val deadline = System.currentTimeMillis() + WAIT_MS
        while (!done() && System.currentTimeMillis() < deadline) Thread.sleep(POLL_MS)
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
    fun aPostWritesExactlyOneContentFreeLogLine() {
        post()

        val line = notifyLines().single()
        // R3: the effective importance and the app switch ride along.
        assertThat(line).containsMatch("^post ch=$NOTIFICATION_CHANNEL_ID t=[0-9a-f]{8} r=POSTED(_NO_SHORTCUT)? ")
        assertThat(line).containsMatch(" i=([0-9]+|-) on=1")
        assertThat(line).doesNotContain(ADDRESS)
        assertThat(line).doesNotContain(SENDER)
        assertThat(line).doesNotContain(BODY)
    }

    @Test
    fun aFallbackWritesExactlyOneLine() {
        failAt(NotifyStage.BUILD)

        post()

        assertThat(notifyLines().single()).contains(" r=FALLBACK ")
    }

    @Test
    fun theRepostAfterAReplyIsLoggedAsSuch() {
        // R11: DirectReplyReceiver re-posts the chat with alertOnlyOnce; that is not a received text.
        offMainThread {
            NotificationHelper(app).showMessageNotification(
                messageId = 1L, isMms = false, address = ADDRESS, body = BODY, threadId = THREAD, bitmap = null,
                sender = null, alertOnlyOnce = true, offerSmartReplies = false,
            )
        }

        assertThat(notifyLines().single()).contains(" r=REPOSTED ")
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
                error("test fault")
            }
        }

        val result = post()

        assertThat(result).isEqualTo(NotifyResult.FALLBACK)
        // The plain form won: no messaging style.
        assertThat(posted.single().messagingStyle()).isNull()
        assertThat(posted.single().title()).isEqualTo(SENDER)
    }

    @Test
    fun onTheMainThreadAFailedNotifyStillFallsBack() {
        failAt(NotifyStage.NOTIFY)

        // Main thread and no shortcut yet: the post moves to a worker thread, which guards itself.
        NotificationHelper(app).showMessageNotification(
            messageId = 1L, isMms = false, address = ADDRESS, body = BODY, threadId = THREAD, bitmap = null,
            sender = SENDER, offerSmartReplies = false,
        )
        awaitUntil { notifyLines().isNotEmpty() }

        assertThat(notifyLines().single()).contains(" r=FALLBACK ")
        val n = posted.single()
        assertThat(n.messagingStyle()).isNull()
        assertThat(n.title()).isEqualTo(SENDER)
    }

    @Test
    fun bothPostsFailingIsFailed() {
        notifyFault = { if (it == NotifyStage.BUILD || it == NotifyStage.PLAIN) error("test fault") }

        val result = post()

        assertThat(result).isEqualTo(NotifyResult.FAILED)
        assertThat(posted).isEmpty()
        assertThat(notifyLines().single()).contains(" r=FAILED ")
    }

    @Test
    fun fallbackOfAnMmsWithoutTextSaysNewMessage() {
        val result = plain().post(
            PlainMessageNotification.Request(THREAD, ADDRESS, SENDER, "", NOTIFICATION_CHANNEL_ID),
            IllegalStateException("test fault"),
        )

        assertThat(result).isEqualTo(NotifyResult.FALLBACK)
        assertThat(posted.single().text()).isEqualTo(app.getString(R.string.new_message))
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
        createChannel(NOTIFICATION_CHANNEL_ID, "Received messages")

        val result = plain { _, _ -> error("channel broke") }
            .post(request("no_such_channel"), IllegalStateException("test fault"))

        assertThat(result).isEqualTo(NotifyResult.FALLBACK)
        assertThat(posted.single().channelId).isEqualTo(NOTIFICATION_CHANNEL_ID)
    }

    @Test
    fun fallbackFailsWhenNoChannelCanBeHad() {
        // R9: Android would drop a post to a channel that does not exist; FAILED keeps the marker for the replay.
        val result = plain { _, _ -> error("channel broke") }
            .post(request("no_such_channel"), IllegalStateException("test fault"))

        assertThat(result).isEqualTo(NotifyResult.FAILED)
        assertThat(posted).isEmpty()
    }

    @Test
    fun fallbackUsesTheNormalChannelWhenNoneIsGiven() {
        val result = plain().post(request(null), IllegalStateException("test fault"))

        assertThat(result).isEqualTo(NotifyResult.FALLBACK)
        assertThat(posted.single().channelId).isEqualTo(NOTIFICATION_CHANNEL_ID)
        assertThat(manager.getNotificationChannel(NOTIFICATION_CHANNEL_ID)).isNotNull()
    }

    @Test
    fun aPostWithoutShortcutLogsTheParentChannel() {
        // S1: the plain post carries no shortcut, so a chat set to "Silent" by long-press does not apply to it.
        createChannel(NOTIFICATION_CHANNEL_ID, "Received messages")
        val conversation = NotificationChannel("conversation", "Chat", NotificationManager.IMPORTANCE_LOW)
        conversation.setConversationId(NOTIFICATION_CHANNEL_ID, THREAD.toString())
        manager.createNotificationChannel(conversation)

        plain().post(request(null), IllegalStateException("test fault"))

        assertThat(notifyLines().single()).contains(" r=FALLBACK i=${NotificationManager.IMPORTANCE_HIGH} ")
    }

    private companion object {
        const val THREAD = 31L
        const val ADDRESS = "+15550001234"
        const val SENDER = "Alex"
        const val BODY = "See you at noon"
        const val CODE = "482913"
        const val CODE_BODY = "Your verification code is $CODE"
        const val WAIT_MS = 5_000L
        const val POLL_MS = 10L
    }
}
