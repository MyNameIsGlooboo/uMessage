package com.glooboo.umessage.spam

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.databases.MessagesDatabase
import com.glooboo.umessage.interfaces.ConversationsDao
import com.glooboo.umessage.spam.SortingWorld.Companion.ALLOWED
import com.glooboo.umessage.spam.SortingWorld.Companion.BLOCKED
import com.glooboo.umessage.spam.SortingWorld.Companion.CONTACT
import com.glooboo.umessage.spam.SortingWorld.Companion.MEMBER
import com.glooboo.umessage.spam.SortingWorld.Companion.OTHER
import com.glooboo.umessage.spam.SortingWorld.Companion.SENDER
import com.glooboo.umessage.spam.SortingWorld.Companion.chat
import com.glooboo.umessage.spam.engine.SenderVerdict
import com.glooboo.umessage.spam.engine.SpamCause
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Step B, ChatSorter (3.2: S9, S11, R12, R4): the Spam table always equals the sender verdict of every one-to-one
 * chat, it is changed by difference only, and a failed read keeps the old table. All numbers are made up.
 */
@RunWith(AndroidJUnit4::class)
class ChatSorterTest {

    private lateinit var db: MessagesDatabase
    private lateinit var dao: ConversationsDao
    private val world = SortingWorld()
    private var clock = 1_000L

    private val markedUnread = mutableListOf<Long>()

    /** Runs inside [ChatSorter.recompute] after the chats were read and before the transaction (a racing text). */
    private var duringSort: () -> Unit = {}

    private val sorter by lazy {
        ChatSorter(
            db,
            snapshot = {
                duringSort()
                world.judge.snapshot()
            },
            contactsReadable = { world.contactsReadable },
            markUnread = { markedUnread += it },
        ) { clock }
    }

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, MessagesDatabase::class.java).allowMainThreadQueries().build()
        dao = db.ConversationsDao()
    }

    @After
    fun tearDown() = db.close()

    private fun spamRows() = dao.getSpamThreads().associateBy { it.threadId }

    /** The sorted place of every cached chat equals the verdict of its sender, which is what a new text gets. */
    private fun assertTableEqualsVerdicts() {
        val snap = world.judge.snapshot()
        val inSpam = dao.getSpamList().map { it.threadId }.toSet()
        dao.getAllRows().forEach { chat ->
            val shouldBeSpam = !chat.isGroupConversation && !chat.isScheduled &&
                snap.verdict(chat.phoneNumber) is SenderVerdict.Spam
            assertThat(chat.threadId in inSpam).isEqualTo(shouldBeSpam)
        }
    }

    private fun seedEveryKind() {
        world.block(BLOCKED)
        world.allow(ALLOWED)
        world.contacts += CONTACT
        world.member(AllowGroup.CODE, MEMBER)
        dao.insertOrUpdate(chat(1, SENDER))
        dao.insertOrUpdate(chat(2, BLOCKED))
        dao.insertOrUpdate(chat(3, ALLOWED))
        dao.insertOrUpdate(chat(4, CONTACT))
        dao.insertOrUpdate(chat(5, MEMBER))
        dao.insertOrUpdate(chat(6, BLOCKED, group = true))
        dao.insertOrUpdate(chat(7, OTHER, scheduled = true))
        dao.insertOrUpdate(chat(8, "Unknown"))
    }

    @Test
    fun theTableEqualsTheVerdictOfEveryChat() {
        seedEveryKind()

        val result = sorter.recompute()

        assertThat(result).isInstanceOf(SortResult.Applied::class.java)
        val rows = spamRows()
        assertThat(rows.keys).containsExactly(1L, 2L)
        assertThat(rows.getValue(1).cause).isEqualTo(SpamCause.UNKNOWN)
        assertThat(rows.getValue(2).cause).isEqualTo(SpamCause.BLOCKED)
        assertThat(rows.getValue(1).sender).isEqualTo(SENDER)
        assertThat(rows.values.map { it.unread }).containsExactly(0, 0)
        assertThat(rows.values.map { it.spamSince }.toSet()).containsExactly(1_000L)
        assertThat((result as SortResult.Applied).added).isEqualTo(2)
        assertTableEqualsVerdicts()
    }

    @Test
    fun theTableFollowsEveryChangeOfTheVerdict() {
        seedEveryKind()
        sorter.recompute()

        world.rules.removeAll { it.pattern == BLOCKED }
        world.block(SENDER)
        world.contacts += BLOCKED
        val result = sorter.recompute() as SortResult.Applied

        assertThat(spamRows().keys).containsExactly(1L)
        assertThat(spamRows().getValue(1).cause).isEqualTo(SpamCause.BLOCKED)
        assertThat(result.removed).isEqualTo(1)
        assertThat(result.changed).isEqualTo(1)
        assertTableEqualsVerdicts()
    }

    @Test
    fun groupChatsStayInMessagesEvenWhenAMemberIsBlocked() {
        world.block(BLOCKED)
        dao.insertOrUpdate(chat(6, BLOCKED, group = true))

        sorter.recompute()

        assertThat(spamRows()).isEmpty()
        assertThat(dao.getMessagesList().map { it.threadId }).containsExactly(6L)
    }

    @Test
    fun withTheFilterOffOnlyBlockedSendersAreInSpam() {
        seedEveryKind()
        world.settings = world.settings.copy(filterOn = false)

        sorter.recompute()

        assertThat(spamRows().keys).containsExactly(2L)
    }

    @Test
    fun aGroupSwitchedOffSendsItsMembersToSpam() {
        seedEveryKind()
        world.settings = world.settings.copy(codeGroupOn = false)

        sorter.recompute()

        assertThat(spamRows().keys).containsExactly(1L, 2L, 5L)
    }

    @Test
    fun aRebuildNeverWritesTheUnreadCountOrMovesSpamSinceBack() {
        // R12: a text arrived (unread + 1) and the chat stays in Spam; a later cause change keeps both.
        dao.insertOrUpdate(chat(1, SENDER))
        sorter.recompute()
        dao.raiseSpamUnread(1)
        dao.raiseSpamUnread(1)
        clock = 9_000L

        sorter.recompute()
        world.block(SENDER)
        sorter.recompute()

        val row = spamRows().getValue(1)
        assertThat(row.unread).isEqualTo(2)
        assertThat(row.spamSince).isEqualTo(1_000L)
        assertThat(row.cause).isEqualTo(SpamCause.BLOCKED)
    }

    @Test
    fun aRowWrittenForANewTextSurvivesARebuild() {
        // R12 race: the incoming path writes the row (unread 1) while a rebuild of an older list runs.
        dao.insertOrUpdate(chat(1, SENDER))
        dao.insertSpamThread(SpamThread(1, SENDER, SpamCause.UNKNOWN, unread = 0, spamSince = 500L))
        dao.raiseSpamUnread(1)

        sorter.recompute()

        assertThat(spamRows().getValue(1).unread).isEqualTo(1)
        assertThat(spamRows().getValue(1).spamSince).isEqualTo(500L)
    }

    @Test
    fun aRowForAThreadThatIsNotCachedIsLeftAlone() {
        // The incoming path writes the row before the chat row exists (S9 makes it count once the chat is cached).
        dao.insertSpamThread(SpamThread(42, SENDER, SpamCause.UNKNOWN, unread = 1, spamSince = 500L))
        dao.insertOrUpdate(chat(1, OTHER))

        sorter.recompute()

        assertThat(spamRows().keys).containsExactly(1L, 42L)
        assertThat(dao.getSpamList().map { it.threadId }).containsExactly(1L)
    }

    @Test
    fun aFailedReadOfTheRulesKeepsTheOldTable() {
        seedEveryKind()
        sorter.recompute()
        val before = spamRows()

        world.rulesFail = true
        world.rules.clear()
        val result = sorter.recompute()

        assertThat(result).isInstanceOf(SortResult.Failed::class.java)
        assertThat(spamRows()).isEqualTo(before)
    }

    @Test
    fun aContactLookupThatDidNotAnswerKeepsTheOldTable() {
        // S11: with the permission granted, "contacts unavailable" means a failed read; nothing may move.
        seedEveryKind()
        sorter.recompute()
        val before = spamRows()

        world.lookupFails = true
        val result = sorter.recompute()

        assertThat(result).isEqualTo(SortResult.Failed("contacts"))
        assertThat(spamRows()).isEqualTo(before)
    }

    @Test
    fun withoutTheContactsPermissionNumbersFailOpenToMessages() {
        seedEveryKind()
        sorter.recompute()

        world.contactsReadable = false
        sorter.recompute()

        // Check 5: numbers go to Messages; the blocked number stays in Spam (check 3 comes first).
        assertThat(spamRows().keys).containsExactly(2L)
    }

    @Test
    fun aRowOfAnotherPersonIsIgnoredAndThenReplaced() {
        // S9: the store was rebuilt and gave thread 1 to someone else.
        dao.insertSpamThread(SpamThread(1, OTHER, SpamCause.BLOCKED, unread = 3, spamSince = 500L))
        dao.insertOrUpdate(chat(1, SENDER))
        assertThat(dao.getSpamList()).isEmpty()
        assertThat(dao.getMessagesList().map { it.threadId }).containsExactly(1L)

        sorter.recompute()

        val row = spamRows().getValue(1)
        assertThat(row.sender).isEqualTo(SENDER)
        assertThat(row.cause).isEqualTo(SpamCause.UNKNOWN)
        assertThat(row.unread).isEqualTo(0)
        assertThat(row.spamSince).isEqualTo(1_000L)
    }

    @Test
    fun theSameSenderWrittenAnotherWayKeepsItsRow() {
        dao.insertSpamThread(SpamThread(1, "(555) 000-1234", SpamCause.UNKNOWN, unread = 2, spamSince = 500L))
        dao.insertOrUpdate(chat(1, SENDER))

        sorter.recompute()

        val row = spamRows().getValue(1)
        assertThat(row.sender).isEqualTo(SENDER)
        assertThat(row.unread).isEqualTo(2)
        assertThat(row.spamSince).isEqualTo(500L)
        assertThat(dao.getSpamList().map { it.threadId }).containsExactly(1L)
    }

    @Test
    fun onlyTheAskedThreadsAreSorted() {
        dao.insertOrUpdate(chat(1, SENDER))
        dao.insertOrUpdate(chat(2, OTHER))

        sorter.recompute(setOf(2L))

        assertThat(spamRows().keys).containsExactly(2L)
    }

    @Test
    fun oneSnapshotIsReadPerRebuild() {
        repeat(20) { dao.insertOrUpdate(chat(it + 1L, "+1555000${2000 + it}")) }
        world.snapshots = 0

        sorter.recompute()

        assertThat(world.snapshots).isEqualTo(1)
    }

    @Test
    fun aRebuildWithNothingToChangeChangesNothing() {
        seedEveryKind()
        sorter.recompute()

        val again = sorter.recompute() as SortResult.Applied

        assertThat(again.anyChange).isFalse()
        assertThat(again.touched).isEmpty()
    }

    @Test
    fun fiveThousandChatsAreSortedWithinTheTimeBudget() {
        // S22, 12.1: a soft bound for a big phone; the real figure is measured on the emulator in step J. B-2: a real
        // Android block list and an address book with many numbers, read once each.
        repeat(50) { world.block("+1555100${1000 + it}") }
        repeat(300) { world.androidBlocked += "+1555400${1000 + it}" }
        repeat(1_000) { world.contacts += "+1555200${1000 + it}" }
        db.runInTransaction {
            repeat(CHATS) { dao.insertOrUpdate(chat(it + 1L, "+1555${(3_000_000 + it).toString().padStart(7, '0')}")) }
            repeat(100) { dao.insertOrUpdate(chat(CHATS + 1L + it, "+1555400${1000 + it}")) }
            repeat(100) { dao.insertOrUpdate(chat(CHATS + 201L + it, "+1555200${1000 + it}")) }
        }
        world.blockListReads = 0

        val snapshotsBefore = world.snapshots
        val started = System.nanoTime()
        val result = sorter.recompute() as SortResult.Applied
        val tookMs = (System.nanoTime() - started) / 1_000_000

        // The work is what is asserted: one snapshot (rules read once) and one block-list read for 5,200 chats. The
        // time bound only catches a lost index; it is wide so a busy build machine cannot fail it.
        assertThat(world.snapshots - snapshotsBefore).isEqualTo(1)

        assertThat(result.added).isEqualTo(CHATS + 100)
        assertThat(dao.getSpamThreads().count { it.cause == SpamCause.BLOCKED }).isEqualTo(100)
        assertThat(world.blockListReads).isEqualTo(1)
        assertThat(tookMs).isLessThan(BUDGET_MS)
    }

    @Test
    fun androidsBlockListIsReadOncePerSnapshotAndBlocks() {
        world.androidBlocked += SENDER
        dao.insertOrUpdate(chat(1, SENDER))
        dao.insertOrUpdate(chat(2, OTHER))
        world.blockListReads = 0

        sorter.recompute()

        assertThat(world.blockListReads).isEqualTo(1)
        assertThat(spamRows().getValue(1).cause).isEqualTo(SpamCause.BLOCKED)
        assertThat(spamRows().getValue(2).cause).isEqualTo(SpamCause.UNKNOWN)
    }

    @Test
    fun aChatThatLeavesSpamWithUnreadTextsIsMarkedUnread() {
        // B-14: as "Not spam" does (3.4), the user must not lose the texts they have not looked at.
        dao.insertOrUpdate(chat(1, SENDER))
        dao.insertOrUpdate(chat(2, OTHER))
        sorter.recompute()
        dao.raiseSpamUnread(1)

        world.contacts += SENDER
        world.contacts += OTHER
        val result = sorter.recompute() as SortResult.Applied

        assertThat(result.removed).isEqualTo(2)
        assertThat(result.leftWithUnread).containsExactly(1L)
        assertThat(markedUnread).containsExactly(1L)
    }

    @Test
    fun newRowsAreCachedInTheSortsTransactionAndACachedRowIsKept() {
        // B-20: a row the list cached meanwhile wins; the new one is cached and sorted at once.
        dao.insertOrUpdate(chat(1, SENDER).copy(title = "Kept title"))

        val rows = listOf(chat(1, SENDER), chat(2, OTHER))
        val result = sorter.recompute(setOf(1L), newRows = rows) as SortResult.Applied

        assertThat(result.added).isEqualTo(2)
        assertThat(dao.getConversationWithThreadId(1)?.title).isEqualTo("Kept title")
        assertThat(dao.getConversationWithThreadId(2)).isNotNull()
        assertThat(dao.getSpamList().map { it.threadId }).containsExactly(1L, 2L)
        assertThat(dao.getMessagesList()).isEmpty()
    }

    @Test
    fun aFailedSortCachesNoNewRow() {
        world.rulesFail = true

        val result = sorter.recompute(newRows = listOf(chat(2, OTHER)))

        assertThat(result).isInstanceOf(SortResult.Failed::class.java)
        assertThat(dao.getConversationWithThreadId(2)).isNull()
    }

    @Test
    fun aSpamTextThatArrivesDuringTheSortKeepsItsCount() {
        // R12, B-25: the incoming path writes while a rebuild is under way (after its read, before its write).
        dao.insertOrUpdate(chat(1, SENDER))
        duringSort = {
            dao.noteSpamArrival(1, SENDER, SpamCause.UNKNOWN, at = 700L) { a, b -> a == b }
            dao.noteSpamArrival(9, OTHER, SpamCause.UNKNOWN, at = 700L) { a, b -> a == b }
            duringSort = {}
        }

        sorter.recompute()

        assertThat(spamRows().getValue(1).unread).isEqualTo(1)
        assertThat(spamRows().getValue(1).spamSince).isEqualTo(700L)
        assertThat(spamRows().getValue(9).unread).isEqualTo(1)
    }

    private companion object {
        const val CHATS = 5_000
        const val BUDGET_MS = 60_000L
    }
}
