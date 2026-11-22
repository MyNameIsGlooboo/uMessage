package com.glooboo.umessage.spam.data

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.R
import com.glooboo.umessage.spam.BlockedMessage
import com.glooboo.umessage.spam.DecisionReason
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/**
 * J1: one new spam text posts exactly one notification. The group summary is posted once; posting it again for every
 * new text lifted the whole bundle, with every older notice in it.
 *
 * These tests prove only which `notify` and `cancel` calls are made. Whether Android still lifts the bundle when a new
 * notice joins it (it ranks a group by its newest member) only the emulator can show; they are no proof of that.
 */
@RunWith(AndroidJUnit4::class)
class BlockedNotifierSummaryTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val manager get() = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun message(id: Long) = BlockedMessage(
        id = id, address = "+15550001234", body = "Win a prize $id", subscriptionId = 1, isMms = false,
        receivedAt = 1_000L + id, reason = DecisionReason.UNKNOWN_SENDER,
    )

    private fun isSummary(n: StatusBarNotification) = n.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0

    private fun summaries() =
        manager.activeNotifications.filter { it.tag == "spamless_blocked_summary" && isSummary(it) }

    /** Each notification object in the shade by its tag and id. */
    private fun byKey(): Map<Pair<String?, Int>, Notification> =
        manager.activeNotifications.associate { (it.tag to it.id) to it.notification }

    /** A summary posted by the version before, under the held messages' tag. */
    private fun oldSummary() {
        val old = NotificationCompat.Builder(app, "spamless_blocked")
            .setSmallIcon(R.drawable.ic_messenger)
            .setGroup("spamless_blocked_group").setGroupSummary(true).build()
        manager.notify("spamless_blocked", SUMMARY_NUMBER.toInt(), old)
    }

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
        for (chat in 1L..3L) BlockedNotifier.show(app, message(chat), chatId = chat)
        val before = shown()

        BlockedNotifier.show(app, message(4L), chatId = 4L)

        val after = shown()
        assertThat(after.containsAll(before)).isTrue()
        assertThat(after - before).hasSize(1)
    }

    @Test
    fun aSecondTextOfTheSameSpamChatReplacesOnlyItsOwnNotice() {
        for (chat in 1L..3L) BlockedNotifier.show(app, message(chat), chatId = chat)
        val before = byKey()

        BlockedNotifier.show(app, message(5L), chatId = 2L)

        val after = byKey()
        assertThat(after.keys).isEqualTo(before.keys)
        val changed = after.keys.filter { after[it] !== before[it] }
        assertThat(changed).containsExactly("spamless_blocked_chat" to 2L.hashCode())
    }

    @Test
    fun heldAndChatNoticesAreCountedTogether() {
        BlockedNotifier.show(app, message(1L))
        BlockedNotifier.show(app, message(2L), chatId = 7L)

        assertThat(summaries()).hasSize(1)
    }

    @Test
    fun theSummaryShowsNoCountAndNoTime() {
        // Posted once, so both would go stale; the shade counts the notices itself.
        for (id in 1L..3L) BlockedNotifier.show(app, message(id))

        val summary = summaries().single().notification
        assertThat(summary.number).isEqualTo(0)
        assertThat(summary.extras.getBoolean(Notification.EXTRA_SHOW_WHEN, true)).isFalse()
    }

    @Test
    fun theSummaryGoesWithTheLastHeldNoticeTheAppCancels() {
        // J1-4: the app's own cancels do not take the summary along.
        for (id in 1L..2L) BlockedNotifier.show(app, message(id))

        BlockedNotifier.cancel(app, listOf(1L))
        assertThat(summaries()).hasSize(1)
        BlockedNotifier.cancel(app, listOf(2L))

        assertThat(manager.activeNotifications).isEmpty()
    }

    @Test
    fun theSummaryGoesWithTheLastChatNoticeTheAppCancels() {
        for (chat in 1L..2L) BlockedNotifier.show(app, message(chat), chatId = chat)

        BlockedNotifier.cancelChat(app, 1L)
        assertThat(summaries()).hasSize(1)
        BlockedNotifier.cancelChat(app, 2L)

        assertThat(manager.activeNotifications).isEmpty()
    }

    @Test
    fun cancellingHeldMessage23313LeavesAnOldSummaryAndItsGroup() {
        // The version before put its summary under held message 23313's key; cancelling it would clear the group.
        oldSummary()
        for (id in 1L..2L) BlockedNotifier.show(app, message(id))

        BlockedNotifier.cancel(app, listOf(SUMMARY_NUMBER))

        assertThat(manager.activeNotifications.count { isSummary(it) }).isEqualTo(1)
        assertThat(manager.activeNotifications.filter { !isSummary(it) }.map { it.id }).containsExactly(1, 2)
    }

    @Test
    fun heldMessage23313AndTheSummaryDoNotReplaceEachOther() {
        // J1: the summary had tag spamless_blocked and id 0x5B11 (23313), the key of held message 23313's notice.
        for (id in listOf(1L, 2L, SUMMARY_NUMBER)) BlockedNotifier.show(app, message(id))

        val children = manager.activeNotifications.filter { it.tag == "spamless_blocked" && !isSummary(it) }
        assertThat(children.map { it.id }).containsExactly(1, 2, BlockedNotifier.notificationId(SUMMARY_NUMBER))
        assertThat(summaries()).hasSize(1)
    }

    @Test
    fun anOldSummaryLeftByTheUpdateIsNotDoubled() {
        oldSummary()

        for (id in 1L..2L) BlockedNotifier.show(app, message(id))

        assertThat(manager.activeNotifications.count { isSummary(it) }).isEqualTo(1)
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

    private companion object {
        /** The summary's old id, 0x5B11, as a held message id. */
        const val SUMMARY_NUMBER = 23_313L
    }
}
