package com.glooboo.umessage.helpers

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.R
import com.glooboo.umessage.spam.DecisionReason
import com.glooboo.umessage.spam.data.SpamSettingsStore
import com.glooboo.umessage.testing.offMainThread
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/** I06 (Block), I67 (Open link) and I03 (silent channel, no notification) on the message notification. */
@RunWith(AndroidJUnit4::class)
class NotificationActionsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun post(notify: NotifyContext, body: String = "Hello", address: String = "+15550100"): Notification? {
        offMainThread {
            NotificationHelper(context).showMessageNotification(
                messageId = 1L, isMms = false, address = address, body = body, threadId = 9L,
                bitmap = null, sender = address, notify = notify,
            )
        }
        return shadowOf(manager).allNotifications.singleOrNull()
    }

    private fun Notification.titles() = actions.map { it.title.toString() }

    private val block get() = context.getString(R.string.notif_block_action)
    private val openLink get() = context.getString(R.string.notif_open_link_action)

    private fun enableOpenLink() = SpamSettingsStore(context).update { it.copy(openLinkAction = true) }

    @Test
    fun blockIsOfferedForAnUnknownSingleSender() {
        val n = post(NotifyContext(reason = DecisionReason.MODE_OFF))!!

        assertThat(n.titles()).containsExactly(
            context.getString(R.string.reply), block, context.getString(R.string.mark_as_read),
        ).inOrder()
        assertThat(n.actions.first { it.title.toString() == block }.isAuthenticationRequired).isTrue()
    }

    @Test
    fun blockIsNotOfferedForContactsGroupsOrCodes() {
        assertThat(post(NotifyContext(reason = DecisionReason.CONTACT))!!.titles()).doesNotContain(block)
        manager.cancelAll()
        assertThat(post(NotifyContext(reason = DecisionReason.MODE_OFF, isGroup = true))!!.titles())
            .doesNotContain(block)
        manager.cancelAll()
        assertThat(post(NotifyContext(reason = DecisionReason.MODE_OFF), body = "Your code is 482913")!!.titles())
            .doesNotContain(block)
    }

    @Test
    fun openLinkIsOffByDefault() {
        val n = post(NotifyContext(reason = DecisionReason.CONTACT), body = "Parcel: https://example.com/track/1")!!
        assertThat(n.titles()).doesNotContain(openLink)
    }

    @Test
    fun openLinkIsOfferedForKnownSendersWithALink() {
        enableOpenLink()
        val n = post(NotifyContext(reason = DecisionReason.ALLOW_RULE), body = "Parcel: https://example.com/track/1.")!!

        assertThat(n.titles()).containsExactly(
            context.getString(R.string.reply), openLink, context.getString(R.string.mark_as_read),
        ).inOrder()
    }

    @Test
    fun openLinkIsNotOfferedWithoutALinkOrForUnknownSenders() {
        enableOpenLink()
        assertThat(post(NotifyContext(reason = DecisionReason.CONTACT))!!.titles()).doesNotContain(openLink)
        manager.cancelAll()
        val unknown = post(NotifyContext(reason = DecisionReason.MODE_OFF), body = "See https://example.com")!!
        assertThat(unknown.titles()).doesNotContain(openLink)
    }

    @Test
    fun firstWebLinkDropsTheClosingPunctuation() {
        assertThat(firstWebLink("Track it: https://example.com/a?b=1, thanks")).isEqualTo("https://example.com/a?b=1")
        assertThat(firstWebLink("(http://example.com/x).")).isEqualTo("http://example.com/x")
        assertThat(firstWebLink("no link here, example.com")).isNull()
    }

    @Test
    fun silentUsesTheQuietChannelAndNoneStaysAway() {
        val silent = post(NotifyContext(NotifyMode.SILENT, DecisionReason.MODE_OFF))!!
        assertThat(silent.channelId).isEqualTo("unknown_senders_silent")
        assertThat(manager.getNotificationChannel(silent.channelId).importance)
            .isEqualTo(NotificationManager.IMPORTANCE_LOW)

        manager.cancelAll()
        assertThat(post(NotifyContext(NotifyMode.NONE, DecisionReason.MODE_OFF))).isNull()
    }

    @Test
    fun aCodeKeepsItsChannelEvenWhenSilent() {
        val n = post(NotifyContext(NotifyMode.SILENT, DecisionReason.MODE_OFF), body = "Your code is 482913")!!
        assertThat(n.channelId).isEqualTo(CODE_CHANNEL_ID)
    }
}
