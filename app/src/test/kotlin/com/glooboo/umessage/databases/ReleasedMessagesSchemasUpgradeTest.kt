package com.glooboo.umessage.databases

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.models.Draft
import com.glooboo.umessage.models.MessageKey
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
 * J13 (upgrade safety): conversations.db as each released version left it, straight to the current schema through the
 * app's own [MessagesDatabase.ALL_MIGRATIONS], then read through the app's DAOs. Released schemas: 13 (0.3.0), 14
 * (0.4.0 to 0.4.2), 15 (0.4.3 to 0.4.14). Every table that held user data at that version gets a realistic row: chats
 * (one archived with a custom title, one group), an SMS and an MMS sharing an id, a scheduled message, a recycled
 * message, a draft, a star, a repeat, a label, a reminder, a cached attachment and (14+) a note. Data is made up.
 */
@RunWith(AndroidJUnit4::class)
class ReleasedMessagesSchemasUpgradeTest {

    @get:Rule
    val helper = migrationTestHelper(MessagesDatabase::class.java)

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun deleteLeftovers() {
        context.deleteDatabase(TEST_DB)
    }

    @Test
    fun theSchemaOf030UpgradesStraightToCurrent() = upgradeFrom(13)

    @Test
    fun theSchemaOf040To042UpgradesStraightToCurrent() = upgradeFrom(14)

    @Test
    fun theSchemaOf043To0414UpgradesStraightToCurrent() = upgradeFrom(15)

    private fun upgradeFrom(version: Int) {
        helper.createDatabase(TEST_DB, version).use { seed(it, version) }

        helper.runMigrationsAndValidate(TEST_DB, MessagesDatabase.VERSION, true, *MessagesDatabase.ALL_MIGRATIONS)
            .use { checkRows(it, version) }

        val db = MessagesDatabase.build(context, TEST_DB)
        try {
            offMainThread { checkDaos(db, version) }
        } finally {
            db.close()
        }
    }

    private fun seed(db: SupportSQLiteDatabase, version: Int) {
        val chat = "INSERT INTO conversations (thread_id, snippet, date, read, title, photo_uri, " +
            "is_group_conversation, phone_number, is_scheduled, uses_custom_title, archived, unread_count) VALUES "
        db.execSQL("$chat (1, 'Lunch?', 100, 0, 'Sam', '', 0, '$NUMBER', 0, 0, 0, 2)")
        db.execSQL("$chat (2, 'Your parcel', 90, 1, 'My shop', '', 0, '$OTHER_NUMBER', 0, 1, 1, 0)")
        db.execSQL("$chat (3, 'Hi all', 80, 1, 'Sam, Alex', '', 1, '$NUMBER', 0, 0, 0, 0)")

        db.execSQL(message(10, mms = false, thread = 1, body = "Lunch?", date = 100, version = version))
        db.execSQL(message(10, mms = true, thread = 1, body = "Picture", date = 95, version = version))
        db.execSQL(message(11, mms = false, thread = 2, body = "Old parcel", date = 50, version = version))
        db.execSQL(message(SCHEDULED_ID, mms = false, thread = 1, body = "Later", date = 2_000, version, true))
        db.execSQL("INSERT INTO recycle_bin_messages (id, is_mms, deleted_ts) VALUES (11, 0, 500)")
        db.execSQL("INSERT INTO drafts (thread_id, body, date) VALUES (1, 'See you', 5)")
        db.execSQL(
            "INSERT INTO starred_messages (id, is_mms, thread_id, address, date, body_hash, starred_ts, " +
                "snapshot_body) VALUES (10, 0, 1, '$NUMBER', 100, 42, 200, 'Lunch?')"
        )
        db.execSQL(
            "INSERT INTO scheduled_repeats (message_id, rule, calendar, anchor_day, anchor_month) " +
                "VALUES ($SCHEDULED_ID, 'WEEKLY', 'JALALI', 3, 1)"
        )
        db.execSQL("INSERT INTO labels (id, name, color, sort_order) VALUES (1, 'Work', -16776961, 0)")
        db.execSQL("INSERT INTO conversation_labels (thread_id, label_id) VALUES (1, 1)")
        db.execSQL(
            "INSERT INTO message_reminders (id, message_id, is_mms, thread_id, remind_at, snapshot_body) " +
                "VALUES (1, 10, 0, 1, 3000, 'Lunch?')"
        )
        db.execSQL(
            "INSERT INTO attachments (id, message_id, uri_string, mimetype, width, height, filename) " +
                "VALUES (1, 10, 'content://mms/part/7', 'image/jpeg', 640, 480, 'photo.jpg')"
        )
        db.execSQL("INSERT INTO message_attachments (id, text, attachments) VALUES (10, '', '[]')")
        if (version >= NOTES_SINCE) {
            db.execSQL(
                "INSERT INTO message_notes (note_key, thread_id, message_date, note, created_at, updated_at) " +
                    "VALUES ('k1', 1, 100, 'Bring the keys', 1, 2)"
            )
        }
    }

    private fun message(
        id: Long, mms: Boolean, thread: Long, body: String, date: Long, version: Int, scheduled: Boolean = false,
    ): String {
        val folded = if (version >= FOLDED_SINCE) ", body_folded" else ""
        val foldedValue = if (version >= FOLDED_SINCE) ", '${body.lowercase()}'" else ""
        return "INSERT INTO messages (id, body, type, status, participants, date, read, thread_id, is_mms, " +
            "attachment, sender_phone_number, sender_name, sender_photo_uri, subscription_id, is_scheduled$folded) " +
            "VALUES ($id, '$body', 1, -1, '[]', $date, 1, $thread, ${if (mms) 1 else 0}, NULL, '$NUMBER', '', '', " +
            "1, ${if (scheduled) 1 else 0}$foldedValue)"
    }

    private fun checkRows(db: SupportSQLiteDatabase, version: Int) {
        assertThat(rows(db, "SELECT thread_id, title, archived, uses_custom_title, unread_count FROM conversations"))
            .containsExactly("1|Sam|0|0|2", "2|My shop|1|1|0", "3|Sam, Alex|0|0|0")
        val folded = if (version >= FOLDED_SINCE) "lunch?" else ""
        assertThat(rows(db, "SELECT id, is_mms, is_scheduled, body_folded FROM messages ORDER BY id, is_mms"))
            .containsExactly(
                "10|0|0|$folded", "10|1|0|${if (version >= FOLDED_SINCE) "picture" else ""}",
                "11|0|0|${if (version >= FOLDED_SINCE) "old parcel" else ""}",
                "$SCHEDULED_ID|0|1|${if (version >= FOLDED_SINCE) "later" else ""}",
            ).inOrder()
        assertThat(rows(db, "SELECT id, is_mms, deleted_ts FROM recycle_bin_messages")).containsExactly("11|0|500")
        assertThat(rows(db, "SELECT thread_id, body FROM drafts")).containsExactly("1|See you")
        assertThat(rows(db, "SELECT id, snapshot_body FROM starred_messages")).containsExactly("10|Lunch?")
        assertThat(rows(db, "SELECT message_id, rule FROM scheduled_repeats")).containsExactly("$SCHEDULED_ID|WEEKLY")
        assertThat(rows(db, "SELECT name FROM labels")).containsExactly("Work")
        assertThat(rows(db, "SELECT thread_id, label_id FROM conversation_labels")).containsExactly("1|1")
        assertThat(rows(db, "SELECT message_id, remind_at FROM message_reminders")).containsExactly("10|3000")
        assertThat(rows(db, "SELECT message_id, filename FROM attachments")).containsExactly("10|photo.jpg")
        assertThat(rows(db, "SELECT id FROM message_attachments")).containsExactly("10")
        val notes = if (version >= NOTES_SINCE) listOf("k1|Bring the keys") else emptyList()
        assertThat(rows(db, "SELECT note_key, note FROM message_notes")).containsExactlyElementsIn(notes)
        // Provider truth's table starts empty: the lists look as they did until the first sort.
        assertThat(rows(db, "SELECT COUNT(*) FROM spam_threads")).containsExactly("0")
    }

    private fun checkDaos(db: MessagesDatabase, version: Int) {
        val chats = db.ConversationsDao()
        assertThat(chats.getArchivedList().map { it.threadId }).containsExactly(2L)
        assertThat(chats.getSpamList()).isEmpty()
        assertThat(chats.getMessagesList().map { it.threadId })
            .containsExactlyElementsIn(chats.getAllRows().filterNot { it.isArchived }.map { it.threadId })

        val messages = db.MessagesDao()
        assertThat(messages.getAllRecycleBinMessages().map { it.key }).containsExactly(MessageKey(11, false))
        assertThat(messages.getAllScheduledMessages().map { it.body }).containsExactly("Later")
        assertThat(messages.getMessage(10, true)?.body).isEqualTo("Picture")
        assertThat(messages.getMessage(10, false)?.body).isEqualTo("Lunch?")

        assertThat(db.DraftsDao().getAll()).containsExactly(Draft(1, "See you", 5))
        runBlocking {
            assertThat(db.StarredMessagesDao().get(10, false))
                .isEqualTo(StarredMessage(10, false, 1, NUMBER, 100, 42, 200, "Lunch?"))
            assertThat(db.LabelsDao().getLabels().map { it.name }).containsExactly("Work")
            assertThat(db.LabelsDao().getThreadIds(1)).containsExactly(1L)
            assertThat(db.MessageRemindersDao().get(1)?.remindAt).isEqualTo(3000L)
            assertThat(db.ScheduledRepeatsDao().getAllRepeating().map { it.messageId }).containsExactly(SCHEDULED_ID)
            val note = db.MessageNotesDao().get("k1")
            val expected = if (version >= NOTES_SINCE) "Bring the keys" else null
            assertThat(note?.text).isEqualTo(expected)
        }
    }

    private fun rows(db: SupportSQLiteDatabase, sql: String): List<String> = db.query(sql).use { cursor ->
        buildList {
            while (cursor.moveToNext()) add((0 until cursor.columnCount).joinToString("|") { cursor.getString(it) })
        }
    }

    private companion object {
        const val TEST_DB = "released-messages-upgrade-test.db"
        const val NUMBER = "+15550001234"
        const val OTHER_NUMBER = "+15550001235"
        const val SCHEDULED_ID = 1_500_000_000L
        const val NOTES_SINCE = 14
        const val FOLDED_SINCE = 15
    }
}
