package com.glooboo.umessage.spam.data

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.service.notification.StatusBarNotification
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.helpers.ProviderTruthFlag
import com.glooboo.umessage.spam.BlockedMessage
import com.glooboo.umessage.spam.DecisionReason
import com.glooboo.umessage.testing.ProviderTruthFlagRule
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * J1: one new spam text posts exactly one notification. The group summary is posted once; posting it again for every
 * new text lifted the whole bundle, with every older notice in it.
 */
@RunWith(AndroidJUnit4::class)
class BlockedNotifierSummaryTest {

    @get:Rule
    val flag = ProviderTruthFlagRule(enabled = false)

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val manager get() = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun message(id: Long) = BlockedMessage(
        id = id, address = "+15550001234", body = "Win a prize $id", subscriptionId = 1, isMms = false,
        receivedAt = 1_000L + id, reason = DecisionReason.UNKNOWN_SENDER,
    )

    private fun isSummary(n: StatusBarNotification) = n.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0

    private fun summaries() = manager.activeNotifications.filter { it.tag == "spamless_blocked" && isSummary(it) }

    /** Every notification object in the shade; a post replaces the object, so an unchanged one was not posted. */
    private fun shown(): Set<Notification> = manager.activeNotifications.map { it.notification }.toSet()

    @Test
    fun aNewHeldTextPostsOnlyItsOwnNotice() {
        for (id in 1L..3L) BlockedNotifier.show(app, message(id))
        val before = shown()

        BlockedNotifier.show(app, message(4L))

        val after = shown()
        assertThat(after.containsAll(before)).isTrue()
        assertThat(after - before).hasSize(1)
        assertThat((after - before).single().flags and Notification.FLAG_GROUP_SUMMARY).isEqualTo(0)
    }

    @Test
    fun aNewSpamChatPostsOnlyItsOwnNotice() {
        ProviderTruthFlag.enabled = true
        for (chat in 1L..3L) BlockedNotifier.show(app, message(chat), chatId = chat)
        val before = shown()

        BlockedNotifier.show(app, message(4L), chatId = 4L)

        val after = shown()
        assertThat(after.containsAll(before)).isTrue()
        assertThat(after - before).hasSize(1)
    }

    @Test
    fun theSummaryComesWithTheSecondNotice() {
        BlockedNotifier.show(app, message(1L))
        assertThat(summaries()).isEmpty()

        BlockedNotifier.show(app, message(2L))

        assertThat(summaries()).hasSize(1)
    }

    @Test
    fun aClearedShadeGetsANewSummaryForTheNextBurst() {
        for (id in 1L..2L) BlockedNotifier.show(app, message(id))
        manager.cancelAll()

        for (id in 3L..4L) BlockedNotifier.show(app, message(id))

        assertThat(summaries()).hasSize(1)
    }
}
