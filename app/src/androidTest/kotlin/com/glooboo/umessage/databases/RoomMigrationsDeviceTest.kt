package com.glooboo.umessage.databases

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.glooboo.umessage.spam.data.SpamDatabase
import com.glooboo.umessage.testing.DeviceTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Spec 12.2, both Room migrations of provider truth with real files on the device's own SQLite: spamless.db 8 to 9 and
 * the messages database 15 to 16. Each file is made from its exported schema (app/schemas, in the debug assets),
 * filled like a 0.4.14 user's, migrated with the app's migrations and validated against the next exported schema,
 * then opened the way the app opens it. The JVM tests (SpamProviderTruthMigrationTest, SpamThreadsMigrationTest)
 * cover the details on Robolectric's SQLite; this checks the same files on Android's. Emulator only. Made-up data.
 */
@RunWith(AndroidJUnit4::class)
class RoomMigrationsDeviceTest {

    @get:Rule
    val spamHelper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), SpamDatabase::class.java)

    @get:Rule
    val messagesHelper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), MessagesDatabase::class.java)

    private val app: Context = DeviceTest.context

    @Before
    fun setUp() {
        DeviceTest.assumeEmulator()
        deleteFiles()
    }

    @After
    fun deleteFiles() {
        app.deleteDatabase(SPAM_DB)
        app.deleteDatabase(MESSAGES_DB)
    }

    private fun rows(db: SupportSQLiteDatabase, sql: String): List<String> = db.query(sql).use { c ->
        buildList {
            while (c.moveToNext()) add((0 until c.columnCount).joinToString("|") { c.getString(it) ?: "null" })
        }
    }

    @Test
    fun spamlessEightToNineKeepsEveryRowAndMergesSimDuplicates() {
        spamHelper.createDatabase(SPAM_DB, 8).use { db ->
            val rule = "INSERT INTO spam_rules (id, kind, type, pattern, subscriptionId, note, createdAt, hitCount, " +
                "lastHit) VALUES "
            // The same number blocked on SIM 1 and on SIM 2; a word on SIM 2 only; an allow for both SIMs.
            db.execSQL("$rule (1, 'BLOCK', 'NUMBER', '+15550001234', 1, '@spamless:blocked_by_you', 100, 3, 1000)")
            db.execSQL("$rule (2, 'BLOCK', 'NUMBER', '+15550001234', 2, '', 200, 4, 3000)")
            db.execSQL("$rule (3, 'BLOCK', 'KEYWORD', 'lottery', 2, '', 300, 0, 0)")
            db.execSQL("$rule (4, 'ALLOW', 'NUMBER', '+15550001235', NULL, '@spamless:allowed_by_you', 400, 0, 0)")
            val held = "INSERT INTO blocked_messages (id, address, body, subscriptionId, isMms, receivedAt, reason, " +
                "ruleId, media, mediaFile, unread) VALUES "
            db.execSQL("$held (1, '+15550001234', 'held text', 1, 0, 1000, 'BLOCK_RULE', 2, 'NONE', NULL, 1)")
            db.execSQL(
                "$held (2, '+15550001236', 'MMS blocked', 2, 1, 2000, 'UNKNOWN_SENDER', NULL, 'MISSING', NULL, 0)"
            )
            db.execSQL("$held (3, '+15550001237', 'kept', 1, 1, 3000, 'UNKNOWN_SENDER', NULL, 'STORED', 'f', 0)")
            db.execSQL(
                "INSERT INTO pending_sms (id, address, subject, body, date, subscriptionId, status, createdAt, " +
                    "dateSent, approved) VALUES (1, '+15550001234', '', 'queued', 5, 1, 0, 5, 0, 1)"
            )
            db.execSQL(
                "INSERT INTO forward_rules (id, senderType, senderPattern, subscriptionId, targets, createdAt) " +
                    "VALUES (1, 'NUMBER', '+15550001234', 2, '+15550009999', 1)"
            )
        }

        spamHelper.runMigrationsAndValidate(SPAM_DB, 9, true, *SpamDatabase.ALL_MIGRATIONS).use { db ->
            assertEquals(
                listOf(
                    "1|BLOCK|NUMBER|+15550001234|null|@spamless:blocked_by_you|100|7|3000",
                    "3|BLOCK|KEYWORD|lottery|null||300|0|0",
                    "4|ALLOW|NUMBER|+15550001235|null|@spamless:allowed_by_you|400|0|0",
                ),
                rows(
                    db,
                    "SELECT id, kind, type, pattern, subscriptionId, note, createdAt, hitCount, lastHit " +
                        "FROM spam_rules ORDER BY id",
                ),
            )
            // Held rows: all kept, the merged rule's id renamed, the migration marks empty.
            assertEquals(
                listOf("1|1|1|null|0", "2|null|0|null|0", "3|null|0|null|0"),
                rows(db, "SELECT id, ruleId, unread, migratedMsgId, migrateTries FROM blocked_messages ORDER BY id"),
            )
            assertEquals(listOf("1|1"), rows(db, "SELECT id, approved FROM pending_sms"))
            assertEquals(listOf("2"), rows(db, "SELECT subscriptionId FROM forward_rules"))
            assertEquals(listOf("0"), rows(db, "SELECT COUNT(*) FROM allow_group_members"))
        }

        // The app's own open of the migrated file: Room accepts it and the DAO reads the rows.
        val opened = SpamDatabase.build(app, SPAM_DB)
        try {
            assertEquals(3, opened.spamDao().getAllBlocked().size)
            assertTrue(opened.spamDao().getAllBlocked().all { it.migratedMsgId == null && it.migrateTries == 0 })
        } finally {
            opened.close()
        }
    }

    @Test
    fun messagesFifteenToSixteenAddsAnEmptySpamTableAndKeepsEveryRow() {
        messagesHelper.createDatabase(MESSAGES_DB, 15).use { db ->
            val chat = "INSERT INTO conversations (thread_id, snippet, date, read, title, photo_uri, " +
                "is_group_conversation, phone_number, is_scheduled, uses_custom_title, archived, unread_count) VALUES "
            db.execSQL("$chat (1, 'Lunch?', 100, 0, 'Sam', '', 0, '+15550001234', 0, 0, 0, 2)")
            db.execSQL("$chat (2, 'Your parcel', 90, 1, 'My shop', '', 0, '+15550001235', 0, 1, 1, 0)")
            db.execSQL("$chat (3, 'Hi all', 80, 1, 'Sam, Alex', '', 1, '+15550001234', 0, 0, 0, 0)")
            val message = "INSERT INTO messages (id, body, type, status, participants, date, read, thread_id, " +
                "is_mms, attachment, sender_phone_number, sender_name, sender_photo_uri, subscription_id, " +
                "is_scheduled, body_folded) VALUES "
            db.execSQL("$message (10, 'Lunch?', 1, -1, '[]', 100, 0, 1, 0, NULL, '+15550001234', '', '', 1, 0, '')")
            db.execSQL("$message (10, 'Picture', 1, -1, '[]', 95, 1, 1, 1, NULL, '+15550001234', '', '', 1, 0, '')")
            db.execSQL("INSERT INTO drafts (thread_id, body, date) VALUES (1, 'See you', 5)")
        }

        messagesHelper.runMigrationsAndValidate(MESSAGES_DB, 16, true, *MessagesDatabase.ALL_MIGRATIONS).use { db ->
            assertEquals(listOf("0"), rows(db, "SELECT COUNT(*) FROM spam_threads"))
            assertEquals(
                listOf("1|Sam|0|2", "2|My shop|1|0", "3|Sam, Alex|0|0"),
                rows(db, "SELECT thread_id, title, archived, unread_count FROM conversations ORDER BY thread_id"),
            )
            assertEquals(listOf("10|0", "10|1"), rows(db, "SELECT id, is_mms FROM messages ORDER BY id, is_mms"))
            assertEquals(listOf("See you"), rows(db, "SELECT body FROM drafts"))
        }

        // The app's own open: the lists read as before the update, Spam empty until the first sort.
        val opened = MessagesDatabase.build(app, MESSAGES_DB)
        try {
            val dao = opened.ConversationsDao()
            assertEquals(setOf(1L, 3L), dao.getMessagesList().map { it.threadId }.toSet())
            assertEquals(listOf(2L), dao.getArchivedList().map { it.threadId })
            assertTrue(dao.getSpamList().isEmpty())
        } finally {
            opened.close()
        }
    }

    private companion object {
        const val SPAM_DB = "j10-room-spamless.db"
        const val MESSAGES_DB = "j10-room-messages.db"
    }
}
