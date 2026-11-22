package com.glooboo.umessage.databases

import android.app.ActivityManager
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.models.Draft
import com.glooboo.umessage.models.MessageNote
import com.glooboo.umessage.spam.AllowGroup
import com.glooboo.umessage.spam.DecisionReason
import com.glooboo.umessage.spam.MemberSource
import com.glooboo.umessage.spam.data.AllowGroupMemberEntity
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
import org.robolectric.Shadows.shadowOf
import java.io.File

/**
 * J14 (emulator Run 13): the open that restores a downgrade backup must leave Room's invalidation tracker working.
 * The restore used to ATTACH the backup on Room's own connection; Android answers the first ATTACH by turning WAL off,
 * which replaces that connection and drops Room's temp table `room_table_modification_log`, so the first observed
 * query crashed with "no such table". Each test opens the database the way the app does, with a backup in place,
 * checks that Room's connection kept its journal mode (J14-1: the ATTACH turned WAL off), observes a table the restore
 * wrote, and then writes to it again to see the observer fire. Every value is made up.
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

    private fun held(receivedAt: Long, mark: Long? = null) = BlockedMessageEntity(
        address = SENDER, body = "held $receivedAt", subscriptionId = 1, isMms = false, receivedAt = receivedAt,
        reason = DecisionReason.UNKNOWN_SENDER, ruleId = null, migratedMsgId = mark,
    )

    private fun spamFile(name: String, block: (SpamDatabase) -> Unit = {}) {
        val db = SpamDatabase.build(context, name)
        try {
            offMainThread {
                db.openHelper.writableDatabase
                block(db)
            }
        } finally {
            db.close()
        }
    }

    private fun walEnabled(db: RoomDatabase) =
        offMainThread { db.openHelper.writableDatabase.isWriteAheadLoggingEnabled }

    /**
     * Collects the held rows, writes one more row after the first emission and waits for the observer to see it.
     * Returns the first emission (what the open left) and the one with the new row.
     */
    private fun SpamDatabase.observeAndWriteAgain(): Pair<List<BlockedMessageEntity>, List<BlockedMessageEntity>> {
        val dao = spamDao()
        var first: List<BlockedMessageEntity>? = null
        val last = runBlocking {
            withTimeout(TIMEOUT_MS) {
                dao.observeBlocked()
                    .onEach { rows ->
                        if (first == null) {
                            first = rows
                            withContext(Dispatchers.IO) { dao.insertBlocked(held(receivedAt = LIVE_AT)) }
                        }
                    }
                    .first { rows -> rows.any { it.receivedAt == LIVE_AT } }
            }
        }
        return checkNotNull(first) to last
    }

    /** The conversations.db restore of Run 13, in WAL mode or (low-RAM device, J14-5) in rollback-journal mode. */
    private fun restoreConversations(lowRam: Boolean) {
        val activityManager = shadowOf(checkNotNull(context.getSystemService(ActivityManager::class.java)))
        activityManager.setIsLowRamDevice(lowRam)
        try {
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
                // Room picks WAL, or TRUNCATE on a low-RAM device; the restore must leave that as it is.
                assertThat(walEnabled(db)).isEqualTo(!lowRam)
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
        } finally {
            activityManager.setIsLowRamDevice(false)
        }
    }

    @Test
    fun conversationsDbRestoreKeepsTheTrackerAndBringsTheRowsBack() = restoreConversations(lowRam = false)

    @Test
    fun conversationsDbRestoreAlsoWorksInRollbackJournalMode() = restoreConversations(lowRam = true)

    @Test
    fun spamlessDbRestoreFromABackupKeepsTheTracker() {
        spamFile(TEST_DB)
        spamFile(OTHER_DB) { it.spamDao().insertBlocked(held(receivedAt = 1)) }
        val backup = DatabaseBackups.backupFile(context, TEST_DB, SpamDatabase.VERSION)
        DatabaseBackups.copyWithCompanions(context.getDatabasePath(OTHER_DB), backup)

        val db = SpamDatabase.build(context, TEST_DB)
        try {
            assertThat(walEnabled(db)).isTrue()
            val (first, last) = db.observeAndWriteAgain()
            assertThat(first.map { it.receivedAt }).containsExactly(1L)
            assertThat(last.map { it.receivedAt }).containsExactly(LIVE_AT, 1L)
            assertThat(backup.exists()).isFalse()
        } finally {
            db.close()
        }
    }

    /** J14-2: the spamless.db path of Run 13, the `-imported` file 0.4.14 kept aside (members and marks). */
    @Test
    fun spamlessDbRestoreFromTheImportedFileKeepsTheTracker() {
        spamFile(OTHER_DB) { db ->
            db.spamDao().insertMember(
                AllowGroupMemberEntity(AllowGroup.CODE, SENDER, SENDER, 7L, MemberSource.LIVE, acknowledged = true)
            )
            db.spamDao().insertBlocked(held(receivedAt = 1, mark = MARK))
        }
        // 0.4.14's copy, upgraded again: the row without its mark, no member.
        spamFile(TEST_DB) { it.spamDao().insertBlocked(held(receivedAt = 1)) }
        val imported = importedFile()
        DatabaseBackups.copyWithCompanions(context.getDatabasePath(OTHER_DB), imported)

        val db = SpamDatabase.build(context, TEST_DB)
        try {
            assertThat(walEnabled(db)).isTrue()
            val (first, last) = db.observeAndWriteAgain()
            assertThat(first.map { it.receivedAt to it.migratedMsgId }).containsExactly(1L to MARK)
            assertThat(last.map { it.receivedAt }).containsExactly(LIVE_AT, 1L)
            assertThat(offMainThread { db.spamDao().getMembers().map { it.senderKey } }).containsExactly(SENDER)
            assertThat(imported.exists()).isFalse()
        } finally {
            db.close()
        }
    }

    /** J14-2: a file of a newer schema is moved aside and imported (importNewer attaches the backup itself). */
    @Test
    fun spamlessDbImportOfANewerFileKeepsTheTracker() {
        spamFile(TEST_DB) { it.spamDao().insertBlocked(held(receivedAt = 1)) }
        SQLiteDatabase.openDatabase(context.getDatabasePath(TEST_DB).path, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.version = SpamDatabase.VERSION + 1
        }

        val db = SpamDatabase.build(context, TEST_DB)
        try {
            assertThat(walEnabled(db)).isTrue()
            val (first, last) = db.observeAndWriteAgain()
            assertThat(first.map { it.receivedAt }).containsExactly(1L)
            assertThat(last.map { it.receivedAt }).containsExactly(LIVE_AT, 1L)
            val newer = context.getDatabasePath(TEST_DB).path + ".v${SpamDatabase.VERSION + 1}"
            assertThat(File("$newer-imported").exists()).isTrue()
            assertThat(File("$newer-backup").exists()).isFalse()
        } finally {
            db.close()
        }
    }

    /**
     * J14-3: an `-imported` file whose restore fails is kept for the next open, and after
     * [RecoveryAttempts.MAX_FAILURES] failed opens it is kept as `-failed` and not tried again.
     */
    @Test
    fun anImportedFileWhoseRestoreFailsIsKeptAndRetiredAfterFiveTries() {
        spamFile(TEST_DB)
        val imported = importedFile()
        imported.writeText("not a database")

        repeat(RecoveryAttempts.MAX_FAILURES - 1) {
            spamFile(TEST_DB)
            assertThat(imported.exists()).isTrue()
        }
        spamFile(TEST_DB)

        assertThat(imported.exists()).isFalse()
        assertThat(File(imported.path.removeSuffix("-imported") + "-failed").exists()).isTrue()
    }

    private fun importedFile() = File(context.getDatabasePath(TEST_DB).path + ".v${SpamDatabase.VERSION}-imported")

    private companion object {
        const val TEST_DB = "recovery-tracker-test.db"
        const val OTHER_DB = "recovery-tracker-other.db"
        const val THREAD = 7L
        const val LIVE_THREAD = 8L
        const val LIVE_AT = 2L
        const val MARK = 55L
        const val SENDER = "+15550001234"
        const val TIMEOUT_MS = 10_000L
    }
}
