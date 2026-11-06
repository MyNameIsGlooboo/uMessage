package com.glooboo.umessage.interfaces

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.databases.MessagesDatabase
import com.glooboo.umessage.models.Conversation
import com.glooboo.umessage.models.Message
import com.glooboo.umessage.models.RecycleBinMessage
import com.glooboo.umessage.ui.conversations.conversationPreview
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** DL-08: the preview under a chat is its real newest sent or received message. */
@RunWith(AndroidJUnit4::class)
class ConversationSnippetTest {

    private lateinit var db: MessagesDatabase
    private lateinit var messages: MessagesDao
    private lateinit var conversations: ConversationsDao

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, MessagesDatabase::class.java).allowMainThreadQueries().build()
        messages = db.MessagesDao()
        conversations = db.ConversationsDao()
        conversations.insertOrUpdate(conversation(THREAD))
    }

    @After
    fun tearDown() = db.close()

    private fun snippet() = conversations.getNonArchived().single { it.threadId == THREAD }.snippet

    @Test
    fun anUnsentScheduledMessageIsNotThePreview() {
        messages.insertOrUpdate(message(1, "See you Friday", date = 1000))
        messages.insertOrUpdate(message(SCHEDULED_ID, "Happy new year!", date = 9000, scheduled = true))

        assertThat(snippet()).isEqualTo("See you Friday")
        assertThat(messages.getNewestShownMessage(THREAD)?.body).isEqualTo("See you Friday")
    }

    @Test
    fun aBinnedMmsDoesNotHideTheSmsWithTheSameId() {
        messages.insertOrUpdate(message(57, "Old text", date = 1000))
        messages.insertOrUpdate(message(58, "Latest text", date = 2000))
        messages.insertOrUpdate(message(58, "Binned picture", date = 3000, mms = true))
        messages.insertRecycleBinEntry(RecycleBinMessage(58, isMMS = true, deletedTS = 1))

        assertThat(snippet()).isEqualTo("Latest text")
        assertThat(messages.getNewestShownMessage(THREAD)?.body).isEqualTo("Latest text")
        assertThat(messages.getLastMessageDates().single().date).isEqualTo(2000)
    }

    @Test
    fun theRecycleBinListPreviewsTheBinnedMessage() {
        messages.insertOrUpdate(message(58, "Latest text", date = 2000))
        messages.insertOrUpdate(message(58, "Binned picture", date = 1000, mms = true))
        messages.insertRecycleBinEntry(RecycleBinMessage(58, isMMS = true, deletedTS = 1))

        val binned = conversations.getAllWithMessagesInRecycleBin().single()

        assertThat(binned.snippet).isEqualTo("Binned picture")
    }

    @Test
    fun aMessageWithoutTextShowsALabelInsteadOfABlankLine() {
        assertThat(conversationPreview("", null, "Attachment")).isEqualTo("Attachment")
        assertThat(conversationPreview("Line one\nline two", null, "Attachment")).isEqualTo("Line one line two")
        assertThat(conversationPreview("", "My draft", "Attachment")).isEqualTo("My draft")
    }

    private fun message(id: Long, body: String, date: Int, mms: Boolean = false, scheduled: Boolean = false) = Message(
        id = id,
        body = body,
        type = 1,
        status = -1,
        participants = ArrayList(),
        date = date,
        read = true,
        threadId = THREAD,
        isMMS = mms,
        attachment = null,
        senderPhoneNumber = "",
        senderName = "",
        senderPhotoUri = "",
        subscriptionId = 0,
        isScheduled = scheduled,
    )

    private fun conversation(threadId: Long) = Conversation(
        threadId = threadId,
        snippet = "provider snippet",
        date = 1000,
        read = true,
        title = "Ali",
        photoUri = "",
        isGroupConversation = false,
        phoneNumber = "",
    )

    private companion object {
        const val THREAD = 7L
        const val SCHEDULED_ID = 1_000_000_000_000L
    }
}
