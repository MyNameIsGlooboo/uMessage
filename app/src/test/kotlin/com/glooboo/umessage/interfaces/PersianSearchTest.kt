package com.glooboo.umessage.interfaces

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.databases.MessagesDatabase
import com.glooboo.umessage.models.Conversation
import com.glooboo.umessage.models.Message
import com.glooboo.umessage.ui.main.highlightRanges
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** MC-15: search finds Persian text whatever letter forms, digits or zero-width marks either side uses. */
@RunWith(AndroidJUnit4::class)
class PersianSearchTest {

    private lateinit var db: MessagesDatabase

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, MessagesDatabase::class.java).allowMainThreadQueries().build()
        val dao = db.MessagesDao()
        dao.insertOrUpdate(message(1, "سلام علی"))
        dao.insertOrUpdate(message(2, "كيك تولد"))
        dao.insertOrUpdate(message(3, "رمز پویا: ۱۲۳۴۵۶"))
        dao.insertOrUpdate(message(4, "می\u200Cخواهم بیایم"))
        dao.insertOrUpdate(message(5, "Hello there"))
        db.ConversationsDao().insertOrUpdate(conversation(10, "علي رضايي"))
    }

    @After
    fun tearDown() = db.close()

    private fun search(text: String) = db.MessagesDao().searchMessages(text).map { it.id }

    @Test fun arabicYehFindsPersianYeh() {
        assertThat(search("علي")).containsExactly(1L)
    }

    @Test fun persianKafFindsArabicKaf() {
        assertThat(search("کیک")).containsExactly(2L)
    }

    @Test fun asciiDigitsFindPersianDigits() {
        assertThat(search("123456")).containsExactly(3L)
        assertThat(search("۱۲۳")).containsExactly(3L)
    }

    @Test fun zeroWidthNonJoinerIsIgnored() {
        assertThat(search("میخواهم")).containsExactly(4L)
        assertThat(search("می\u200Cخواهم")).containsExactly(4L)
    }

    @Test fun plainSearchStillWorks() {
        assertThat(search("hello")).containsExactly(5L)
        assertThat(search("nothing")).isEmpty()
    }

    @Test fun conversationTitlesAreFolded() {
        assertThat(db.ConversationsDao().searchConversations("علی رضایی").map { it.threadId }).containsExactly(10L)
    }

    @Test fun highlightFollowsTheFoldedMatch() {
        assertThat(highlightRanges("سلام علي", "علی")).containsExactly(5..7)
        assertThat(highlightRanges("می\u200Cخواهم", "میخ")).containsExactly(0..3)
        assertThat(highlightRanges("Hello hello", "HELLO")).containsExactly(0..4, 6..10)
    }

    private fun message(id: Long, body: String) = Message(
        id = id,
        body = body,
        type = 1,
        status = -1,
        participants = ArrayList(),
        date = 1000,
        read = true,
        threadId = 7,
        isMMS = false,
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
