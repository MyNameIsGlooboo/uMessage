package com.glooboo.umessage.extensions

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.models.Conversation
import com.glooboo.umessage.testing.FakeThreadStore
import com.glooboo.umessage.testing.offMainThread
import com.glooboo.umessage.testing.resetSharedMessagesDatabase
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * "Delete forever", "Empty the Recycle Bin" and the automatic purge remove only the messages that are in the bin:
 * the other messages of the same chat stay, and the chat row goes only when nothing is left of the chat.
 */
@RunWith(AndroidJUnit4::class)
class RecycleBinPurgeTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var store: FakeThreadStore

    @Before
    fun setUp() {
        store = FakeThreadStore.install()
        store.addThread(CHAT, FakeThreadStore.ADDRESS)
        store.addThread(ONLY_BIN_CHAT, FakeThreadStore.ADDRESS)
    }

    @After
    fun tearDown() = resetSharedMessagesDatabase()

    private fun addMessages(threadId: Long, ids: LongRange) {
        ids.forEach { store.addSms(it, threadId, "Message $it", dateMs = 1_700_000_000_000 + it * 1_000) }
    }

    /** Caches every provider message and a chat row for each thread, then bins [binned]. */
    private fun cacheAndBin(vararg binned: Long) = offMainThread {
        listOf(CHAT, ONLY_BIN_CHAT).forEach { thread ->
            context.messagesDB.insertOrIgnoreAll(context.getMessages(thread, includeScheduledMessages = false))
            context.conversationsDB.insertOrUpdate(
                Conversation(thread, "snippet", 1_700_000_000, true, "Chat $thread", "", false, "+1555000$thread")
            )
        }
        binned.forEach { context.moveMessageToRecycleBin(it, false) }
    }

    private fun live() = offMainThread { store.smsIds() }

    private fun binEntries() = offMainThread { context.messagesDB.getAllRecycleBinEntries().map { it.id }.sorted() }

    private fun chatRows() = offMainThread {
        listOf(CHAT, ONLY_BIN_CHAT).filter { context.conversationsDB.getConversationWithThreadId(it) != null }
    }

    @Test
    fun deleteForeverKeepsTheMessagesThatAreNotInTheBin() {
        addMessages(CHAT, 1..5L)
        cacheAndBin(4, 5)

        offMainThread { context.deleteRecycleBinMessagesOfConversations(listOf(CHAT)) }

        assertThat(live()).containsExactly(1L, 2L, 3L)
        assertThat(offMainThread { context.messagesDB.getNonRecycledThreadMessages(CHAT).map { it.id }.sorted() })
            .containsExactly(1L, 2L, 3L)
        assertThat(binEntries()).isEmpty()
        assertThat(chatRows()).contains(CHAT)
    }

    @Test
    fun deleteForeverRemovesTheChatWhenOnlyBinnedMessagesWereLeft() {
        addMessages(ONLY_BIN_CHAT, 10..11L)
        cacheAndBin(10, 11)

        offMainThread { context.deleteRecycleBinMessagesOfConversations(listOf(ONLY_BIN_CHAT)) }

        assertThat(live()).isEmpty()
        assertThat(binEntries()).isEmpty()
        assertThat(chatRows()).doesNotContain(ONLY_BIN_CHAT)
    }

    @Test
    fun emptyingTheBinKeepsLiveMessagesAndDropsOnlyEmptiedChats() {
        addMessages(CHAT, 1..5L)
        addMessages(ONLY_BIN_CHAT, 10..11L)
        cacheAndBin(4, 5, 10, 11)

        offMainThread { context.emptyRecycleBinAndTidyConversations() }

        assertThat(live()).containsExactly(1L, 2L, 3L)
        assertThat(binEntries()).isEmpty()
        assertThat(chatRows()).containsExactly(CHAT)
    }

    @Test
    fun emptyingAnEmptyBinChangesNothing() {
        addMessages(CHAT, 1..3L)
        cacheAndBin()

        offMainThread { context.emptyRecycleBinAndTidyConversations() }

        assertThat(live()).containsExactly(1L, 2L, 3L)
        assertThat(chatRows()).containsExactly(CHAT, ONLY_BIN_CHAT)
    }

    @Test
    fun theAutomaticPurgeDeletesOnlyOldBinEntries() {
        addMessages(CHAT, 1..5L)
        cacheAndBin(4, 5)
        // Message 4 was binned long ago, message 5 just now.
        offMainThread {
            val now = System.currentTimeMillis()
            context.messagesDB.insertRecycleBinEntry(
                com.glooboo.umessage.models.RecycleBinMessage(4, false, now - 40L * 24 * 3600 * 1000)
            )
            context.deleteOldRecycleBinMessages(now)
        }

        assertThat(live()).containsExactly(1L, 2L, 3L, 5L)
        assertThat(binEntries()).containsExactly(5L)
    }

    private companion object {
        const val CHAT = 7L
        const val ONLY_BIN_CHAT = 8L
    }
}
