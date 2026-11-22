package com.glooboo.umessage.spam.data

import android.app.Application
import android.app.NotificationManager
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.provider.Telephony
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.extensions.config
import com.glooboo.umessage.extensions.conversationsDB
import com.glooboo.umessage.extensions.messagesDB
import com.glooboo.umessage.models.Conversation
import com.glooboo.umessage.spam.AllowGroup
import com.glooboo.umessage.spam.BlockedMessage
import com.glooboo.umessage.spam.DecisionReason
import com.glooboo.umessage.spam.FilterDecision
import com.glooboo.umessage.spam.FirstRun
import com.glooboo.umessage.spam.FirstRunPhase
import com.glooboo.umessage.spam.FirstRunState
import com.glooboo.umessage.spam.FirstRunStore
import com.glooboo.umessage.spam.ForwardRule
import com.glooboo.umessage.spam.MemberSource
import com.glooboo.umessage.spam.ListKind
import com.glooboo.umessage.spam.RuleType
import com.glooboo.umessage.spam.SpamRule
import com.glooboo.umessage.spam.SpamSettings
import com.glooboo.umessage.spam.SpamThread
import com.glooboo.umessage.spam.SpamThreads
import com.glooboo.umessage.spam.UMessage
import com.glooboo.umessage.spam.engine.ContactChecker
import com.glooboo.umessage.spam.engine.ContactLookup
import com.glooboo.umessage.spam.engine.DefaultSpamFilter
import com.glooboo.umessage.spam.engine.Judgement
import com.glooboo.umessage.spam.engine.LegacyBlocklist
import com.glooboo.umessage.spam.engine.NumberNormalizer
import com.glooboo.umessage.spam.engine.SenderVerdict
import com.glooboo.umessage.spam.engine.SpamCause
import com.glooboo.umessage.spam.engine.SpamEngine
import com.glooboo.umessage.databases.MessagesDatabase
import com.glooboo.umessage.receivers.SmsReceiver
import com.glooboo.umessage.ui.lock.LockScope
import org.fossify.commons.extensions.baseConfig
import com.glooboo.umessage.testing.ShadowRecordingSmsManager
import com.glooboo.umessage.testing.joinAppBackgroundThreads
import com.glooboo.umessage.testing.SpamTestWiring
import com.glooboo.umessage.testing.offMainThread
import com.glooboo.umessage.testing.resetSharedMessagesDatabase
import com.glooboo.umessage.ui.spam.RuleNotes
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import java.util.Locale

/**
 * Step D (6.1): an incoming SMS under provider truth. Spam is written to the phone's store read, seen and
 * silent, with its spam_threads row first; Messages texts stay unread and announced; the safety check (R1) never
 * leaves a read and silent text outside Spam. The store is in memory; the filter and repository are the real ones on
 * an in-memory spamless.db. Every number is made up (555 range).
 */
@RunWith(AndroidJUnit4::class)
class ProviderTruthSmsTest : ProviderTruthSmsFixture() {

    @Test
    fun anUnknownSendersTextIsStoredReadSeenAndSilentInSpam() {
        receive("Big sale today only")

        val row = store.rows().single()
        assertThat(row.int("read")).isEqualTo(1)
        assertThat(row.int("seen")).isEqualTo(1)
        assertThat(row.int("thread_id")).isEqualTo(store.threadOf(STRANGER).toInt())
        val spam = spamRow()!!
        assertThat(spam.cause).isEqualTo(SpamCause.UNKNOWN)
        assertThat(spam.unread).isEqualTo(1)
        assertThat(posted).isEmpty()
        assertThat(NotifyDue.pendingIds(app)).isEmpty()
        // Nothing goes to the old store any more; the "blocked" count still grows.
        assertThat(db.spamDao().getAllBlocked()).isEmpty()
        assertThat(blockedEvents()).isEqualTo(1)
    }

    @Test
    fun eachSpamTextRaisesTheChatsOwnUnreadCount() {
        receive("Big sale today only")
        receive("Last chance", at = now + 60_000L)

        assertThat(spamRow()!!.unread).isEqualTo(2)
        assertThat(store.rows().map { it.int("read") }).containsExactly(1, 1)
        assertThat(posted).isEmpty()
    }

    @Test
    fun aBlockedSenderNeverNotifiesNotEvenForACodeOrWithTheOptionOn() {
        settings { it.copy(notifyOnBlock = true) }
        val ruleId = runBlocking {
            repo.addRule(SpamRule(kind = ListKind.BLOCK, type = RuleType.NUMBER, pattern = STRANGER))
        }

        receive("Your verification code is 482915")

        assertThat(store.rows().single().int("read")).isEqualTo(1)
        assertThat(spamRow()!!.cause).isEqualTo(SpamCause.BLOCKED)
        assertThat(posted).isEmpty()
        assertThat(repo.groupMembership.members()).isEmpty()
        assertThat(db.spamDao().getRules().single { it.id == ruleId }.hitCount).isEqualTo(1)
    }

    @Test
    fun withTheOptionOnAnUnknownSenderGetsOneQuietNoticePerChat() {
        settings { it.copy(notifyOnBlock = true) }

        receive("Big sale today only")
        receive("Last chance", at = now + 60_000L)

        val notice = posted.single()
        assertThat(notice.channelId).isEqualTo("spamless_blocked")
        val shown = manager.activeNotifications.single()
        assertThat(shown.tag).isEqualTo("spamless_blocked_chat")
        assertThat(shown.id).isEqualTo(store.threadOf(STRANGER).hashCode())
        // Still read and silent in the store.
        assertThat(store.rows().map { it.int("read") }).containsExactly(1, 1)
        assertThat(NotifyDue.pendingIds(app)).isEmpty()
    }

    @Test
    fun aWeakCodeFromAnUnknownSenderStaysSilentInSpam() {
        // Owner decision 20 Nov (B): a code too weak to allow its sender stays in Spam, silent.
        receive("Use 664422 now")

        assertThat(store.rows().single().int("read")).isEqualTo(1)
        assertThat(spamRow()).isNotNull()
        assertThat(posted).isEmpty()
    }

    @Test
    fun aVerificationCodeAllowsTheSenderAndIsAnnouncedInMessages() {
        receive("Your verification code is 482915")

        assertThat(store.rows().single().int("read")).isEqualTo(0)
        assertThat(spamRow()).isNull()
        assertThat(posted).hasSize(1)
        assertThat(repo.groupMembership.members().map { it.group }).containsExactly(AllowGroup.CODE)
        assertThat(NotifyDue.pendingIds(app)).isEmpty()
    }

    @Test
    fun withTheFilterOffAnUnknownSendersTextIsUnreadInMessages() {
        settings { it.copy(filterOn = false) }

        receive("Big sale today only")

        assertThat(store.rows().single().int("read")).isEqualTo(0)
        assertThat(spamRow()).isNull()
        assertThat(posted).hasSize(1)
    }

    @Test
    fun aTextWithoutASenderIsShownInMessages() {
        receive("Network notice", address = "Unknown")

        assertThat(store.rows().single().int("read")).isEqualTo(0)
        assertThat(spamRow("Unknown")).isNull()
    }

    @Test
    fun aFailedHistoryCheckFailsOpenToMessages() {
        historyAnswer = false
        firstRun(FirstRunState())

        receive("Big sale today only")

        assertThat(store.rows().single().int("read")).isEqualTo(0)
        assertThat(spamRow()).isNull()
        assertThat(posted).hasSize(1)
    }

    @Test
    fun aBrokenFilterFailsOpenToMessages() {
        UMessage.filterFactory = { error("filter broke") }

        receive("Big sale today only")

        assertThat(store.rows().single().int("read")).isEqualTo(0)
        assertThat(posted).hasSize(1)
    }

    @Test
    fun theSafetyCheckAnnouncesASilentTextWhoseChatLeftSpam() {
        // R1: something (a sort, a rebuilt store) takes the chat out of Spam between the row and the check.
        store.afterInsert = { app.conversationsDB.deleteSpamThread(store.threadOf(STRANGER)) }

        receive("Big sale today only")

        val row = store.rows().single()
        assertThat(row.int("read")).isEqualTo(0)
        assertThat(row.int("seen")).isEqualTo(0)
        assertThat(posted).hasSize(1)
        assertThat(NotifyDue.pendingIds(app)).isEmpty()
        // D-8: a text that ended in Messages is not counted as blocked.
        assertThat(blockedEvents()).isEqualTo(0)
    }

    @Test
    fun theSameSpamTextWithinTenSecondsIsStoredOnce() {
        receive("Big sale today only")
        receive("Big sale today only", at = now + 5_000L)

        assertThat(store.rows()).hasSize(1)
        assertThat(spamRow()!!.unread).isEqualTo(1)
    }

    @Test
    fun theSameTextLaterIsStoredAgain() {
        receive("Big sale today only")
        receive("Big sale today only", at = now + 20_000L)

        assertThat(store.rows()).hasSize(2)
    }

    @Test
    fun aDuplicateInMessagesIsNeverDropped() {
        receive("Your verification code is 482915")
        receive("Your verification code is 482915", at = now + 5_000L)

        assertThat(store.rows()).hasSize(2)
    }

    @Test
    fun theReplayAnnouncesAStoredSilentRowWhoseChatIsNotInSpam() {
        // The process died after the silent insert (its mark is on disk); meanwhile the chat left Spam.
        val thread = store.threadOf(STRANGER)
        store.addInbox(STRANGER, "Big sale today only", now, thread, read = 1)
        SilentMarks.mark(app, message("Big sale today only"))
        offMainThread {
            db.spamDao().insertPending(message("Big sale today only", createdAt = now - PendingSmsPolicy.GRACE_MS - 1))
        }
        settings { it.copy(filterOn = false) }

        offMainThread { PendingSmsQueue.replay(app, now) }

        assertThat(store.rows().single().int("read")).isEqualTo(0)
        assertThat(posted).hasSize(1)
        assertThat(db.spamDao().getPending()).isEmpty()
    }

    @Test
    fun theReplayLeavesAStoredSilentRowThatIsInSpam() {
        val thread = store.threadOf(STRANGER)
        offMainThread {
            app.conversationsDB.insertSpamThread(SpamThread(thread, STRANGER, SpamCause.UNKNOWN, spamSince = 1L))
        }
        store.addInbox(STRANGER, "Big sale today only", now, thread, read = 1)
        SilentMarks.mark(app, message("Big sale today only"))
        offMainThread {
            db.spamDao().insertPending(message("Big sale today only", createdAt = now - PendingSmsPolicy.GRACE_MS - 1))
        }

        offMainThread { PendingSmsQueue.replay(app, now) }

        assertThat(store.rows().single().int("read")).isEqualTo(1)
        assertThat(posted).isEmpty()
        // The kill came before the count: the replay raises it once, and never again.
        assertThat(spamRow()!!.unread).isEqualTo(1)
        assertThat(SilentMarks.has(app, message("Big sale today only"))).isFalse()
    }

    @Test
    fun aSpamTextTheStoreRefusesIsKeptInTheQueueAndStoredOnceLater() {
        // M-T5: both inserts fail (the full one and the bare one): the caller keeps the pending row.
        val row = message("Big sale today only", createdAt = now - PendingSmsPolicy.GRACE_MS - 1)
        offMainThread { db.spamDao().insertPending(row) }
        store.failNextInserts = 2

        assertThat(offMainThread { PendingSmsQueue.replay(app, now) }).isGreaterThan(0)
        assertThat(store.rows()).isEmpty()
        assertThat(db.spamDao().getPending()).hasSize(1)

        offMainThread { PendingSmsQueue.replay(app, now) }

        val stored = store.rows().single()
        assertThat(stored.int("read")).isEqualTo(1)
        assertThat(stored.int("seen")).isEqualTo(1)
        assertThat(spamRow()).isNotNull()
        assertThat(db.spamDao().getPending()).isEmpty()
        assertThat(posted).isEmpty()
    }

    @Test
    fun aSpamTextStoredByTheBareInsertIsStillReadAndSilentInSpam() {
        // M-T5: the full insert fails, the bare one takes it.
        store.failNextInserts = 1

        receive("Big sale today only")

        val stored = store.rows().single()
        assertThat(stored.int("read")).isEqualTo(1)
        assertThat(stored.int("seen")).isEqualTo(1)
        assertThat(spamRow()).isNotNull()
        assertThat(posted).isEmpty()
    }

    @Test
    fun aMessagesTextStoredByTheBareInsertIsAnnouncedByTheReplay() {
        // M-T5 with the notification hotfix: the bare insert leaves a "notify due" marker; the replay posts it.
        store.failNextInserts = 1

        receive("Your verification code is 482915")

        assertThat(store.rows().single().int("read")).isEqualTo(0)
        assertThat(NotifyDue.pendingIds(app)).hasSize(1)
        offMainThread { PendingSmsQueue.replay(app) }
        assertThat(posted).hasSize(1)
        assertThat(NotifyDue.pendingIds(app)).isEmpty()
    }
}
