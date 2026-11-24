package com.glooboo.umessage.interfaces

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.databases.MessagesDatabase
import com.glooboo.umessage.models.Conversation
import com.glooboo.umessage.models.Message
import com.glooboo.umessage.spam.SpamThread
import com.glooboo.umessage.spam.engine.SpamCause
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * S1, S9: the four chat lists of provider truth. Every cached chat is in exactly one of Messages, Archived and Spam,
 * and a spam_threads row counts only while its chat still has its sender and is one-to-one. Numbers are made up.
 */
@RunWith(AndroidJUnit4::class)
class SpamListQueriesTest {

    private lateinit var db: MessagesDatabase
    private lateinit var dao: ConversationsDao

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, MessagesDatabase::class.java).allowMainThreadQueries().build()
        dao = db.ConversationsDao()
        dao.insertOrUpdate(chat(1, "+15550001234"))
        dao.insertOrUpdate(chat(2, "+15550001235"))
        dao.insertOrUpdate(chat(3, "+15550001236", archived = true))
        dao.insertOrUpdate(chat(4, "+15550001237", archived = true))
        dao.insertOrUpdate(chat(5, "+15550001238", group = true))
        dao.insertOrUpdate(chat(6, "+15550001239"))
        spam(2, "+15550001235")
        spam(4, "+15550001237")
        spam(5, "+15550001238")
        spam(6, "+15550009999") // another person's old row (S9)
    }

    @After
    fun tearDown() = db.close()

    private fun chat(id: Long, number: String, archived: Boolean = false, group: Boolean = false) = Conversation(
        threadId = id, snippet = "hi", date = id.toInt(), read = true, title = "Chat $id", photoUri = "",
        isGroupConversation = group, phoneNumber = number, isArchived = archived,
    )

    private fun spam(id: Long, sender: String) =
        dao.insertSpamThread(SpamThread(id, sender, SpamCause.UNKNOWN, spamSince = 1L))

    private fun ids(list: List<Conversation>) = list.map { it.threadId }

    @Test
    fun everyChatIsInExactlyOneList() {
        val messages = ids(dao.getMessagesList())
        val archived = ids(dao.getArchivedList())
        val spam = ids(dao.getSpamList())

        assertThat(messages).containsExactly(1L, 5L, 6L)
        assertThat(archived).containsExactly(3L)
        assertThat(spam).containsExactly(2L, 4L)
        assertThat(ids(dao.getAllRows())).containsExactlyElementsIn(messages + archived + spam)
    }

    @Test
    fun onlyRowsThatCountAreValidAndInSpam() {
        assertThat(dao.getValidSpamThreads().map { it.threadId }).containsExactly(2L, 4L)
        assertThat(dao.isInSpam(2)).isTrue()
        assertThat(dao.isInSpam(5)).isFalse()
        assertThat(dao.isInSpam(6)).isFalse()
        assertThat(dao.isInSpam(1)).isFalse()
    }

    @Test
    fun aSpamChatDeletedFromTheCacheLeavesTheSpamList() {
        dao.deleteThreadId(2)

        assertThat(ids(dao.getSpamList())).containsExactly(4L)
        assertThat(dao.getSpamThread(2)).isNotNull()
        assertThat(dao.isInSpam(2)).isFalse()
    }

    @Test
    fun theSpamListShowsTheNewestShownMessageAsItsPreview() {
        val messages = db.MessagesDao()
        messages.insertOrUpdate(
            Message(
                id = 10, body = "Newest spam text", type = 1, status = -1, participants = ArrayList(), date = 50,
                read = true, threadId = 2, isMMS = false, attachment = null, senderPhoneNumber = "",
                senderName = "", senderPhotoUri = "", subscriptionId = -1,
            )
        )

        assertThat(dao.getSpamList().single { it.threadId == 2L }.snippet).isEqualTo("Newest spam text")
    }

    @Test
    fun aSpamArrivalWritesTheRowFirstAndCountsEachText() {
        dao.noteSpamArrival(1, "+15550001234", SpamCause.UNKNOWN, at = 500L) { a, b -> a == b }
        dao.noteSpamArrival(1, "+15550001234", SpamCause.UNKNOWN, at = 900L) { a, b -> a == b }

        val row = dao.getSpamThread(1)!!
        assertThat(row.unread).isEqualTo(2)
        assertThat(row.spamSince).isEqualTo(500L)
        assertThat(dao.isInSpam(1)).isTrue()
        assertThat(ids(dao.getMessagesList())).doesNotContain(1L)
    }

    @Test
    fun aSpamArrivalReplacesARowOfAnotherSender() {
        // B-7: thread 6 held another person's old row; the new text's sender gets a fresh one.
        dao.raiseSpamUnread(6)
        dao.noteSpamArrival(6, "+15550001239", SpamCause.UNKNOWN, at = 800L) { a, b -> a == b }

        val row = dao.getSpamThread(6)!!
        assertThat(row.sender).isEqualTo("+15550001239")
        assertThat(row.unread).isEqualTo(1)
        assertThat(row.spamSince).isEqualTo(800L)
        assertThat(dao.isInSpam(6)).isTrue()
    }

    @Test
    fun theSameSenderWrittenAnotherWayKeepsItsRowOnArrival() {
        dao.noteSpamArrival(2, "(555) 000-1235", SpamCause.UNKNOWN, at = 800L) { a, b ->
            a.filter(Char::isDigit).takeLast(10) == b.filter(Char::isDigit).takeLast(10)
        }

        val row = dao.getSpamThread(2)!!
        assertThat(row.sender).isEqualTo("+15550001235")
        assertThat(row.spamSince).isEqualTo(1L)
        assertThat(row.unread).isEqualTo(1)
    }

    @Test
    fun theCardCountsValidRowsSinceAMoment() {
        dao.noteSpamArrival(1, "+15550001234", SpamCause.UNKNOWN, at = 500L) { a, b -> a == b }

        assertThat(dao.countSpamSince(1L)).isEqualTo(3)
        assertThat(dao.countSpamSince(500L)).isEqualTo(1)
        assertThat(dao.countSpamSince(501L)).isEqualTo(0)
    }

    @Test
    fun unreadIsRaisedAndClearedOnlyByItsOwnUpdates() {
        dao.raiseSpamUnread(2)
        dao.raiseSpamUnread(2)
        dao.raiseSpamUnread(4)
        assertThat(dao.getSpamThread(2)?.unread).isEqualTo(2)

        // A second insert of the same row (a rebuild, a repeated arrival) keeps it.
        dao.insertSpamThread(SpamThread(2, "+15550001235", SpamCause.BLOCKED, spamSince = 9L))
        assertThat(dao.getSpamThread(2)?.unread).isEqualTo(2)
        assertThat(dao.getSpamThread(2)?.spamSince).isEqualTo(1L)

        dao.clearSpamUnread(2)
        assertThat(dao.getSpamThread(2)?.unread).isEqualTo(0)
        dao.clearAllSpamUnread()
        assertThat(dao.getSpamThread(4)?.unread).isEqualTo(0)
    }

    @Test
    fun theUnreadCountCanBeSetToWhatTheStoreStillHolds() {
        dao.raiseSpamUnread(2)
        dao.raiseSpamUnread(2)

        // J29: after a delete that kept a newer text, the count is the store's, not a plain reset.
        assertThat(dao.setSpamUnread(2, 1)).isEqualTo(1)
        assertThat(dao.getSpamThread(2)?.unread).isEqualTo(1)
        // A row that is not there is left alone.
        assertThat(dao.setSpamUnread(99, 4)).isEqualTo(0)
    }
}
