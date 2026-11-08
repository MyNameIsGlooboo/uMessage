package com.glooboo.umessage.databases

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.sqlite.db.SupportSQLiteDatabase
import com.glooboo.umessage.helpers.MessageFoldBackfill
import com.glooboo.umessage.models.ConversationLabel
import com.glooboo.umessage.models.Draft
import com.glooboo.umessage.models.Label
import com.glooboo.umessage.models.MessageKey
import com.glooboo.umessage.models.MessageNote
import com.glooboo.umessage.models.MessageReminder
import com.glooboo.umessage.models.ScheduledRepeat
import com.glooboo.umessage.models.StarredMessage
import com.glooboo.umessage.testing.migrationTestHelper
import com.glooboo.umessage.testing.offMainThread
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Upgrade and downgrade tests for conversations.db.
 *
 * Exported schemas exist only for versions 8 (restored from the upstream Fossify history), 11, 12, 13, 14 and 15
 * (current), so the migrations 1..7 cannot be exercised here: there is no record of what those databases looked
 * like. Room's own validation after the run covers 8 -> 9 -> 10 -> 11 -> 12 -> 13 -> 14 -> 15.
 */
@RunWith(AndroidJUnit4::class)
class MessagesDatabaseMigrationTest {

    @get:Rule
    val helper = migrationTestHelper(MessagesDatabase::class.java)

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** The helper deletes only the main file, so a WAL left by an earlier test would be replayed into the new one. */
    @Before
    fun deleteLeftovers() {
        context.deleteDatabase(TEST_DB)
    }

    @Test
    fun migratesFromOldestExportedSchemaToLatestAndKeepsRows() {
        helper.createDatabase(TEST_DB, OLDEST_EXPORTED).use { db ->
            db.execSQL(
                "INSERT INTO conversations (thread_id, snippet, date, read, title, photo_uri, is_group_conversation, " +
                    "phone_number, is_scheduled, uses_custom_title, archived) " +
                    "VALUES (7, 'hello', 1000, 0, 'Title', '', 0, '5550100', 0, 0, 0)"
            )
        }

        helper.runMigrationsAndValidate(TEST_DB, LATEST, true, *MessagesDatabase.ALL_MIGRATIONS).use { db ->
            db.query("SELECT snippet, unread_count FROM conversations WHERE thread_id = 7").use { cursor ->
                assertThat(cursor.moveToFirst()).isTrue()
                assertThat(cursor.getString(0)).isEqualTo("hello")
                assertThat(cursor.getInt(1)).isEqualTo(0)
            }
            db.query("SELECT COUNT(*) FROM drafts").use { cursor ->
                assertThat(cursor.moveToFirst()).isTrue()
                assertThat(cursor.getInt(0)).isEqualTo(0)
            }
        }
    }

    @Test
    fun migrationToTwelveKeepsEveryRowAndLetsSmsAndMmsShareAnId() {
        helper.createDatabase(TEST_DB, 11).use { db ->
            db.execSQL(insertMessage(id = 57, mms = false, scheduled = false, body = "sms 57"))
            db.execSQL(insertMessage(id = 58, mms = true, scheduled = false, body = "mms 58"))
            db.execSQL(insertMessage(id = 1_234_567, mms = false, scheduled = true, body = "scheduled"))
            db.execSQL("INSERT INTO recycle_bin_messages (id, deleted_ts) VALUES (58, 500)")
            db.execSQL("INSERT INTO recycle_bin_messages (id, deleted_ts) VALUES (999, 600)") // orphan entry
            db.execSQL("INSERT INTO drafts (thread_id, body, date) VALUES (7, 'draft', 1)")
            db.execSQL(
                "INSERT INTO conversations (thread_id, snippet, date, read, title, photo_uri, is_group_conversation, " +
                    "phone_number, is_scheduled, uses_custom_title, archived, unread_count) " +
                    "VALUES (7, 'hello', 1000, 0, 'Title', '', 0, '5550100', 0, 0, 0, 0)"
            )
        }

        helper.runMigrationsAndValidate(TEST_DB, LATEST, true, *MessagesDatabase.ALL_MIGRATIONS).use { db ->
            // What used to collide: an MMS with the SMS's id, and an SMS with the recycled MMS's id.
            db.execSQL(insertMessage(id = 57, mms = true, scheduled = false, body = "mms 57"))
            db.execSQL(insertMessage(id = 58, mms = false, scheduled = false, body = "sms 58"))

            assertThat(rows(db, "SELECT id, is_mms, is_scheduled, body FROM messages ORDER BY id, is_mms"))
                .containsExactly(
                    "57|0|0|sms 57", "57|1|0|mms 57", "58|0|0|sms 58", "58|1|0|mms 58", "1234567|0|1|scheduled"
                ).inOrder()
            assertThat(rows(db, "SELECT id, is_mms, deleted_ts FROM recycle_bin_messages ORDER BY id"))
                .containsExactly("58|1|500", "999|0|600").inOrder()
            assertThat(rows(db, "SELECT thread_id, body FROM drafts")).containsExactly("7|draft")
            assertThat(rows(db, "SELECT thread_id, snippet FROM conversations")).containsExactly("7|hello")
        }

        // And the app's DAO sees them as distinct messages: only MMS 58 is in the recycle bin.
        val db = MessagesDatabase.build(context, TEST_DB)
        try {
            offMainThread {
                val dao = db.MessagesDao()
                assertThat(dao.getAllRecycleBinMessages().map { it.key }).containsExactly(MessageKey(58, true))
                assertThat(dao.getAllScheduledMessages().map { it.body }).containsExactly("scheduled")
                assertThat(dao.getNonRecycledThreadMessages(7).map { it.body })
                    .containsExactly("sms 57", "mms 57", "sms 58", "scheduled")
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun migrationToThirteenAddsTheNewTablesAndKeepsExistingRows() {
        helper.createDatabase(TEST_DB, 12).use { db ->
            db.execSQL(insertMessage(id = 57, mms = false, scheduled = false, body = "sms 57"))
            db.execSQL("INSERT INTO drafts (thread_id, body, date) VALUES (7, 'draft', 1)")
        }

        helper.runMigrationsAndValidate(TEST_DB, LATEST, true, *MessagesDatabase.ALL_MIGRATIONS).use { db ->
            assertThat(rows(db, "SELECT id, body FROM messages")).containsExactly("57|sms 57")
            assertThat(rows(db, "SELECT thread_id, body FROM drafts")).containsExactly("7|draft")
            for (table in listOf(
                "starred_messages", "scheduled_repeats", "labels", "conversation_labels", "message_reminders"
            )) {
                assertThat(rows(db, "SELECT COUNT(*) FROM $table")).containsExactly("0")
            }
        }

        val db = MessagesDatabase.build(context, TEST_DB)
        try {
            offMainThread {
                val star = StarredMessage(57, false, 7, "5550100", 1000, 42, 2000, "sms 57")
                runBlocking {
                    db.StarredMessagesDao().insert(star)
                    assertThat(db.StarredMessagesDao().get(57, false)).isEqualTo(star)
                    assertThat(db.StarredMessagesDao().get(57, true)).isNull()

                    val label = Label(name = "Work", color = 0xFF0000FF.toInt(), sortOrder = 0)
                    val labelId = db.LabelsDao().insertLabel(label)
                    db.LabelsDao().addToConversation(ConversationLabel(7, labelId))
                    assertThat(db.LabelsDao().getThreadIds(labelId)).containsExactly(7L)

                    val reminderId = db.MessageRemindersDao().insert(MessageReminder(0, 57, false, 7, 3000, "sms 57"))
                    assertThat(db.MessageRemindersDao().get(reminderId)?.remindAt).isEqualTo(3000)

                    db.ScheduledRepeatsDao().insertOrUpdate(ScheduledRepeat(99, "WEEKLY", "JALALI", 3, 1))
                    assertThat(db.ScheduledRepeatsDao().getAllRepeating().map { it.messageId }).containsExactly(99L)
                }
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun migrationToFourteenAddsTheNotesTableAndKeepsExistingRows() {
        helper.createDatabase(TEST_DB, 13).use { db ->
            db.execSQL(insertMessage(id = 57, mms = false, scheduled = false, body = "sms 57"))
            db.execSQL("INSERT INTO starred_messages VALUES (57, 0, 7, '5550100', 1000, 42, 2000, 'sms 57')")
        }

        helper.runMigrationsAndValidate(TEST_DB, LATEST, true, *MessagesDatabase.ALL_MIGRATIONS).use { db ->
            assertThat(rows(db, "SELECT id, body FROM messages")).containsExactly("57|sms 57")
            assertThat(rows(db, "SELECT id FROM starred_messages")).containsExactly("57")
            assertThat(rows(db, "SELECT COUNT(*) FROM message_notes")).containsExactly("0")
            assertThat(rows(db, "SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = 'message_notes'"))
                .contains("index_message_notes_thread_id")
        }

        val db = MessagesDatabase.build(context, TEST_DB)
        try {
            offMainThread {
                val note = MessageNote("v1|s|r|1000|5550100|2a", 7, 1000, "rent", 1, 2)
                runBlocking {
                    db.MessageNotesDao().upsert(note)
                    assertThat(db.MessageNotesDao().get(note.key)).isEqualTo(note)
                    assertThat(db.MessageNotesDao().getThreadIds()).containsExactly(7L)
                }
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun migrationToFifteenAddsAnEmptyFoldedBodyColumnAndKeepsEveryRow() {
        helper.createDatabase(TEST_DB, 14).use { db ->
            db.execSQL(insertMessage(id = 57, mms = false, scheduled = false, body = "sms 57"))
            db.execSQL(insertMessage(id = 58, mms = true, scheduled = false, body = "علي ۱۲۳"))
        }

        helper.runMigrationsAndValidate(TEST_DB, LATEST, true, *MessagesDatabase.ALL_MIGRATIONS).use { db ->
            assertThat(rows(db, "SELECT id, is_mms, body, body_folded FROM messages ORDER BY id"))
                .containsExactly("57|0|sms 57|", "58|1|علي ۱۲۳|").inOrder()
            // No index on the new column: LIKE '%x%' cannot use one.
            assertThat(
                rows(
                    db,
                    "SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = 'messages' " +
                        "AND name NOT LIKE 'sqlite_autoindex%'",
                ),
            ).containsExactly("index_messages_thread_id_date")
        }

        // The app's DAO reads the old rows, the backfill fills them and search finds the Persian variants.
        MessageFoldBackfill.reset()
        val db = MessagesDatabase.build(context, TEST_DB)
        try {
            offMainThread {
                val dao = db.MessagesDao()
                assertThat(dao.getAll().map { it.bodyFolded }).containsExactly("", "")
                assertThat(dao.searchMessages("۱۲۳").map { it.id }).containsExactly(58L) // old rows: fallback fold
                assertThat(MessageFoldBackfill(dao).run()).isTrue()
                assertThat(dao.getMessage(58, true)?.bodyFolded).isEqualTo("علی 123")
                assertThat(dao.getMessage(57, false)?.bodyFolded).isEqualTo("sms 57")
                assertThat(dao.searchMessages("123").map { it.id }).containsExactly(58L)
                assertThat(dao.searchMessages("علی").map { it.id }).containsExactly(58L)
            }
        } finally {
            db.close()
            MessageFoldBackfill.reset()
        }
    }

    @Test
    fun appBuilderOpensAnUpgradedDatabase() {
        helper.createDatabase(TEST_DB, OLDEST_EXPORTED).close()

        val db = MessagesDatabase.build(context, TEST_DB)
        try {
            offMainThread {
                db.DraftsDao().insertOrUpdate(Draft(threadId = 1, body = "draft", date = 1))
                assertThat(db.DraftsDao().getAll()).containsExactly(Draft(1, "draft", 1))
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun downgradeStartsEmptyAndKeepsTheNewerFileAside() {
        val db = MessagesDatabase.build(context, TEST_DB)
        offMainThread { db.DraftsDao().insertOrUpdate(Draft(threadId = 1, body = "draft", date = 1)) }
        db.close()

        // Pretend a newer build wrote this file and the user then sideloaded the current one.
        SQLiteDatabase.openDatabase(context.getDatabasePath(TEST_DB).path, null, SQLiteDatabase.OPEN_READWRITE)
            .use { it.version = LATEST + 1 }

        // Policy (MessagesDatabaseRecovery): no crash, an empty cache, and the newer file kept for the next upgrade.
        val reopened = MessagesDatabase.build(context, TEST_DB)
        try {
            assertThat(offMainThread { reopened.DraftsDao().getAll() }).isEmpty()
        } finally {
            reopened.close()
        }
        val backup = MessagesDatabase.downgradeBackup(context, TEST_DB, LATEST + 1)
        assertThat(backup.exists()).isTrue()
        DatabaseBackups.deleteWithCompanions(backup)
    }

    @Test
    fun aBackupFromAnOlderSchemaIsMigratedBeforeItsRowsAreRestored() {
        // A version-11 file, kept aside by a build older than it (so its rows were never migrated).
        helper.createDatabase(BACKUP_SOURCE_DB, 11).use { db ->
            db.execSQL(insertMessage(id = 1_234_567, mms = true, scheduled = true, body = "scheduled"))
            db.execSQL(insertMessage(id = 58, mms = true, scheduled = false, body = "recycled mms"))
            db.execSQL(insertMessage(id = 59, mms = false, scheduled = false, body = "cached sms"))
            db.execSQL("INSERT INTO recycle_bin_messages (id, deleted_ts) VALUES (58, 500)")
        }
        val backup = MessagesDatabase.downgradeBackup(context, TEST_DB, 11)
        DatabaseBackups.copyWithCompanions(context.getDatabasePath(BACKUP_SOURCE_DB), backup)
        context.deleteDatabase(BACKUP_SOURCE_DB)

        val db = MessagesDatabase.build(context, TEST_DB)
        try {
            offMainThread {
                val dao = db.MessagesDao()
                assertThat(dao.getAll().map { it.body }).containsExactly("scheduled", "recycled mms")
                assertThat(dao.getAllRecycleBinMessages().map { it.key }).containsExactly(MessageKey(58, true))
            }
        } finally {
            db.close()
        }
        assertThat(backup.exists()).isFalse()
        DatabaseBackups.deleteWithCompanions(java.io.File(backup.path.removeSuffix("-backup") + "-restored"))
    }

    private fun insertMessage(id: Long, mms: Boolean, scheduled: Boolean, body: String) =
        "INSERT INTO messages (id, body, type, status, participants, date, read, thread_id, is_mms, attachment, " +
            "sender_phone_number, sender_name, sender_photo_uri, subscription_id, is_scheduled) " +
            "VALUES ($id, '$body', 1, -1, '[]', 1000, 1, 7, ${if (mms) 1 else 0}, NULL, '', '', '', 0, " +
            "${if (scheduled) 1 else 0})"

    private fun rows(db: SupportSQLiteDatabase, sql: String): List<String> = db.query(sql).use { cursor ->
        buildList {
            while (cursor.moveToNext()) add((0 until cursor.columnCount).joinToString("|") { cursor.getString(it) })
        }
    }

    private companion object {
        const val TEST_DB = "messages-migration-test.db"
        const val BACKUP_SOURCE_DB = "messages-migration-backup-source.db"
        const val OLDEST_EXPORTED = 8
        const val LATEST = MessagesDatabase.VERSION
    }
}
