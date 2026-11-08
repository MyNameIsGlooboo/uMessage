package com.glooboo.umessage.helpers

import android.content.Context
import android.provider.Telephony
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.databases.MessagesDatabase
import com.glooboo.umessage.models.Attachment
import com.glooboo.umessage.models.Conversation
import com.glooboo.umessage.models.Message
import com.glooboo.umessage.models.MessageAttachment
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The Starred screen lists starred messages, one row each: the query, the mapping, the order and the search. */
@RunWith(AndroidJUnit4::class)
class StarredListTest {

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
        body: String = "text $id",
        thread: Long = 7,
        date: Int = 1000,
        from: String = "+15550100",
        type: Int = Telephony.Sms.MESSAGE_TYPE_INBOX,
        attachment: MessageAttachment? = null,
    ) = Message(
        id = id, body = body, type = type, status = 0, participants = ArrayList(), date = date, read = true,
        threadId = thread, isMMS = attachment != null, attachment = attachment, senderPhoneNumber = from,
        senderName = "", senderPhotoUri = "", subscriptionId = 0,
    )

    private fun chat(thread: Long, title: String, group: Boolean = false) = Conversation(
        threadId = thread, snippet = "", date = 0, read = true, title = title, photoUri = "",
        isGroupConversation = group, phoneNumber = "+15550100",
    )

    /** Caches [message] and stars it at [starredAt]. */
    private fun star(message: Message, starredAt: Long) = runBlocking {
        db.MessagesDao().insertOrUpdate(message)
        db.StarredMessagesDao().insert(StarredMessages.snapshotOf(message, starredAt))
    }

    private fun items(
        chats: Map<Long, Conversation> = mapOf(7L to chat(7, "Sam")),
        notes: Set<String> = emptySet(),
        limit: Int = 100,
    ): List<StarredItem> = runBlocking {
        val rows = db.StarredMessagesDao().getListRows(limit)
        StarredList.build(rows, chats, notes) { it }
    }

    @Test
    fun twoStarsFromOneSenderAreTwoRows() {
        star(message(1, "first"), starredAt = 10)
        star(message(2, "second", date = 2000), starredAt = 20)
        star(message(3, "other", thread = 8, from = "+15550199"), starredAt = 15)

        val rows = items(mapOf(7L to chat(7, "Sam"), 8L to chat(8, "Kim")))

        assertThat(rows.map { it.text }).containsExactly("second", "other", "first").inOrder()
        assertThat(rows.map { it.title }).containsExactly("Sam", "Kim", "Sam").inOrder()
        assertThat(rows.map { it.listKey }).containsNoDuplicates()
    }

    @Test
    fun theMostRecentlyStarredComesFirstWhateverTheMessageDate() {
        star(message(1, date = 5000), starredAt = 10)
        star(message(2, date = 100), starredAt = 99)

        assertThat(items().map { it.id }).containsExactly(2L, 1L).inOrder()
    }

    @Test
    fun theRowCarriesTheMessageTimeAndKey() {
        star(message(4, date = 1234), starredAt = 1)

        val row = items().single()

        assertThat(row.dateMillis).isEqualTo(1_234_000L)
        assertThat(row.key.id).isEqualTo(4L)
        assertThat(row.chatGone).isFalse()
    }

    @Test
    fun aGroupChatShowsItsTitleAndIsMarkedAsGroup() {
        star(message(1, thread = 9), starredAt = 1)

        val row = items(mapOf(9L to chat(9, "Family", group = true))).single()

        assertThat(row.title).isEqualTo("Family")
        assertThat(row.isGroup).isTrue()
    }

    @Test
    fun aPictureMessageIsMarkedAndAnAttachmentToo() {
        fun withFile(id: Long, mime: String) = message(
            id,
            body = "",
            attachment = MessageAttachment(
                id, "", arrayListOf(Attachment(null, id, "content://x/$id", mime, 10, 10, "f")),
            ),
        )
        star(withFile(1, "image/jpeg"), starredAt = 3)
        star(withFile(2, "application/pdf"), starredAt = 2)
        star(message(3, "plain"), starredAt = 1)

        assertThat(items().map { it.kind })
            .containsExactly(StarredKind.PICTURE, StarredKind.ATTACHMENT, StarredKind.TEXT).inOrder()
    }

    @Test
    fun aMessageWithANoteGetsTheNoteMark() {
        val noted = message(1, "rent is due")
        star(noted, starredAt = 1)
        star(message(2, "no note"), starredAt = 2)

        val rows = items(notes = setOf(MessageNoteKey.of(noted)))

        assertThat(rows.associate { it.id to it.hasNote }).containsExactly(1L, true, 2L, false)
    }

    @Test
    fun aSentMessageFindsItsNoteToo() {
        val sent = message(1, "on my way", type = Telephony.Sms.MESSAGE_TYPE_SENT)
        star(sent, starredAt = 1)

        assertThat(items(notes = setOf(MessageNoteKey.of(sent))).single().hasNote).isTrue()
    }

    @Test
    fun aStarWhoseChatIsGoneKeepsItsSavedText() {
        star(message(1, "keep me"), starredAt = 1)
        db.MessagesDao().deleteThreadMessages(7)

        val row = items(chats = emptyMap()).single()

        assertThat(row.text).isEqualTo("keep me")
        assertThat(row.chatGone).isTrue()
        assertThat(row.title).isEqualTo("+15550100")
    }

    @Test
    fun unstarringRemovesOnlyThatRow() {
        star(message(1), starredAt = 1)
        star(message(2), starredAt = 2)

        runBlocking { db.StarredMessagesDao().delete(2, false) }

        assertThat(items().map { it.id }).containsExactly(1L)
    }

    @Test
    fun theQueryStopsAtTheLimit() {
        (1L..5L).forEach { star(message(it), starredAt = it) }

        assertThat(items(limit = 3)).hasSize(3)
    }

    @Test
    fun searchFindsByTextOrChatNameAndIgnoresCase() {
        star(message(1, "Pay the RENT"), starredAt = 1)
        star(message(2, "see you", thread = 8), starredAt = 2)
        val all = items(mapOf(7L to chat(7, "Sam"), 8L to chat(8, "Landlord")))

        assertThat(StarredList.filter(all, "rent").map { it.id }).containsExactly(1L)
        assertThat(StarredList.filter(all, "landlord").map { it.id }).containsExactly(2L)
        assertThat(StarredList.filter(all, "  ")).hasSize(2)
        assertThat(StarredList.filter(all, "zzz")).isEmpty()
    }
}
