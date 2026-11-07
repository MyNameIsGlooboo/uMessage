package com.glooboo.umessage.interfaces

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.databases.MessagesDatabase
import com.glooboo.umessage.models.Message
import com.glooboo.umessage.models.RecycleBinMessage
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** F5: main search skips recycle-bin and unsent scheduled messages. PF1: opening a chat reads one page. */
@RunWith(AndroidJUnit4::class)
class SearchVisibilityAndPagingTest {

    private lateinit var db: MessagesDatabase
    private val dao get() = db.MessagesDao()

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, MessagesDatabase::class.java).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    private fun found(text: String) = dao.searchMessages(text).map { it.id }

    @Test
    fun plainSearchSkipsRecycledAndScheduledMessages() {
        dao.insertOrUpdate(message(1, "hello shown"))
        dao.insertOrUpdate(message(2, "hello deleted"))
        dao.insertOrUpdate(message(3, "hello unsent", scheduled = true))
        dao.insertRecycleBinEntry(RecycleBinMessage(id = 2, isMMS = false, deletedTS = 1))
        assertThat(found("hello")).containsExactly(1L)
    }

    @Test
    fun foldedSearchSkipsRecycledAndScheduledMessages() {
        dao.insertOrUpdate(message(1, "code ۱۲ shown"))
        dao.insertOrUpdate(message(2, "code ۱۲ deleted"))
        dao.insertOrUpdate(message(3, "code 12 unsent", scheduled = true))
        dao.insertRecycleBinEntry(RecycleBinMessage(id = 2, isMMS = false, deletedTS = 1))
        assertThat(found("12")).containsExactly(1L)
    }

    @Test
    fun recycledMmsDoesNotHideTheSmsWithTheSameId() {
        dao.insertOrUpdate(message(5, "hello sms"))
        dao.insertOrUpdate(message(5, "hello mms", isMMS = true))
        dao.insertRecycleBinEntry(RecycleBinMessage(id = 5, isMMS = true, deletedTS = 1))
        assertThat(dao.searchMessages("hello").map { it.isMMS }).containsExactly(false)
    }

    @Test
    fun newestPageIsBoundedAndNewestFirst() {
        (1..30).forEach { dao.insertOrUpdate(message(it.toLong(), "m $it", date = it)) }
        assertThat(dao.getNewestThreadMessages(7, 10).map { it.date }).containsExactlyElementsIn((21..30).toList())
        assertThat(dao.getNewestThreadMessages(7, 10).first().date).isEqualTo(30)
    }

    @Test
    fun newestPageRespectsTheRecycleBin() {
        (1..6).forEach { dao.insertOrUpdate(message(it.toLong(), "m $it", date = it)) }
        dao.insertRecycleBinEntry(RecycleBinMessage(id = 6, isMMS = false, deletedTS = 1))
        assertThat(dao.getNewestNonRecycledThreadMessages(7, 3).map { it.id }).containsExactly(5L, 4L, 3L).inOrder()
        assertThat(dao.getNewestRecycleBinThreadMessages(7, 3).map { it.id }).containsExactly(6L)
    }

    private fun message(id: Long, body: String, date: Int = 1000, scheduled: Boolean = false, isMMS: Boolean = false) =
        Message(
            id = id, body = body, type = 1, status = -1, participants = ArrayList(), date = date, read = true,
            threadId = 7, isMMS = isMMS, attachment = null, senderPhoneNumber = "", senderName = "",
            senderPhotoUri = "", subscriptionId = 0, isScheduled = scheduled,
        )
}
