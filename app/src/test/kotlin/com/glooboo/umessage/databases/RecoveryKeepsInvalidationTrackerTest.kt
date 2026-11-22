package com.glooboo.umessage.databases

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.models.Draft
import com.glooboo.umessage.models.MessageNote
import com.glooboo.umessage.spam.DecisionReason
import com.glooboo.umessage.spam.data.BlockedMessageEntity
import com.glooboo.umessage.spam.data.SpamDatabase
import com.glooboo.umessage.testing.offMainThread
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * J14 (emulator Run 13): the open that restores a downgrade backup must leave Room's invalidation tracker working.
 * The restore used to ATTACH the backup on Room's own connection; Android answers the first ATTACH by turning WAL off,
 * which replaces that connection and drops Room's temp table `room_table_modification_log`, so the first observed
 * query crashed with "no such table". Each test opens the database the way the app does, with a backup in place,
 * observes a table the restore wrote, and then writes to it again to see the observer fire. Every value is made up.
 */
@RunWith(AndroidJUnit4::class)
class RecoveryKeepsInvalidationTrackerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    @After
    fun deleteFiles() {
        context.getDatabasePath(TEST_DB).parentFile?.listFiles { _, name ->
            name.startsWith(TEST_DB) || name.startsWith(OTHER_DB)
        }?.forEach { it.delete() }
        context.getSharedPreferences("database_recovery_attempts", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun note(threadId: Long) = MessageNote(
        key = "note-$threadId", threadId = threadId, messageDate = 1, text = "kept note", createdAt = 1, updatedAt = 1,
    )

    private fun held(receivedAt: Long) = BlockedMessageEntity(
        address = SENDER, body = "held", subscriptionId = 1, isMms = false, receivedAt = receivedAt,
        reason = DecisionReason.UNKNOWN_SENDER, ruleId = null,
    )

    @Test
    fun conversationsDbRestoreKeepsTheTrackerAndBringsTheRowsBack() {
        MessagesDatabase.build(context, TEST_DB).apply { offMainThread { openHelper.writableDatabase } }.close()
        val older = MessagesDatabase.build(context, OTHER_DB)
        try {
            offMainThread {
                older.DraftsDao().insertOrUpdate(Draft(threadId = THREAD, body = "kept draft", date = 1))
                runBlocking { older.MessageNotesDao().upsert(note(THREAD)) }
            }
        } finally {
            older.close()
        }
        val backup = MessagesDatabase.downgradeBackup(context, TEST_DB, MessagesDatabase.VERSION)
        DatabaseBackups.copyWithCompanions(context.getDatabasePath(OTHER_DB), backup)

        val db = MessagesDatabase.build(context, TEST_DB)
        try {
            val dao = db.MessageNotesDao()
            val seen = runBlocking {
                withTimeout(TIMEOUT_MS) {
                    dao.observeThreadIds()
                        .onEach { if (it == listOf(THREAD)) dao.upsert(note(LIVE_THREAD)) }
                        .first { LIVE_THREAD in it }
                }
            }
            assertThat(seen).containsExactly(THREAD, LIVE_THREAD)
            assertThat(offMainThread { db.DraftsDao().getAll().map { it.body } }).containsExactly("kept draft")
            assertThat(backup.exists()).isFalse()
        } finally {
            db.close()
        }
    }

    @Test
    fun spamlessDbRestoreKeepsTheTrackerAndBringsTheRowsBack() {
        SpamDatabase.build(context, TEST_DB).apply { offMainThread { openHelper.writableDatabase } }.close()
        val older = SpamDatabase.build(context, OTHER_DB)
        try {
            offMainThread { older.spamDao().insertBlocked(held(receivedAt = 1)) }
        } finally {
            older.close()
        }
        val backup = DatabaseBackups.backupFile(context, TEST_DB, SpamDatabase.VERSION)
        DatabaseBackups.copyWithCompanions(context.getDatabasePath(OTHER_DB), backup)

        val db = SpamDatabase.build(context, TEST_DB)
        try {
            val dao = db.spamDao()
            val seen = runBlocking {
                withTimeout(TIMEOUT_MS) {
                    dao.observeBlocked()
                        .onEach { rows ->
                            if (rows.size == 1) withContext(Dispatchers.IO) { dao.insertBlocked(held(receivedAt = 2)) }
                        }
                        .first { it.size == 2 }
                }
            }
            assertThat(seen.map { it.receivedAt }).containsExactly(2L, 1L)
            assertThat(backup.exists()).isFalse()
        } finally {
            db.close()
        }
    }

    private companion object {
        const val TEST_DB = "recovery-tracker-test.db"
        const val OTHER_DB = "recovery-tracker-other.db"
        const val THREAD = 7L
        const val LIVE_THREAD = 8L
        const val SENDER = "+15550001234"
        const val TIMEOUT_MS = 10_000L
    }
}
