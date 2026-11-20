package com.glooboo.umessage.spam

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.databases.MessagesDatabase
import com.glooboo.umessage.interfaces.ConversationsDao
import com.glooboo.umessage.models.Conversation
import com.glooboo.umessage.spam.SortingWorld.Companion.SENDER
import com.glooboo.umessage.spam.SortingWorld.Companion.chat
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Step B, new chat rows (B-6, B2-3, B2-6): after the first sort a new chat's sender is checked first and the row is
 * cached with its sort, also while a later pass works; before it, and for more than [NewChatSorting.BATCH] rows, the
 * rows are cached and a pass (or the first run) sorts them. The number is made up.
 */
@RunWith(AndroidJUnit4::class)
class NewChatSorterTest {

    private lateinit var db: MessagesDatabase
    private lateinit var dao: ConversationsDao
    private val world = SortingWorld()
    private var state = FirstRunState(phase = FirstRunPhase.DONE, sortPublished = true)

    private val checked = mutableListOf<Long>()
    private val requested = mutableListOf<Long>()
    private var passes = 0
    private val published = mutableListOf<SortResult>()

    private val sorting by lazy {
        NewChatSorter(
            dao = dao,
            state = { state },
            checkHistory = { rows ->
                // The history check runs before the row is cached: the row is never shown unsorted.
                rows.forEach { assertThat(dao.getConversationWithThreadId(it.threadId)).isNull() }
                checked += rows.map { it.threadId }
            },
            sorter = { ChatSorter(db, { world.judge.snapshot() }, { world.contactsReadable }) },
            requestThreads = { requested += it },
            requestPass = { passes++ },
            published = { published += it },
        )
    }

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, MessagesDatabase::class.java).allowMainThreadQueries().build()
        dao = db.ConversationsDao()
        world.block(SENDER)
    }

    @After
    fun tearDown() = db.close()

    private fun cachedIds() = dao.getAllRows().map { it.threadId }.toSet()

    private fun spamIds() = dao.getSpamThreads().map { it.threadId }.toSet()

    @Test
    fun afterTheFirstSortANewChatIsCheckedThenCachedWithItsSort() {
        sorting.cacheAndSort(
            listOf(chat(1, SENDER), chat(2, SENDER, group = true), chat(3, SENDER, scheduled = true))
        )

        // Only the one-to-one chat has a sender to check; the group and the scheduled-only chat stay in Messages.
        assertThat(checked).containsExactly(1L)
        assertThat(cachedIds()).containsExactly(1L, 2L, 3L)
        assertThat(spamIds()).containsExactly(1L)
        assertThat(published).hasSize(1)
        assertThat(requested).isEmpty()
        assertThat(passes).isEqualTo(0)
    }

    @Test
    fun aNewRowKeepsItsScheduledMark() {
        // B2-6: a row of scheduled messages only is not sortable, also when it is new.
        sorting.cacheAndSort(listOf(chat(3, SENDER, scheduled = true)))

        assertThat(dao.getConversationWithThreadId(3)!!.isScheduled).isTrue()
        assertThat(spamIds()).isEmpty()
    }

    @Test
    fun whileALaterPassWorksANewChatStillGetsItsHistoryCheckAndSort() {
        // B2-3: the pass read the store before this chat came; the chat does not wait for it unchecked.
        state = FirstRunState(
            phase = FirstRunPhase.SORT, trigger = FirstRunTrigger.CONTACTS_GRANTED, sortPublished = true,
        )

        sorting.cacheAndSort(listOf(chat(1, SENDER)))

        assertThat(checked).containsExactly(1L)
        assertThat(spamIds()).containsExactly(1L)
        assertThat(passes).isEqualTo(0)
    }

    @Test
    fun beforeTheFirstSortTheRowsAreCachedAndWaitForTheFirstRun() {
        state = FirstRunState(phase = FirstRunPhase.HISTORY, trigger = FirstRunTrigger.UPDATE)

        sorting.cacheAndSort(listOf(chat(1, SENDER)))

        assertThat(checked).isEmpty()
        assertThat(cachedIds()).containsExactly(1L)
        assertThat(spamIds()).isEmpty()
        assertThat(requested).containsExactly(1L)
        assertThat(passes).isEqualTo(0)
    }

    @Test
    fun moreThanABatchOfNewChatsIsCachedAndGetsAPass() {
        val rows: List<Conversation> = (1L..NewChatSorting.BATCH + 1L).map { chat(it, SENDER) }

        sorting.cacheAndSort(rows)

        assertThat(cachedIds()).hasSize(NewChatSorting.BATCH + 1)
        assertThat(checked).isEmpty()
        assertThat(spamIds()).isEmpty()
        assertThat(passes).isEqualTo(1)
        assertThat(requested).isEmpty()
    }

    @Test
    fun exactlyABatchIsStillCheckedAndSortedAtOnce() {
        val rows: List<Conversation> = (1L..NewChatSorting.BATCH).map { chat(it, SENDER) }

        sorting.cacheAndSort(rows)

        assertThat(checked).hasSize(NewChatSorting.BATCH)
        assertThat(spamIds()).hasSize(NewChatSorting.BATCH)
        assertThat(passes).isEqualTo(0)
    }

    @Test
    fun aFailedSortStillCachesTheRowsAndAsksAgain() {
        world.rulesFail = true

        sorting.cacheAndSort(listOf(chat(1, SENDER)))

        assertThat(cachedIds()).containsExactly(1L)
        assertThat(spamIds()).isEmpty()
        assertThat(requested).containsExactly(1L)
        assertThat(published).isEmpty()
    }
}
