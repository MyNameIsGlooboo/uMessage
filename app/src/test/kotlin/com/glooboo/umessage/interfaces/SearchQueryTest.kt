package com.glooboo.umessage.interfaces

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.databases.MessagesDatabase
import com.glooboo.umessage.models.Conversation
import com.glooboo.umessage.models.Message
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** MC-14 / DL-10: % and _ are matched as themselves. MC-17: newest first by date, capped. */
@RunWith(AndroidJUnit4::class)
class SearchQueryTest {

    private lateinit var db: MessagesDatabase
    private val dao get() = db.MessagesDao()

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, MessagesDatabase::class.java).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    private fun search(text: String) = dao.searchMessages(text).map { it.id }

    @Test
    fun percentIsNotAWildcard() {
        dao.insertOrUpdate(message(1, "50% off today"))
        dao.insertOrUpdate(message(2, "Hello there, 50 and more"))
        dao.insertOrUpdate(message(3, "Hello there"))
        assertThat(search("50%")).containsExactly(1L)
        assertThat(search("%o")).isEmpty()
    }

    @Test
    fun underscoreIsNotAWildcard() {
        dao.insertOrUpdate(message(1, "file_name.txt"))
        dao.insertOrUpdate(message(2, "filename.txt"))
        dao.insertOrUpdate(message(3, "fileXname.txt"))
        assertThat(search("e_n")).containsExactly(1L)
    }

    @Test
    fun backslashIsMatchedAsItself() {
        dao.insertOrUpdate(message(1, "C:\\path"))
        dao.insertOrUpdate(message(2, "C:path"))
        assertThat(search(":\\p")).containsExactly(1L)
    }

    @Test
    fun foldedSearchEscapesToo() {
        // Digits take the folded path; the % must still be literal there.
        dao.insertOrUpdate(message(1, "تخفیف ۵۰% امروز"))
        dao.insertOrUpdate(message(2, "تخفیف ۵۰ تومان"))
        assertThat(search("۵۰%")).containsExactly(1L)
        assertThat(search("50%")).containsExactly(1L)
    }

    @Test
    fun newestDateFirstAcrossSmsAndMms() {
        // Ids say nothing about age: SMS and MMS ids are separate counters.
        dao.insertOrUpdate(message(900, "hello old", date = 1_000))
        dao.insertOrUpdate(message(5, "hello new", date = 3_000, isMMS = true))
        dao.insertOrUpdate(message(40, "hello mid", date = 2_000))
        assertThat(dao.searchMessages("hello").map { it.date }).containsExactly(3_000, 2_000, 1_000).inOrder()
        assertThat(dao.searchMessages("hello ").map { it.date }).containsExactly(3_000, 2_000, 1_000).inOrder()
    }

    @Test
    fun foldedResultsAreNewestFirstToo() {
        dao.insertOrUpdate(message(900, "code 12 old", date = 1_000))
        dao.insertOrUpdate(message(5, "code ۱۲ new", date = 3_000, isMMS = true))
        assertThat(dao.searchMessages("12").map { it.date }).containsExactly(3_000, 1_000).inOrder()
    }

    @Test
    fun resultsAreCapped() {
        (1..SEARCH_RESULTS_LIMIT + 20).forEach { dao.insertOrUpdate(message(it.toLong(), "hello $it", date = it)) }
        val found = dao.searchMessages("hello")
        assertThat(found).hasSize(SEARCH_RESULTS_LIMIT)
        assertThat(found.first().date).isEqualTo(SEARCH_RESULTS_LIMIT + 20)
        assertThat(dao.searchMessages("hello 1").size).isAtMost(SEARCH_RESULTS_LIMIT)
    }

    @Test
    fun conversationTitlesEscapeWildcards() {
        val conversations = db.ConversationsDao()
        conversations.insertOrUpdate(conversation(1, "Shop 50%"))
        conversations.insertOrUpdate(conversation(2, "Mom 500"))
        conversations.insertOrUpdate(conversation(3, "a_b"))
        conversations.insertOrUpdate(conversation(4, "axb"))
        assertThat(conversations.searchConversations("50%").map { it.threadId }).containsExactly(1L)
        assertThat(conversations.searchConversations("a_b").map { it.threadId }).containsExactly(3L)
    }

    @Test
    fun importIsIdempotentAndKeepsCachedRows() {
        // DL-10: the cache import may read a thread twice; SMS 1 and MMS 1 are different messages.
        dao.insertOrUpdate(message(1, "read in app", date = 5))
        val page = listOf(message(1, "from provider", date = 5), message(1, "mms", isMMS = true), message(2, "old"))
        dao.insertOrIgnoreAll(page)
        dao.insertOrIgnoreAll(page)
        assertThat(dao.getAll()).hasSize(3)
        assertThat(dao.getMessage(1, false)?.body).isEqualTo("read in app")
        assertThat(search("old")).containsExactly(2L)
    }

    private fun message(id: Long, body: String, date: Int = 1000, isMMS: Boolean = false) = Message(
        id = id,
        body = body,
        type = 1,
        status = -1,
        participants = ArrayList(),
        date = date,
        read = true,
        threadId = 7,
        isMMS = isMMS,
        attachment = null,
        senderPhoneNumber = "",
        senderName = "",
        senderPhotoUri = "",
        subscriptionId = 0,
        isScheduled = false,
    )

    private fun conversation(threadId: Long, title: String) = Conversation(
        threadId = threadId,
        snippet = "",
        date = 1000,
        read = true,
        title = title,
        photoUri = "",
        isGroupConversation = false,
        phoneNumber = "",
    )
}
