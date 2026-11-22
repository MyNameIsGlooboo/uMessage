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
 * Step D, the review rounds: the place follows the lists' rule (D-1, plan 12.1), Spam is never forwarded, the expired
 * and replay paths, the history order (R2, B2-14), D-4, D-7, the R1 fact cases, the notice and the clean-ups.
 */
@RunWith(AndroidJUnit4::class)
class ProviderTruthSmsEdgeTest : ProviderTruthSmsFixture() {

    // ---- D-1: the place follows the rule the lists use (plan 12.1: two numbers in one thread, region mismatch) ----

    @Test
    fun aSpamTextInAChatCachedUnderAnotherNumberIsAnnouncedInMessages() {
        // The phone put OTHER's chat and STRANGER's text into one thread; the chat is cached as OTHER, in Messages.
        val thread = store.threadOf(OTHER)
        store.share(STRANGER, thread)
        cache(OTHER, thread)

        receive("Big sale today only")

        assertThat(inbox().single().int("read")).isEqualTo(0)
        assertThat(posted).hasSize(1)
        assertThat(offMainThread { app.conversationsDB.getSpamThread(thread) }).isNull()
        assertThat(messagesList()).contains(thread)
        assertThat(spamList()).doesNotContain(thread)
    }

    @Test
    fun aValidSpamRowIsNeverReplacedByATextOfAnotherNumber() {
        val thread = store.threadOf(OTHER)
        store.share(STRANGER, thread)
        cache(OTHER, thread)
        offMainThread {
            app.conversationsDB.insertSpamThread(SpamThread(thread, OTHER, SpamCause.BLOCKED, spamSince = 5L))
        }

        receive("Big sale today only")

        val row = offMainThread { app.conversationsDB.getSpamThread(thread) }!!
        assertThat(row.sender).isEqualTo(OTHER)
        assertThat(row.cause).isEqualTo(SpamCause.BLOCKED)
        assertThat(row.spamSince).isEqualTo(5L)
        assertThat(row.unread).isEqualTo(1)
        assertThat(inbox().single().int("read")).isEqualTo(1)
        assertThat(spamList()).contains(thread)
        assertThat(posted).isEmpty()
    }

    @Test
    fun aChatCachedUnderTheSameNumberWrittenAnotherWayGoesToSpamUnderThatNumber() {
        val before = Locale.getDefault()
        Locale.setDefault(Locale.US)
        try {
            val thread = store.threadOf(STRANGER)
            cache(NATIONAL, thread)

            receive("Big sale today only")

            assertThat(offMainThread { app.conversationsDB.getSpamThread(thread) }!!.sender).isEqualTo(NATIONAL)
            assertThat(spamList()).contains(thread)
            assertThat(inbox().single().int("read")).isEqualTo(1)
            assertThat(posted).isEmpty()
        } finally {
            Locale.setDefault(before)
        }
    }

    @Test
    fun aRegionMismatchBetweenTheChatAndTheTextFailsOpen() {
        // Read in Germany, the cached national number is not the text's +1 number.
        val before = Locale.getDefault()
        Locale.setDefault(Locale.GERMANY)
        try {
            val thread = store.threadOf(STRANGER)
            cache(NATIONAL, thread)

            receive("Big sale today only")

            assertThat(inbox().single().int("read")).isEqualTo(0)
            assertThat(posted).hasSize(1)
            assertThat(messagesList()).contains(thread)
            assertThat(spamList()).doesNotContain(thread)
        } finally {
            Locale.setDefault(before)
        }
    }

    @Test
    fun theSafetyCheckLeavesTheCachedChatAndItsTextUnread() {
        val thread = store.threadOf(STRANGER)
        cache(STRANGER, thread)
        store.afterInsert = { app.conversationsDB.deleteSpamThread(thread) }

        receive("Big sale today only")

        val id = (inbox().single()["_id"] as String).toLong()
        assertThat(offMainThread { app.messagesDB.getMessage(id, false) }!!.read).isFalse()
        assertThat(offMainThread { app.conversationsDB.getConversationWithThreadId(thread) }!!.read).isFalse()
        assertThat(messagesList()).contains(thread)
        assertThat(posted).hasSize(1)
    }

    // ---- Reviewer: a text judged Spam is never forwarded ----

    @Test
    fun aSpamTextIsNeverForwardedButAMessagesTextIs() {
        forwardTo(TARGET)

        receive("Big sale today only")
        assertThat(forwards()).isEmpty()

        settings { it.copy(filterOn = false) }
        receive("See you at noon", at = now + 60_000L)
        assertThat(forwards()).containsExactly(TARGET)
    }

    @Test
    fun aSpamTextAnnouncedByTheSafetyCheckIsNotForwarded() {
        forwardTo(TARGET)
        store.afterInsert = { app.conversationsDB.deleteSpamThread(store.threadOf(STRANGER)) }

        receive("Big sale today only")

        assertThat(posted).hasSize(1)
        assertThat(forwards()).isEmpty()
    }

    @Test
    fun aSpamTextWithoutAThreadIdFailsOpenAndIsNotForwarded() {
        forwardTo(TARGET)
        store.failThreadIds = true

        receive("Big sale today only")

        assertThat(inbox().single().int("read")).isEqualTo(0)
        assertThat(posted).hasSize(1)
        assertThat(forwards()).isEmpty()
    }

    @Test
    fun aSpamRowThatCannotBeWrittenFailsOpenAndIsNotForwarded() {
        forwardTo(TARGET)
        val sql = MessagesDatabase.getInstance(app).openHelper
        offMainThread {
            sql.writableDatabase.execSQL(
                "CREATE TRIGGER refuse_spam BEFORE INSERT ON spam_threads BEGIN SELECT RAISE(ABORT, 'refused'); END"
            )
        }
        try {
            receive("Big sale today only")

            assertThat(inbox().single().int("read")).isEqualTo(0)
            assertThat(posted).hasSize(1)
            assertThat(forwards()).isEmpty()
        } finally {
            offMainThread { sql.writableDatabase.execSQL("DROP TRIGGER IF EXISTS refuse_spam") }
        }
    }

    // ---- Archive ----

    @Test
    fun aSpamTextLeavesItsChatArchivedAMessagesTextDoesNot() {
        app.config.isArchiveAvailable = true
        app.config.keepConversationsArchived = false
        val spamThread = store.threadOf(STRANGER)
        cache(STRANGER, spamThread, archived = true)
        val codeThread = store.threadOf(OTHER)
        cache(OTHER, codeThread, archived = true)

        receive("Big sale today only")
        receive(CODE, address = OTHER)

        assertThat(offMainThread { app.conversationsDB.getConversationWithThreadId(spamThread) }!!.isArchived).isTrue()
        assertThat(offMainThread { app.conversationsDB.getConversationWithThreadId(codeThread) }!!.isArchived).isFalse()
    }

    // ---- Reviewer: an expired queued text keeps its sender's place ----

    @Test
    fun anExpiredTextOfABlockedSenderIsStoredSilentlyInSpam() {
        runBlocking { repo.addRule(SpamRule(kind = ListKind.BLOCK, type = RuleType.NUMBER, pattern = STRANGER)) }
        val old = now - PendingSmsPolicy.MAX_AGE_MS - 60_000L
        offMainThread { db.spamDao().insertPending(message("Big sale today only", at = old)) }

        offMainThread { PendingSmsQueue.replay(app, now) }

        assertThat(inbox().single().int("read")).isEqualTo(1)
        assertThat(spamRow()!!.cause).isEqualTo(SpamCause.BLOCKED)
        assertThat(posted).isEmpty()
        assertThat(db.spamDao().getPending()).isEmpty()
    }

    // ---- D-2: the replay never re-announces a Messages text ----

    @Test
    fun theReplayLeavesAMessagesTextTheUserHasRead() {
        // A Messages text, read since; its pending row outlived the insert. It was never written silently.
        store.addInbox(STRANGER, "See you at noon", now, store.threadOf(STRANGER), read = 1)
        settings { it.copy(filterOn = false) }
        offMainThread {
            db.spamDao().insertPending(message("See you at noon", createdAt = now - PendingSmsPolicy.GRACE_MS - 1))
        }

        offMainThread { PendingSmsQueue.replay(app, now) }

        assertThat(inbox().single().int("read")).isEqualTo(1)
        assertThat(posted).isEmpty()
        assertThat(db.spamDao().getPending()).isEmpty()
    }

    // ---- R2 and B2-14: the real order of the history check ----

    @Test
    fun beforeTheFirstRunEndsTheSenderIsCheckedFirstAndAnOldReplyKeepsItInMessages() {
        // R2: the first SMS after an update, from a sender with an old sent text.
        firstRun(FirstRunState())
        val thread = store.threadOf(STRANGER)
        cache(STRANGER, thread)
        ArrivalFacts.checkSender = { _, address, _ ->
            checkedSenders += address
            runBlocking { repo.joinGroup(AllowGroup.WROTE, address, MemberSource.HISTORY) }
        }

        receive("Big sale today only")

        assertThat(checkedSenders).containsExactly(STRANGER)
        assertThat(inbox().single().int("read")).isEqualTo(0)
        assertThat(posted).hasSize(1)
        assertThat(spamList()).doesNotContain(thread)
    }

    @Test
    fun aChatNotCachedYetHasItsSenderCheckedBeforeTheVerdict() {
        receive("Big sale today only")

        assertThat(checkedSenders).containsExactly(STRANGER)
    }

    @Test
    fun aCachedChatAfterTheFirstRunIsNotCheckedAgain() {
        cache(STRANGER, store.threadOf(STRANGER))

        receive("Big sale today only")

        assertThat(checkedSenders).isEmpty()
        assertThat(spamRow()).isNotNull()
    }

    // ---- D-4: a number the user wrote to is put back on arrival ----

    @Test
    fun aTextFromANumberTheUserWroteToJoinsTheGroupAndGoesToMessages() {
        cache(STRANGER, store.threadOf(STRANGER))
        wroteAnswer = ContactLookup.FOUND

        receive("Big sale today only")

        val member = repo.groupMembership.members().single()
        assertThat(member.group).isEqualTo(AllowGroup.WROTE)
        assertThat(member.source).isEqualTo(MemberSource.HISTORY)
        assertThat(inbox().single().int("read")).isEqualTo(0)
        assertThat(posted).hasSize(1)
        assertThat(spamRow()).isNull()
    }

    // ---- D-7: people the user wrote to are known for the notification ----

    @Test
    fun aMemberOfPeopleYouWroteToIsKnownACodeSenderIsNot() {
        runBlocking { repo.joinGroup(AllowGroup.WROTE, STRANGER, MemberSource.LIVE) }
        runBlocking { repo.joinGroup(AllowGroup.CODE, OTHER, MemberSource.LIVE) }
        val member = Judgement(SenderVerdict.Messages(DecisionReason.GROUP_MEMBER))

        val wrote = offMainThread { ArrivalFacts.messagesDecision(app, STRANGER, member) }
        val code = offMainThread { ArrivalFacts.messagesDecision(app, OTHER, member) }

        assertThat(wrote!!.reason).isEqualTo(DecisionReason.KNOWN_CONVERSATION)
        assertThat(code!!.reason).isEqualTo(DecisionReason.GROUP_MEMBER)
    }

    // ---- R1 cases with facts ----

    @Test
    fun aFactThatCannotBeWrittenEndsUnreadAndAnnounced() {
        offMainThread {
            db.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER refuse_member BEFORE INSERT ON allow_group_members " +
                    "BEGIN SELECT RAISE(ABORT, 'refused'); END"
            )
        }

        receive(CODE)

        assertThat(inbox().single().int("read")).isEqualTo(0)
        assertThat(posted).hasSize(1)
        assertThat(repo.groupMembership.members()).isEmpty()
        assertThat(spamRow()).isNull()
    }

    @Test
    fun aWordOfTheOldFossifyListBlocksTheSenderSilently() {
        legacyWords = listOf("casino")

        receive("Win big at the casino tonight")

        assertThat(inbox().single().int("read")).isEqualTo(1)
        assertThat(spamRow()!!.cause).isEqualTo(SpamCause.BLOCKED)
        assertThat(db.spamDao().getRules().single().note).isEqualTo(RuleNotes.BLOCKED_FROM_KEYWORD)
        assertThat(posted).isEmpty()
    }

    @Test
    fun aBlockedWordBlocksTheSenderWithNoNoticeAndCountsItsHit() {
        settings { it.copy(notifyOnBlock = true) }
        val wordId = runBlocking {
            repo.addRule(SpamRule(kind = ListKind.BLOCK, type = RuleType.KEYWORD, pattern = "casino"))
        }

        receive("Win big at the casino tonight")

        assertThat(inbox().single().int("read")).isEqualTo(1)
        assertThat(spamRow()!!.cause).isEqualTo(SpamCause.BLOCKED)
        assertThat(posted).isEmpty()
        assertThat(db.spamDao().getRules().single { it.id == wordId }.hitCount).isEqualTo(1)
    }

    // ---- The notice on the lock screen ----

    @Test
    fun theQuietNoticeShowsNoSenderOrTextOnTheLockScreen() {
        settings { it.copy(notifyOnBlock = true) }

        receive("Big sale today only")

        val locked = posted.single().publicVersion!!
        val shown = locked.extras.keySet().joinToString(" ") { locked.extras.get(it).toString() }
        assertThat(locked.extras.getCharSequence(android.app.Notification.EXTRA_TITLE).toString())
            .isEqualTo(app.getString(com.glooboo.umessage.R.string.spam_engine_blocked_public_title))
        assertThat(shown).doesNotContain(STRANGER)
        assertThat(shown).doesNotContain("Big sale")
    }

    // ---- Clean-ups wait for the first run ----

    @Test
    fun theReceiversPurgeWaitsUntilTheFirstSortIsPublished() {
        val purge = app.getSharedPreferences("spamless_spam_settings", Context.MODE_PRIVATE)
        firstRun(FirstRunState())

        offMainThread { SmsReceiver().housekeeping(app) }
        assertThat(purge.getLong("last_purge_at", 0L)).isEqualTo(0L)

        firstRun(FirstRunState(phase = FirstRunPhase.DONE, sortPublished = true))
        offMainThread { SmsReceiver().housekeeping(app) }
        assertThat(purge.getLong("last_purge_at", 0L)).isGreaterThan(0L)
    }

    // ---- Second review ----

    @Test
    fun whenTheStoreRefusesTheUnreadUpdateNothingIsPostedAndTheRowIsKeptForTheReplay() {
        // D-10: the safety check must announce, but the store keeps the row read.
        store.afterInsert = { app.conversationsDB.deleteSpamThread(store.threadOf(STRANGER)) }
        store.refuseUpdates = true

        val thrown = runCatching { receive("Big sale today only") }.exceptionOrNull()

        assertThat(thrown).isInstanceOf(IllegalStateException::class.java)
        assertThat(posted).isEmpty()
        assertThat(NotifyDue.pendingIds(app)).isEmpty()
        assertThat(SilentMarks.has(app, message("Big sale today only"))).isTrue()
        assertThat(inbox().single().int("read")).isEqualTo(1)
    }

    @Test
    fun aNumberTheUserRemovedStaysRemovedWhenItTextsAgain() {
        // D-4 respects "Remove": the HISTORY join is refused, the text stays in Spam.
        cache(STRANGER, store.threadOf(STRANGER))
        val readers = SpamThreads.readers(app)
        RemovedMembers.add(AllowGroup.WROTE, readers.senderKeys(STRANGER) + readers.senderKey(STRANGER))
        wroteAnswer = ContactLookup.FOUND

        receive("Big sale today only")

        assertThat(repo.groupMembership.members()).isEmpty()
        assertThat(inbox().single().int("read")).isEqualTo(1)
        assertThat(posted).isEmpty()
    }

    @Test
    fun anUnreadableSentHistoryFailsOpenToMessages() {
        // E-4: the store could not say whether the user wrote to the number.
        cache(STRANGER, store.threadOf(STRANGER))
        wroteAnswer = ContactLookup.UNKNOWN

        receive("Big sale today only")

        assertThat(inbox().single().int("read")).isEqualTo(0)
        assertThat(posted).hasSize(1)
        assertThat(spamRow()).isNull()
        assertThat(UMessage.log(app).entries().map { it.reason }).contains("HISTORY_CHECK_FAILED")
    }

    @Test
    fun withSpamLockedTheQuietNoticeShowsNeitherSenderNorText() {
        settings { it.copy(notifyOnBlock = true) }
        app.baseConfig.isAppPasswordProtectionOn = true
        LockScope.set(app, LockScope.ChosenParts)

        receive("Big sale today only")

        val extras = posted.single().extras
        val shown = extras.keySet().joinToString(" ") { extras.get(it).toString() }
        assertThat(extras.getCharSequence(android.app.Notification.EXTRA_TITLE).toString())
            .isEqualTo(app.getString(com.glooboo.umessage.R.string.spam_engine_blocked_public_title))
        assertThat(shown).doesNotContain(STRANGER)
        assertThat(shown).doesNotContain("Big sale")
    }

    @Test
    fun anExpiredTextOfAnUnknownSenderIsCheckedFirstAndStaysSilentInSpam() {
        // E-3: the history check runs before the expired text of an unknown sender goes to Spam.
        val old = now - PendingSmsPolicy.MAX_AGE_MS - 60_000L
        offMainThread { db.spamDao().insertPending(message("Big sale today only", at = old)) }

        offMainThread { PendingSmsQueue.replay(app, now) }

        assertThat(checkedSenders).containsExactly(STRANGER)
        assertThat(inbox().single().int("read")).isEqualTo(1)
        assertThat(posted).isEmpty()
    }

    @Test
    fun anExpiredTextOfAnUnknownSenderWhoseHistoryCannotBeCheckedIsAnnounced() {
        historyAnswer = false
        val old = now - PendingSmsPolicy.MAX_AGE_MS - 60_000L
        offMainThread { db.spamDao().insertPending(message("Big sale today only", at = old)) }

        offMainThread { PendingSmsQueue.replay(app, now) }

        assertThat(inbox().single().int("read")).isEqualTo(0)
        assertThat(posted).hasSize(1)
    }

    @Test
    fun anExpiredTextOfAKnownSenderIsAnnouncedInMessages() {
        settings { it.copy(filterOn = false) }
        val old = now - PendingSmsPolicy.MAX_AGE_MS - 60_000L
        offMainThread { db.spamDao().insertPending(message("See you at noon", at = old)) }

        offMainThread { PendingSmsQueue.replay(app, now) }

        assertThat(inbox().single().int("read")).isEqualTo(0)
        assertThat(posted).hasSize(1)
        assertThat(spamRow()).isNull()
    }

    @Test
    fun aSilentRowWithoutAThreadIsReplayedLaterAndKeepsItsMark() {
        // E-2: the thread lookup fails on replay; the mark and the pending row stay for the next try.
        val text = "Big sale today only"
        store.addInbox(STRANGER, text, now, store.threadOf(STRANGER), read = 1)
        SilentMarks.mark(app, message(text))
        offMainThread { db.spamDao().insertPending(message(text, createdAt = now - PendingSmsPolicy.GRACE_MS - 1)) }
        store.failThreadIds = true

        val waiting = offMainThread { PendingSmsQueue.replay(app, now) }

        assertThat(waiting).isGreaterThan(0)
        assertThat(db.spamDao().getPending()).hasSize(1)
        assertThat(SilentMarks.has(app, message(text))).isTrue()
    }

    @Test
    fun anInsertThatFailsLeavesNoMark() {
        // E-2: a text stored nowhere is no silent text; a later Messages copy is never re-announced for it.
        store.failNextInserts = 2

        runCatching { receive("Big sale today only") }

        assertThat(SilentMarks.has(app, message("Big sale today only"))).isFalse()
    }

    @Test
    fun aChatNotCachedYetKeepsItsRowWhenTheSenderIsWrittenAnotherWay() {
        // E-5: the same sender in another written form keeps the row's unread count and spam_since.
        val before = Locale.getDefault()
        Locale.setDefault(Locale.US)
        try {
            val thread = store.threadOf(STRANGER)
            offMainThread {
                app.conversationsDB.insertSpamThread(
                    SpamThread(thread, NATIONAL, SpamCause.UNKNOWN, unread = 3, spamSince = 5L)
                )
            }

            receive("Big sale today only")

            val row = offMainThread { app.conversationsDB.getSpamThread(thread) }!!
            assertThat(row.sender).isEqualTo(NATIONAL)
            assertThat(row.unread).isEqualTo(4)
            assertThat(row.spamSince).isEqualTo(5L)
        } finally {
            Locale.setDefault(before)
        }
    }

    @Test
    fun aChatCachedUnderAnotherNumberLeavesNoStaleRowAndLogsNoError() {
        val thread = store.threadOf(OTHER)
        store.share(STRANGER, thread)
        cache(OTHER, thread)
        offMainThread {
            app.conversationsDB.insertSpamThread(SpamThread(thread, STRANGER, SpamCause.UNKNOWN, spamSince = 5L))
        }

        receive("Big sale today only")

        assertThat(offMainThread { app.conversationsDB.getSpamThread(thread) }).isNull()
        val line = UMessage.log(app).entries().single { it.reason == "CHAT_OTHER_NUMBER" }
        assertThat(line.decision).isEqualTo("ALLOW")
        assertThat(UMessage.log(app).entries().map { it.reason }).doesNotContain("SPAM_ROW_FAILED")
    }
}
