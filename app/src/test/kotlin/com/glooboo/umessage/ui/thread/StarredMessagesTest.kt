package com.glooboo.umessage.ui.thread

import android.content.Context
import android.provider.Telephony
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.databases.MessagesDatabase
import com.glooboo.umessage.helpers.StarredMessages
import com.glooboo.umessage.models.Message
import com.glooboo.umessage.models.MessageKey
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StarredMessagesTest {

    private lateinit var db: MessagesDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MessagesDatabase::class.java).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    private fun message(
        id: Long,
        body: String = "keep this",
        thread: Long = 7,
        date: Int = 1000,
        from: String = "+15550100",
    ) = Message(
        id = id,
        body = body,
        type = Telephony.Sms.MESSAGE_TYPE_INBOX,
        status = 0,
        participants = ArrayList(),
        date = date,
        read = true,
        threadId = thread,
        isMMS = false,
        attachment = null,
        senderPhoneNumber = from,
        senderName = "",
        senderPhotoUri = "",
        subscriptionId = 0,
    )

    private fun star(message: Message) = db.StarredMessagesDao().let { dao ->
        runBlocking { dao.insert(StarredMessages.snapshotOf(message, 5000)) }
    }

    @Test
    fun starAndUnstarKeepOnlyThatMessage() = runBlocking {
        val dao = db.StarredMessagesDao()
        star(message(1))
        star(message(2))
        dao.delete(1, false)

        assertThat(dao.getAll().map { it.id }).containsExactly(2L)
        assertThat(dao.isStarred(2, false)).isTrue()
        assertThat(dao.isStarred(1, false)).isFalse()
        assertThat(dao.isStarred(2, true)).isFalse()
    }

    @Test
    fun aStarSurvivesDeletingTheChatAndItsMessages() {
        val messages = db.MessagesDao()
        val first = message(1)
        messages.insertOrUpdate(first)
        star(first)

        messages.deleteThreadMessages(7)

        assertThat(messages.getMessage(1, false)).isNull()
        assertThat(db.StarredMessagesDao().isStarred(1, false)).isTrue()
        assertThat(runBlocking { db.StarredMessagesDao().get(1, false) }?.snapshotBody).isEqualTo("keep this")
    }

    @Test
    fun aStarFindsItsMessageAgainUnderANewId() {
        val star = StarredMessages.snapshotOf(message(1), 5000)
        val candidates = listOf(
            message(20, body = "other text"),
            message(21, from = "+15559999"),
            message(22, thread = 9),
            message(23, thread = 7),
        )

        // The same time, text and sender: the one in the same chat wins.
        assertThat(StarredMessages.relinkTarget(star, candidates)?.id).isEqualTo(23L)
        assertThat(StarredMessages.relinkTarget(star, candidates.take(2))).isNull()
    }

    @Test
    fun theSenderMatchesWhateverTheNumberIsWrittenLike() {
        val star = StarredMessages.snapshotOf(message(1, from = "+44 712 345 6789"), 5000)
        val same = message(2, from = "07123456789")

        assertThat(StarredMessages.relinkTarget(star, listOf(same))?.id).isEqualTo(2L)
    }

    @Test
    fun theCacheFindsTheMessageBackUnderANewId() = runBlocking {
        val dao = db.StarredMessagesDao()
        star(message(1))
        // The cache was rebuilt: the message is back as id 50, even in another chat.
        db.MessagesDao().insertOrUpdate(message(50, thread = 8))
        val moved = StarredMessages.relinkTarget(dao.getAll().single(), db.MessagesDao().getMessagesAtDate(1000))
        assertThat(moved?.key).isEqualTo(MessageKey(50, false))
    }

    @Test
    fun theFingerprintFoldsWhiteSpaceOnly() {
        assertThat(StarredMessages.fingerprint(" a  b\n c ")).isEqualTo(StarredMessages.fingerprint("a b c"))
        assertThat(StarredMessages.fingerprint("a b c")).isNotEqualTo(StarredMessages.fingerprint("a b d"))
    }

    @Test
    fun allStarredNeedsEveryMessageStarred() {
        val a = message(1)
        val b = message(2)
        val starred = setOf(a.key)

        assertThat(allStarred(listOf(a), starred)).isTrue()
        assertThat(allStarred(listOf(a, b), starred)).isFalse()
        assertThat(allStarred(emptyList(), starred)).isFalse()
    }
}
