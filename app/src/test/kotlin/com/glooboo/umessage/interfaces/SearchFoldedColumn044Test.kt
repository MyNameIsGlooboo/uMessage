package com.glooboo.umessage.interfaces

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.databases.MessagesDatabase
import com.glooboo.umessage.helpers.MessageFoldBackfill
import com.glooboo.umessage.models.Message
import com.glooboo.umessage.models.RecycleBinMessage
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** PF2: search reads the saved `body_folded` column; rows written before it existed are still found, then filled. */
@RunWith(AndroidJUnit4::class)
class SearchFoldedColumn044Test {

    private lateinit var db: MessagesDatabase
    private val dao get() = db.MessagesDao()

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, MessagesDatabase::class.java).allowMainThreadQueries().build()
        MessageFoldBackfill.reset()
    }

    @After
    fun tearDown() {
        db.close()
        MessageFoldBackfill.reset()
    }

    private fun search(text: String) = dao.searchMessages(text).map { it.id }

    private fun blankFoldedColumn() = db.openHelper.writableDatabase.execSQL("UPDATE messages SET body_folded = ''")

    @Test
    fun insertSavesTheFoldedBody() {
        dao.insertOrUpdate(message(1, "علي ۱۲۳ ك‌تاب"))
        assertThat(dao.getMessage(1, false)?.bodyFolded).isEqualTo("علی 123 کتاب")
        assertThat(message(2, "x").copy(body = "ي").bodyFolded).isEqualTo("ی")
    }

    @Test
    fun foldedColumnFindsPersianArabicAndDigitVariants() {
        dao.insertOrUpdate(message(1, "کد تایید ۱۲۳۴"))
        dao.insertOrUpdate(message(2, "کد تاييد ١٢٣٤"))
        dao.insertOrUpdate(message(3, "other 5678"))
        assertThat(MessageFoldBackfill(dao).run()).isTrue()
        assertThat(search("1234").sorted()).containsExactly(1L, 2L)
        assertThat(search("۱۲۳۴").sorted()).containsExactly(1L, 2L)
        assertThat(search("تاييد").sorted()).containsExactly(1L, 2L)
        assertThat(search("5678")).containsExactly(3L)
    }

    @Test
    fun rowsNotBackfilledYetAreStillFoundAndNewRowsToo() {
        dao.insertOrUpdate(message(1, "کد ۱۲۳۴", date = 1_000))
        dao.insertOrUpdate(message(2, "کد ۱۲۳۴ دوم", date = 3_000))
        blankFoldedColumn() // as after the 14 -> 15 migration
        dao.insertOrUpdate(message(3, "کد ١٢٣٤ تازه", date = 2_000)) // arrives before the backfill ran
        assertThat(search("1234")).containsExactly(2L, 3L, 1L).inOrder()
    }

    @Test
    fun backfillIsInBatchesAndResumable() {
        (1..7).forEach { dao.insertOrUpdate(message(it.toLong(), "پیام ۹۹ شماره $it")) }
        blankFoldedColumn()
        // A stopped run (cancelled after its first batch) leaves the flag down and continues on the next run.
        var batches = 0
        assertThat(MessageFoldBackfill(dao, batchSize = 3).run(isCancelled = { batches++ >= 1 })).isFalse()
        assertThat(MessageFoldBackfill.isComplete).isFalse()
        assertThat(dao.getAll().count { it.bodyFolded.isNotEmpty() }).isEqualTo(3)
        assertThat(search("99")).hasSize(7)
        assertThat(MessageFoldBackfill(dao, batchSize = 3).run()).isTrue()
        assertThat(MessageFoldBackfill.isComplete).isTrue()
        assertThat(dao.getAll().all { it.bodyFolded.contains("99") }).isTrue()
        assertThat(search("99")).hasSize(7)
    }

    @Test
    fun recycledAndScheduledMessagesStayOutOfBothPaths() {
        dao.insertOrUpdate(message(1, "کد ۱۲۳"))
        dao.insertOrUpdate(message(2, "کد ۱۲۳ حذف"))
        dao.insertOrUpdate(message(3, "کد ۱۲۳ زمان‌بندی", scheduled = true))
        dao.insertRecycleBinEntry(RecycleBinMessage(2, false, 500))
        assertThat(search("123")).containsExactly(1L) // folded column
        blankFoldedColumn()
        assertThat(search("123")).containsExactly(1L) // old fold, before the backfill
    }

    @Test
    fun percentStaysLiteralInTheFoldedColumn() {
        dao.insertOrUpdate(message(1, "تخفیف ۵۰% امروز"))
        dao.insertOrUpdate(message(2, "تخفیف ۵۰ تومان"))
        assertThat(search("50%")).containsExactly(1L)
    }

    private fun message(id: Long, body: String, date: Int = 1000, scheduled: Boolean = false) = Message(
        id = id,
        body = body,
        type = 1,
        status = -1,
        participants = ArrayList(),
        date = date,
        read = true,
        threadId = 7,
        isMMS = false,
        attachment = null,
        senderPhoneNumber = "",
        senderName = "",
        senderPhotoUri = "",
        subscriptionId = 0,
        isScheduled = scheduled,
    )
}
