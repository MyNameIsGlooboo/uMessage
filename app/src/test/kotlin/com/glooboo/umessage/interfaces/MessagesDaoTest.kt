package com.glooboo.umessage.interfaces

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.databases.MessagesDatabase
import com.glooboo.umessage.models.Message
import com.glooboo.umessage.models.MessageKey
import com.glooboo.umessage.models.RecycleBinMessage
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MessagesDaoTest {

    private lateinit var db: MessagesDatabase
    private lateinit var dao: MessagesDao

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, MessagesDatabase::class.java).allowMainThreadQueries().build()
        dao = db.MessagesDao()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun oneTimeCleanupKeepsScheduledAndRecycleBinMessages() {
        dao.insertOrUpdate(message(id = 1))
        dao.insertOrUpdate(message(id = 2, scheduled = true))
        dao.insertOrUpdate(message(id = 3))
        dao.insertRecycleBinEntry(RecycleBinMessage(id = 3, isMMS = false, deletedTS = 1))

        dao.deleteCachedMessages()

        assertThat(dao.getAll().map { it.id }).containsExactly(2L, 3L)
        assertThat(dao.getAllScheduledMessages().map { it.id }).containsExactly(2L)
        assertThat(dao.getAllRecycleBinMessages().map { it.id }).containsExactly(3L)
    }

    @Test
    fun smsAndMmsWithTheSameIdAreKeptApart() {
        dao.insertOrUpdate(message(id = 57, mms = false))
        dao.insertOrUpdate(message(id = 57, mms = true))

        assertThat(dao.getAll().map { it.key })
            .containsExactly(MessageKey(57, isMMS = false), MessageKey(57, isMMS = true))
    }

    @Test
    fun recyclingTheMmsLeavesTheSmsWithTheSameIdAlone() {
        dao.insertOrUpdate(message(id = 57, mms = false))
        dao.insertOrUpdate(message(id = 57, mms = true))

        dao.insertRecycleBinEntry(RecycleBinMessage(id = 57, isMMS = true, deletedTS = 1))

        assertThat(dao.getAllRecycleBinMessages().map { it.key }).containsExactly(MessageKey(57, isMMS = true))
        assertThat(dao.getNonRecycledThreadMessages(7).map { it.key }).containsExactly(MessageKey(57, isMMS = false))

        dao.deleteCachedMessages()
        assertThat(dao.getAll().map { it.key }).containsExactly(MessageKey(57, isMMS = true))
    }

    @Test
    fun keyedUpdatesTouchOnlyTheirProvider() {
        dao.insertOrUpdate(message(id = 57, mms = false).copy(read = false))
        dao.insertOrUpdate(message(id = 57, mms = true).copy(read = false))

        dao.markRead(57, isMMS = true)
        dao.updateType(57, isMMS = false, type = 5)
        dao.delete(57, isMMS = true)

        val left = dao.getAll()
        assertThat(left.map { it.key }).containsExactly(MessageKey(57, isMMS = false))
        assertThat(left.single().read).isFalse()
        assertThat(left.single().type).isEqualTo(5)
        assertThat(dao.getMessage(57, isMMS = false)).isNotNull()
        assertThat(dao.getMessage(57, isMMS = true)).isNull()
    }

    @Test
    fun scheduledHelpersNeverTouchAProviderMessageWithTheSameId() {
        dao.insertOrUpdate(message(id = 57, mms = false))
        dao.insertOrUpdate(message(id = 57, mms = true, scheduled = true))

        dao.updateScheduledType(57, type = 5)
        assertThat(dao.getMessage(57, isMMS = false)!!.type).isEqualTo(1)
        assertThat(dao.getMessage(57, isMMS = true)!!.type).isEqualTo(5)

        dao.deleteScheduledMessage(57)
        assertThat(dao.getAll().map { it.key }).containsExactly(MessageKey(57, isMMS = false))
    }

    private fun message(id: Long, scheduled: Boolean = false, mms: Boolean = false) = Message(
        id = id,
        body = "body $id",
        type = 1,
        status = -1,
        participants = ArrayList(),
        date = 1000,
        read = true,
        threadId = 7,
        isMMS = mms,
        attachment = null,
        senderPhoneNumber = "",
        senderName = "",
        senderPhotoUri = "",
        subscriptionId = 0,
        isScheduled = scheduled,
    )
}
