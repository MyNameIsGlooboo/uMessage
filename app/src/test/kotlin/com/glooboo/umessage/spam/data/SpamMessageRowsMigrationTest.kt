package com.glooboo.umessage.spam.data

import android.content.Context
import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.testing.migrationTestHelper
import com.glooboo.umessage.testing.offMainThread
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * spamless.db 8 to 9 with message rows (step J8): held texts and MMS of every media state, read and unread,
 * and queued incoming texts survive the migration column by column, and the marks of the message migration
 * (`migratedMsgId`, `migrateTries`) start empty. The rule merge has its own test (SpamProviderTruthMigrationTest).
 * The number is made up.
 */
@RunWith(AndroidJUnit4::class)
class SpamMessageRowsMigrationTest {

    @get:Rule
    val helper = migrationTestHelper(SpamDatabase::class.java)

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    @After
    fun deleteFiles() {
        context.getDatabasePath(TEST_DB).parentFile
            ?.listFiles { _, name -> name.startsWith(TEST_DB) }
            ?.forEach { it.delete() }
    }

    /**
     * Held rows in the column order of [HELD_COLUMNS]: a read SMS and an unread one, a kept MMS, an MMS whose content
     * was not kept and one still downloading; with and without a rule, on SIM 1, SIM 2 and no SIM.
     */
    private val held: List<List<Any?>> = listOf(
        listOf(1L, "held text", 1, 0, 1_000L, "UNKNOWN_SENDER", null, "NONE", null, 1),
        listOf(2L, "", 2, 1, 2_000L, "BLOCK_RULE", 1L, "STORED", "held-2.pdu", 0),
        listOf(3L, "picture caption", -1, 1, 3_000L, "BLOCK_RULE", 1L, "MISSING", null, 1),
        listOf(4L, "waiting picture", 1, 1, 4_000L, "UNKNOWN_SENDER", null, "DOWNLOADING", null, 0),
        listOf(5L, "older text", 1, 0, 5_000L, "BLOCK_RULE", 1L, "NONE", null, 0),
    )

    private fun SupportSQLiteDatabase.insertHeld(row: List<Any?>) = execSQL(
        "INSERT INTO blocked_messages (address, $HELD_COLUMNS) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        (listOf<Any?>(NUMBER) + row).toTypedArray(),
    )

    private fun SupportSQLiteDatabase.insertPending() = execSQL(
        "INSERT INTO pending_sms (id, address, subject, body, date, subscriptionId, status, createdAt, dateSent, " +
            "approved) VALUES (1, ?, '', 'queued text', 5000, 1, 0, 5001, 4999, 1)",
        arrayOf<Any?>(NUMBER),
    )

    private fun seedVersion8() {
        helper.createDatabase(TEST_DB, 8).use { db ->
            db.execSQL(
                "INSERT INTO spam_rules (id, kind, type, pattern, subscriptionId, note, createdAt, hitCount, " +
                    "lastHit) VALUES (1, 'BLOCK', 'NUMBER', ?, 1, '', 1, 3, 4000)",
                arrayOf<Any?>(NUMBER),
            )
            held.forEach { db.insertHeld(it) }
            db.insertPending()
        }
    }

    private fun Cursor.readHeld(): List<Any?> = listOf(
        getLong(0), getString(1), getInt(2), getInt(3), getLong(4), getString(5),
        if (isNull(6)) null else getLong(6), getString(7), if (isNull(8)) null else getString(8), getInt(9),
    )

    @Test
    fun heldMessagesSurviveColumnByColumn() {
        seedVersion8()
        helper.runMigrationsAndValidate(TEST_DB, 9, true, *SpamDatabase.ALL_MIGRATIONS).use { db ->
            val rows = db.query("SELECT $HELD_COLUMNS FROM blocked_messages ORDER BY id").use { c ->
                buildList { while (c.moveToNext()) add(c.readHeld()) }
            }
            assertThat(rows).containsExactlyElementsIn(held).inOrder()
            db.query("SELECT COUNT(*) FROM blocked_messages WHERE address = ?", arrayOf<Any?>(NUMBER)).use {
                assertThat(it.moveToFirst() && it.getInt(0) == held.size).isTrue()
            }
        }
    }

    @Test
    fun theMigrationMarksStartEmpty() {
        seedVersion8()
        helper.runMigrationsAndValidate(TEST_DB, 9, true, *SpamDatabase.ALL_MIGRATIONS).use { db ->
            db.query("SELECT migratedMsgId, migrateTries FROM blocked_messages").use { c ->
                var count = 0
                while (c.moveToNext()) {
                    assertThat(c.isNull(0)).isTrue()
                    assertThat(c.getInt(1)).isEqualTo(0)
                    count++
                }
                assertThat(count).isEqualTo(held.size)
            }
        }
    }

    @Test
    fun aQueuedIncomingTextSurvives() {
        seedVersion8()
        helper.runMigrationsAndValidate(TEST_DB, 9, true, *SpamDatabase.ALL_MIGRATIONS).use { db ->
            db.query("SELECT address, body, date, subscriptionId, createdAt, dateSent, approved FROM pending_sms")
                .use { c ->
                    assertThat(c.moveToNext()).isTrue()
                    assertThat(c.getString(0)).isEqualTo(NUMBER)
                    assertThat(c.getString(1)).isEqualTo("queued text")
                    assertThat(listOf(c.getLong(2), c.getLong(3), c.getLong(4), c.getLong(5), c.getLong(6)))
                        .containsExactly(5000L, 1L, 5001L, 4999L, 1L).inOrder()
                    assertThat(c.moveToNext()).isFalse()
                }
        }
    }

    /** Through Room, as the message migration reads them: every held row is still to move. */
    @Test
    fun theMigratedRowsReadThroughRoomAsNotMovedYet(): Unit = offMainThread {
        seedVersion8()
        val db = SpamDatabase.build(context, TEST_DB)
        try {
            val rows = db.spamDao().getAllBlocked()
            assertThat(rows.map { it.id }).containsExactly(1L, 2L, 3L, 4L, 5L)
            assertThat(rows.map { it.migratedMsgId }.toSet()).containsExactly(null)
        } finally {
            db.close()
        }
    }

    private companion object {
        const val TEST_DB = "message-rows-migration-test.db"
        const val NUMBER = "+15550001234"
        const val HELD_COLUMNS = "id, body, subscriptionId, isMms, receivedAt, reason, ruleId, media, mediaFile, unread"
    }
}
