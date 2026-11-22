package com.glooboo.umessage.databases

import android.content.Context
import android.util.Log
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Downgrade policy of conversations.db.
 *
 * Most of this database is a cache of the telephony provider and is rebuilt from it. Only scheduled messages (with
 * the temporary threads that hold them), the recycle bin, drafts, stars, repeat rules, labels, reminders and
 * notes exist nowhere else.
 *
 * - Downgrade (the file was written by a newer schema): [MessagesDatabase.build] moves the file aside as
 *   `conversations.db.v<N>-backup` and the older build starts from an empty database; the cache refills from the
 *   provider. It never crashes on open.
 * - Upgrade again (this callback, on every open): when a backup of schema N <= [MessagesDatabase.VERSION] exists,
 *   a copy of the newest one is migrated to the current schema and its scheduled messages, temporary threads,
 *   recycle-bin entries (with their messages), drafts, stars, repeat rules, labels (with their conversation links),
 *   reminders and notes are added wherever the live database lacks them; the live rows always win. The backup is then
 *   renamed
 *   `-restored`, so it is used once. App start re-arms the alarms of the restored scheduled messages
 *   (rescheduleAllScheduledMessages).
 *
 * A failed restore is logged and leaves the backup for the next open; it never stops the database from opening.
 *
 * Not copied, by the lead's decision of 22 Nov 2026 (B-23; step J plan, decision L6): `spam_threads`. After a 16 to
 * 15 to 16 trip the app-start sort adds the Spam rows again, but with no unread count and `spam_since` set to the time
 * of that sort, so auto-delete never removes the texts those chats received before that sort.
 * MessagesDatabaseRecoverySpamThreadsTest pins this.
 */
internal class MessagesDatabaseRecovery(private val context: Context, private val name: String) :
    RoomDatabase.Callback() {

    /**
     * Never runs SQL on [db], Room's own connection: the restore opens a connection of its own
     * ([DatabaseBackups.withAttached]), so whatever it does or throws cannot touch the invalidation table Room set up
     * on [db] just before this callback (J14). Every failure is caught and leaves the backup for the next open.
     */
    override fun onOpen(db: SupportSQLiteDatabase) {
        val newest = DatabaseBackups.restorableBackups(context, name, MessagesDatabase.VERSION).firstOrNull() ?: return
        runCatching { restoreFromNewestBackup() }.onFailure {
            Log.e(TAG, "Restoring $name from a backup failed", it)
            runCatching { RecoveryAttempts.failed(context, newest) }
        }
    }

    private fun restoreFromNewestBackup() {
        val backups = DatabaseBackups.restorableBackups(context, name, MessagesDatabase.VERSION)
        val newest = backups.firstOrNull() ?: return
        DatabaseBackups.withMigratedCopy(context, name, newest, migrate = ::migrateScratch) { db ->
            DatabaseBackups.inTransaction(db) {
                val recycled = "EXISTS (SELECT 1 FROM src.recycle_bin_messages r " +
                    "WHERE r.id = s.id AND r.is_mms = s.is_mms)"
                DatabaseBackups.copyRows(db, "messages", where = "s.is_scheduled = 1 OR $recycled")
                DatabaseBackups.copyRows(db, "recycle_bin_messages")
                DatabaseBackups.copyRows(db, "conversations", where = "s.is_scheduled = 1")
                DatabaseBackups.copyRows(db, "drafts")
                DatabaseBackups.copyRows(db, "starred_messages")
                DatabaseBackups.copyRows(db, "scheduled_repeats")
                LabelRecovery.merge(db)
                DatabaseBackups.mergeRows(db, "message_reminders")
                DatabaseBackups.copyRows(db, "message_notes")
            }
        }
        backups.forEach { DatabaseBackups.retire(it) }
        RecoveryAttempts.succeeded(context, newest)
        Log.i(TAG, "Restored the user data of $name from ${newest.name}")
    }

    /** Opens the scratch copy once with every migration, which brings it to the current schema, and closes it. */
    private fun migrateScratch(scratchName: String) {
        val scratch = Room.databaseBuilder(context, MessagesDatabase::class.java, scratchName)
            .addMigrations(*MessagesDatabase.ALL_MIGRATIONS)
            .build()
        try {
            scratch.openHelper.writableDatabase
        } finally {
            scratch.close()
        }
    }

    private companion object {
        const val TAG = "MessagesDbRecovery"
    }
}
