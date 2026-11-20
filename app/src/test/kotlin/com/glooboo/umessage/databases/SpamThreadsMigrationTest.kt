package com.glooboo.umessage.databases

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.spam.SpamThread
import com.glooboo.umessage.spam.engine.SpamCause
import com.glooboo.umessage.testing.migrationTestHelper
import com.glooboo.umessage.testing.offMainThread
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * conversations.db 15 to 16 (provider truth 3.2): the new spam_threads table, empty, and every row of a 0.4.14
 * database kept as it was, so the lists look the same until the first sort. Data is made up.
 */
@RunWith(AndroidJUnit4::class)
class SpamThreadsMigrationTest {

    @get:Rule
    val helper = migrationTestHelper(MessagesDatabase::class.java)

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun deleteLeftovers() {
        context.deleteDatabase(TEST_DB)
    }

    /** A 0.4.14 user's database: chats (one archived, one a group), messages, a scheduled one, a draft and a note. */
    private fun seed15(db: SupportSQLiteDatabase) {
        val chat = "INSERT INTO conversations (thread_id, snippet, date, read, title, photo_uri, " +
            "is_group_conversation, phone_number, is_scheduled, uses_custom_title, archived, unread_count) VALUES "
        db.execSQL("$chat (1, 'Lunch?', 100, 0, 'Sam', '', 0, '+15550001234', 0, 0, 0, 2)")
        db.execSQL("$chat (2, 'Your parcel', 90, 1, 'My shop', '', 0, '+15550001235', 0, 1, 1, 0)")
        db.execSQL("$chat (3, 'Hi all', 80, 1, 'Sam, Alex', '', 1, '+15550001234', 0, 0, 0, 0)")
        val message = "INSERT INTO messages (id, body, type, status, participants, date, read, thread_id, is_mms, " +
            "attachment, sender_phone_number, sender_name, sender_photo_uri, subscription_id, is_scheduled, " +
            "body_folded) VALUES "
        db.execSQL("$message (10, 'Lunch?', 1, -1, '[]', 100, 0, 1, 0, NULL, '+15550001234', '', '', 1, 0, 'lunch?')")
        db.execSQL("$message (10, 'Picture', 1, -1, '[]', 95, 1, 1, 1, NULL, '+15550001234', '', '', 1, 0, '')")
        db.execSQL("$message (1500000000, 'Later', 6, -1, '[]', 2000, 1, 1, 0, NULL, '', '', '', 1, 1, '')")
        db.execSQL("INSERT INTO drafts (thread_id, body, date) VALUES (1, 'See you', 5)")
        db.execSQL(
            "INSERT INTO message_notes (note_key, thread_id, message_date, note, created_at, updated_at) " +
                "VALUES ('k1', 1, 100, 'Bring the keys', 1, 1)"
        )
    }

    private fun rows(db: SupportSQLiteDatabase, sql: String): List<String> = db.query(sql).use { cursor ->
        buildList {
            while (cursor.moveToNext()) add((0 until cursor.columnCount).joinToString("|") { cursor.getString(it) })
        }
    }

    @Test
    fun theMigrationAddsAnEmptySpamTableAndKeepsEveryRow() {
        helper.createDatabase(TEST_DB, 15).use { seed15(it) }

        helper.runMigrationsAndValidate(TEST_DB, 16, true, *MessagesDatabase.ALL_MIGRATIONS).use { db ->
            assertThat(rows(db, "SELECT COUNT(*) FROM spam_threads")).containsExactly("0")
            val chats = "SELECT thread_id, title, archived, uses_custom_title, unread_count FROM conversations"
            assertThat(rows(db, chats)).containsExactly("1|Sam|0|0|2", "2|My shop|1|1|0", "3|Sam, Alex|0|0|0")
            assertThat(rows(db, "SELECT id, is_mms, is_scheduled FROM messages ORDER BY id, is_mms"))
                .containsExactly("10|0|0", "10|1|0", "1500000000|0|1").inOrder()
            assertThat(rows(db, "SELECT body FROM drafts")).containsExactly("See you")
            assertThat(rows(db, "SELECT note FROM message_notes")).containsExactly("Bring the keys")
        }
    }

    @Test
    fun theUpgradedDatabaseShowsTheSameListsUntilTheFirstSort() {
        helper.createDatabase(TEST_DB, 15).use { seed15(it) }

        val db = MessagesDatabase.build(context, TEST_DB)
        try {
            offMainThread {
                val dao = db.ConversationsDao()
                assertThat(dao.getMessagesList().map { it.threadId }).containsExactlyElementsIn(
                    dao.getNonArchived().map { it.threadId }
                )
                assertThat(dao.getArchivedList().map { it.threadId }).containsExactly(2L)
                assertThat(dao.getSpamList()).isEmpty()

                // The table works: a Spam row moves chat 2 (archived) from Archived to Spam, and the group stays.
                dao.insertSpamThread(SpamThread(2, "+15550001235", SpamCause.BLOCKED, spamSince = 7L))
                dao.insertSpamThread(SpamThread(3, "+15550001234", SpamCause.BLOCKED, spamSince = 7L))
                assertThat(dao.getArchivedList()).isEmpty()
                assertThat(dao.getSpamList().map { it.threadId }).containsExactly(2L)
                assertThat(dao.getMessagesList().map { it.threadId }).containsExactly(1L, 3L)
            }
        } finally {
            db.close()
        }
    }

    private companion object {
        const val TEST_DB = "spam-threads-migration-test.db"
    }
}
