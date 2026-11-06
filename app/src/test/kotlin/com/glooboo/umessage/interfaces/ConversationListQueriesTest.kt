package com.glooboo.umessage.interfaces

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.databases.MessagesDatabase
import com.glooboo.umessage.models.Conversation
import com.glooboo.umessage.models.Message
import com.glooboo.umessage.models.RecycleBinMessage
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** DL-07, MC-19: the list's saved-data queries look chats up by index instead of scanning every message. */
@RunWith(AndroidJUnit4::class)
class ConversationListQueriesTest {

    private lateinit var db: MessagesDatabase
    private lateinit var messages: MessagesDao
    private lateinit var conversations: ConversationsDao

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, MessagesDatabase::class.java).allowMainThreadQueries().build()
        messages = db.MessagesDao()
        conversations = db.ConversationsDao()
    }

    @After
    fun tearDown() = db.close()

    private fun plan(sql: String): List<String> =
        db.openHelper.readableDatabase.query("EXPLAIN QUERY PLAN $sql").use { c ->
            buildList { while (c.moveToNext()) add(c.getString(c.getColumnIndexOrThrow("detail"))) }
        }

    @Test
    fun recycleBinChatsAreFoundFromTheBinNotFromEveryChat() {
        val plan = plan(ConversationQueries.IN_RECYCLE_BIN)
        // SQLite versions word plans differently ("SCAN conversations" or "SCAN TABLE conversations").
        assertThat(plan.none { it.startsWith("SCAN") && it.contains("conversations") }).isTrue()
        assertThat(plan.any { it.startsWith("SCAN") && it.contains("recycle_bin_messages") }).isTrue()
    }

    @Test
    fun resumeCheckDoesNotScanEveryMessage() {
        val plan = plan(ConversationQueries.OLDER_THAN_MESSAGES)
        assertThat(plan.none { it.startsWith("SCAN") && it.contains("messages") }).isTrue()
        assertThat(plan.any { it.contains("index_messages_thread_id_date") }).isTrue()
    }

    @Test
    fun recycleBinListKeepsItsResults() {
        conversations.insertOrUpdate(conversation(1))
        conversations.insertOrUpdate(conversation(2))
        messages.insertOrUpdate(message(10, thread = 1, body = "Kept", date = 100))
        messages.insertOrUpdate(message(11, thread = 1, body = "Binned", date = 200))
        messages.insertOrUpdate(message(12, thread = 2, body = "Only kept", date = 300))
        messages.insertRecycleBinEntry(RecycleBinMessage(11, isMMS = false, deletedTS = 1))

        val inBin = conversations.getAllWithMessagesInRecycleBin()
        assertThat(inBin.map { it.threadId }).containsExactly(1L)
        assertThat(inBin.single().snippet).isEqualTo("Binned")
    }

    @Test
    fun olderThanMessagesListsOnlyChatsWithNewerShownMessages() {
        conversations.insertOrUpdate(conversation(1, date = 100))
        conversations.insertOrUpdate(conversation(2, date = 500))
        conversations.insertOrUpdate(conversation(3, date = 100))
        conversations.insertOrUpdate(conversation(4, date = 100).copy(isArchived = true))
        messages.insertOrUpdate(message(1, thread = 1, body = "Newer", date = 200))
        messages.insertOrUpdate(message(2, thread = 2, body = "Older", date = 400))
        messages.insertOrUpdate(message(3, thread = 3, body = "Scheduled", date = 900, scheduled = true))
        messages.insertOrUpdate(message(4, thread = 3, body = "Binned", date = 800))
        messages.insertRecycleBinEntry(RecycleBinMessage(4, isMMS = false, deletedTS = 1))
        messages.insertOrUpdate(message(5, thread = 4, body = "Archived", date = 900))

        assertThat(conversations.getNonArchivedOlderThanMessages()).containsExactly(ThreadLastDate(1, 200))
    }

    private fun conversation(thread: Long, date: Int = 0) = Conversation(
        threadId = thread, snippet = "", date = date, read = true, title = "Chat $thread", photoUri = "",
        isGroupConversation = false, phoneNumber = "+1555$thread",
    )

    private fun message(id: Long, thread: Long, body: String, date: Int, scheduled: Boolean = false) = Message(
        id = id, body = body, type = 1, status = -1, participants = ArrayList(), date = date, read = true,
        threadId = thread, isMMS = false, attachment = null, senderPhoneNumber = "", senderName = "",
        senderPhotoUri = "", subscriptionId = -1, isScheduled = scheduled,
    )
}
