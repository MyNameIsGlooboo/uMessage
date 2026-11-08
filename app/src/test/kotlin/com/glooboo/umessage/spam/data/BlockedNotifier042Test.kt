package com.glooboo.umessage.spam.data

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.service.notification.StatusBarNotification
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.spam.BlockedMessage
import com.glooboo.umessage.spam.DecisionReason
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/** I5: blocked-message notifications are grouped, and go when the message is restored, deleted or its thread opened. */
@RunWith(AndroidJUnit4::class)
class BlockedNotifier042Test {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val manager get() = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun message(id: Long) = BlockedMessage(
        id = id, address = "+1555010$id", body = "Win a prize $id", subscriptionId = 1, isMms = false,
        receivedAt = 1_000L + id, reason = DecisionReason.UNKNOWN_SENDER,
    )

    private fun isSummary(n: StatusBarNotification) = n.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0

    private fun children() = manager.activeNotifications.filter { it.tag == "spamless_blocked" && !isSummary(it) }

    private fun summaries() = manager.activeNotifications.filter { it.tag == "spamless_blocked" && isSummary(it) }

    @Test fun aBurstIsOneGroupWithOneSummary() {
        for (id in 1L..5L) BlockedNotifier.show(app, message(id))

        assertThat(children()).hasSize(5)
        assertThat(summaries()).hasSize(1)
        assertThat(children().map { it.notification.group }.toSet()).containsExactly("spamless_blocked_group")
    }

    @Test fun aSingleNoticeNeedsNoSummary() {
        BlockedNotifier.show(app, message(1))

        assertThat(children()).hasSize(1)
        assertThat(summaries()).isEmpty()
    }

    @Test fun theSummaryNamesNoSenderAndNoText() {
        for (id in 1L..3L) BlockedNotifier.show(app, message(id))

        val text = summaries().single().notification.extras.toString()
        assertThat(text).doesNotContain("Win a prize")
        assertThat(text).doesNotContain("+1555010")
    }

    @Test fun cancelRemovesOnlyTheNamedMessages() {
        for (id in 1L..3L) BlockedNotifier.show(app, message(id))

        BlockedNotifier.cancel(app, listOf(2L))

        assertThat(children().map { it.id }).containsExactly(
            BlockedNotifier.notificationId(1L),
            BlockedNotifier.notificationId(3L),
        )
    }

    @Test fun openingTheBlockedTabClearsEverything() {
        for (id in 1L..3L) BlockedNotifier.show(app, message(id))

        BlockedNotifier.cancelAll(app)

        assertThat(manager.activeNotifications.filter { it.tag == "spamless_blocked" }).isEmpty()
    }
}
