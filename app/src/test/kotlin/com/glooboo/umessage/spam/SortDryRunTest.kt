package com.glooboo.umessage.spam

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.databases.MessagesDatabase
import com.glooboo.umessage.interfaces.ConversationsDao
import com.glooboo.umessage.spam.SortingWorld.Companion.BLOCKED
import com.glooboo.umessage.spam.SortingWorld.Companion.CONTACT
import com.glooboo.umessage.spam.SortingWorld.Companion.MEMBER
import com.glooboo.umessage.spam.SortingWorld.Companion.OTHER
import com.glooboo.umessage.spam.SortingWorld.Companion.SENDER
import com.glooboo.umessage.spam.SortingWorld.Companion.chat
import com.glooboo.umessage.spam.engine.ContactLookup
import com.glooboo.umessage.ui.spam.SortChange
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * G-B3 (answer 18, U11): the switch confirm's count is a dry run of the change: the chats the sort would move with the
 * changed settings (and, for the switch going on, after the history check's joins), and nothing is written. All
 * numbers are made up.
 */
@RunWith(AndroidJUnit4::class)
class SortDryRunTest {

    private lateinit var db: MessagesDatabase
    private lateinit var dao: ConversationsDao
    private val world = SortingWorld()
    private var published = true
    private val removedMarks = mutableSetOf<Pair<AllowGroup, String>>()

    /** The phone's history: the numbers written to, and the received texts per thread. */
    private val wrote = mutableSetOf<String>()
    private val received = mutableMapOf<Long, String>()
    private var joins = 0

    private val store = object : HistoryStore {
        override fun chats(): List<SortableChat> = dao.getSortableChats()
        override fun cachedChats(): List<SortableChat> = dao.getSortableChats()
        override fun wroteTo(address: String): ContactLookup =
            if (wrote.any { world.readers.same(it, address) }) ContactLookup.FOUND else ContactLookup.NOT_FOUND
        override fun threadsWithReceived(threadIds: Collection<Long>, matches: (String) -> Boolean): Set<Long> =
            threadIds.filterTo(HashSet()) { id -> received[id]?.let(matches) == true }
    }

    private val sorter by lazy {
        ChatSorter(db, snapshot = { world.judge.snapshot() }, contactsReadable = { world.contactsReadable })
    }

    private val dryRun by lazy {
        SortDryRun(
            snapshot = { world.judge.snapshot() },
            sorter = { sorter },
            history = { snap ->
                HistoryCheck(store, snap, join = { _, senders -> senders.size.also { joins++ } }) { "code" in it }
            },
            published = { published },
            removed = { group, keys -> keys.any { (group to it) in removedMarks } },
            dispatcher = Dispatchers.Unconfined,
        )
    }

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, MessagesDatabase::class.java).allowMainThreadQueries().build()
        dao = db.ConversationsDao()
        world.block(BLOCKED)
        world.contacts += CONTACT
        world.member(AllowGroup.CODE, MEMBER)
        dao.insertOrUpdate(chat(1, SENDER))
        dao.insertOrUpdate(chat(2, OTHER))
        dao.insertOrUpdate(chat(3, BLOCKED))
        dao.insertOrUpdate(chat(4, CONTACT))
        dao.insertOrUpdate(chat(5, MEMBER))
        dao.insertOrUpdate(chat(6, SENDER, group = true))
    }

    @After
    fun tearDown() = db.close()

    private fun sorted() = assertThat(sorter.recompute()).isInstanceOf(SortResult.Applied::class.java)

    private fun count(change: SortChange): Int? = runBlocking { dryRun.chatsMoving(change) }

    /** What a dry run must leave as it was: the Spam table and the group members. */
    private fun written() = dao.getSpamThreads().toSet() to world.members.toList()

    @Test
    fun theSwitchGoingOffCountsTheUnknownSendersChatsOnly() {
        sorted()
        val before = written()

        // Chats 1 and 2 (unknown senders) leave Spam; the blocked sender's chat stays.
        assertThat(count(SortChange.Filter(on = false))).isEqualTo(2)
        assertThat(written()).isEqualTo(before)
    }

    @Test
    fun aGroupGoingOffCountsItsMembersChats() {
        sorted()

        assertThat(count(SortChange.Group(AllowGroup.CODE, on = false))).isEqualTo(1)
        assertThat(count(SortChange.Group(AllowGroup.WROTE, on = false))).isEqualTo(0)
    }

    @Test
    fun aGroupGoingOnCountsTheChatsThatLeaveSpam() {
        world.settings = world.settings.copy(codeGroupOn = false)
        sorted()

        assertThat(count(SortChange.Group(AllowGroup.CODE, on = true))).isEqualTo(1)
    }

    @Test
    fun theSwitchGoingOnCountsAfterTheHistoryChecksJoinsWithoutJoining() {
        world.settings = world.settings.copy(filterOn = false)
        sorted()
        wrote += SENDER
        val before = written()

        // Chat 1's sender was written to: the switch's pass would allow it. Only chat 2 enters Spam.
        assertThat(count(SortChange.Filter(on = true))).isEqualTo(1)
        assertThat(joins).isEqualTo(0)
        assertThat(written()).isEqualTo(before)
    }

    @Test
    fun theSwitchGoingOnCountsACodeSenderAsAllowed() {
        world.settings = world.settings.copy(filterOn = false)
        sorted()
        received[2] = "Your code is 4711"

        assertThat(count(SortChange.Filter(on = true))).isEqualTo(1)
    }

    @Test
    fun aSenderTheUserRemovedFromAGroupIsNotCountedAsJoining() {
        world.settings = world.settings.copy(filterOn = false)
        sorted()
        wrote += SENDER
        world.readers.senderKeys(SENDER).forEach { removedMarks += AllowGroup.WROTE to it }

        assertThat(count(SortChange.Filter(on = true))).isEqualTo(2)
    }

    @Test
    fun theCountIsUnknownBeforeTheFirstSortOrOnAFailedRead() {
        sorted()
        published = false
        assertThat(count(SortChange.Filter(on = false))).isNull()

        published = true
        world.contactsReadable = true
        world.lookupFails = true
        assertThat(count(SortChange.Filter(on = false))).isNull()

        world.lookupFails = false
        world.rulesFail = true
        assertThat(count(SortChange.Filter(on = false))).isNull()
    }

    @Test
    fun aChangeThatMovesNothingCountsZero() {
        sorted()

        assertThat(count(SortChange.Filter(on = true))).isEqualTo(0)
    }
}
