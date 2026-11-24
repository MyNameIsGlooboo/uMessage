package com.glooboo.umessage.spam.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.glooboo.umessage.databases.DatabaseBackups
import com.glooboo.umessage.databases.RecoveryAttempts
import com.glooboo.umessage.spam.AllowGroup
import com.glooboo.umessage.spam.DecisionReason
import com.glooboo.umessage.spam.FirstRun
import com.glooboo.umessage.spam.FirstRunTrigger
import com.glooboo.umessage.spam.ForwardSim
import com.glooboo.umessage.spam.ListKind
import com.glooboo.umessage.spam.MemberSource
import com.glooboo.umessage.spam.MmsMedia
import com.glooboo.umessage.spam.RuleType
import java.io.File

/**
 * Downgrade policy of spamless.db. Rules, blocked messages and the event log exist nowhere else (spam settings live
 * in SharedPreferences and are not affected), so this database is never wiped.
 *
 * - Downgrade (the file was written by a newer schema N): [SpamDatabase.build] moves it aside as
 *   `spamless.db.v<N>-backup` and Room creates an empty database of this schema. On its first open this callback
 *   copies every row it can from the backup: for each table, the columns both schemas share, skipping rows with an
 *   enum value this build does not know (they would crash Room on read). A table this schema cannot hold (it needs a
 *   value the newer one lacks) is left empty. When every table copied, the backup is renamed `-imported` and kept
 *   as a safety copy; otherwise it stays a `-backup` for the next upgrade.
 * - Upgrade again (every open): when a `-backup` of schema N <= [SpamDatabase.VERSION] exists, a copy of it is
 *   migrated to this schema and its rules, blocked messages, events and forward rules are merged in (rows the live
 *   database already has, by content, are skipped; clashing ids get new ones), then it is renamed `-restored`. Pending
 *   SMS are not merged here: the older build delivered or dropped them meanwhile, and replaying them would duplicate
 *   messages.
 * - Forward rules after a trip to schema 3: that build renamed the file it imported `-imported` (it copied every table
 *   it knows), so the forward rules are merged back from the newest `-imported` file, once. The file name is that of
 *   the schema imported, not of the build that imported it (0.4.14 also keeps a `.v9-imported`, and it edits forward
 *   rules), so the trip is told by the live file: [ForwardTrip] marks a file that was still below schema 4 when this
 *   build opened it, that is, the open where MIGRATION_3_4 ran. Without the mark only the members and the migration
 *   marks come back (J27).
 *
 * Failures are logged and never stop the database from opening. Nothing here runs SQL on Room's own connection: each
 * step opens a connection of its own ([DatabaseBackups.withAttached]), so Room's invalidation table survives (J14).
 */
internal class SpamDatabaseRecovery(
    private val context: Context,
    private val name: String,
    /** The backup [SpamDatabase.build] just moved aside, when this open follows a downgrade. */
    private val downgradedFrom: File?,
) : RoomDatabase.Callback() {

    override fun onOpen(db: SupportSQLiteDatabase) {
        runCatching {
            if (downgradedFrom != null) importNewer(downgradedFrom) else restoreFromNewestBackup()
        }.onFailure { Log.e(TAG, "Recovering $name from a backup failed", it) }
    }

    private fun importNewer(backup: File) {
        val copied = DatabaseBackups.withAttached(context.getDatabasePath(name), backup) { db ->
            DatabaseBackups.inTransaction(db) {
                importedTables(db).map { (table, where) -> DatabaseBackups.copyRows(db, table, where) }
            }
        }
        if (copied.all { it }) DatabaseBackups.retire(backup, state = "imported")
        Log.w(TAG, "Imported $name from ${backup.name}; tables copied: $copied")
    }

    private fun restoreFromNewestBackup() {
        restoreForwardRulesFromImported()
        val backups = DatabaseBackups.restorableBackups(context, name, SpamDatabase.VERSION)
        val newest = backups.firstOrNull() ?: return
        DatabaseBackups.withMigratedCopy(context, name, newest, migrate = ::migrateScratch) { db ->
            DatabaseBackups.inTransaction(db) {
                reattachKeptMms(db)
                mergedTables(db).forEach { (table, where) -> DatabaseBackups.mergeRows(db, table, where = where) }
                // Members have no id column: their key is the group and the sender, so a plain copy skips doubles.
                if (hasMembers(db)) DatabaseBackups.copyRows(db, MEMBERS.first, MEMBERS.second)
            }
        }
        backups.forEach { DatabaseBackups.retire(it) }
        Log.i(TAG, "Restored rules, blocked messages and events of $name from ${newest.name}")
    }

    /**
     * A build older than schema 4 that opened a newer file copied every table it knows and renamed the file
     * `-imported`, leaving the forward rules behind in it, unchangeable by that build. Back on schema 4 or later, they
     * are merged back from the newest `-imported` file (rows already here are skipped), and the files are renamed
     * `-restored` so this happens once. Only when [ForwardTrip] marks the live file (J27), that is, when
     * MIGRATION_3_4 ran on this open: after a trip to 0.4.14 the forward rules in the kept file are older than the
     * live ones and must not return.
     *
     * J14-3: only when both steps worked. A failed try keeps the file for the next open (both steps skip what is
     * already here, so a second try adds nothing twice); after [RecoveryAttempts.MAX_FAILURES] failed tries it is
     * kept as `-failed` and not tried again.
     */
    private fun restoreForwardRulesFromImported() {
        val imported = DatabaseBackups.importedBackups(context, name, ForwardTrip.FORWARD_SINCE..SpamDatabase.VERSION)
        val newest = imported.firstOrNull()
        if (newest == null) {
            ForwardTrip.clear(context, name)
            return
        }
        // J27 (L2-1): a rule the user deleted, switched off or retargeted on 0.4.14 must not return from its file.
        // The marker means MIGRATION_3_4 ran on this open, so the build that wrote the live file could not change a rule.
        val forwardRules = if (!ForwardTrip.isMarked(context, name)) {
            // Deliberate: with no mark the file brings no forward rules (only members and marks), yet it is retired
            // below, so a later open cannot read it again.
            true
        } else {
            runCatching {
                DatabaseBackups.withMigratedCopy(context, name, newest, migrate = ::migrateScratch) { db ->
                    DatabaseBackups.inTransaction(db) {
                        val (table, where) = forwards(db)
                        DatabaseBackups.mergeRows(db, table, where = where)
                    }
                }
            }.onFailure { Log.e(TAG, "Restoring forward rules of $name from ${newest.name} failed", it) }.isSuccess
        }
        val members = restoreProviderTruthFromImported(newest)
        if (forwardRules && members != null) {
            imported.forEach { DatabaseBackups.retire(it) }
            ForwardTrip.clear(context, name)
            runCatching { RecoveryAttempts.succeeded(context, newest) }
        } else {
            runCatching { RecoveryAttempts.failed(context, newest) }
        }
        // The history check runs again for every chat (a RESTORE pass).
        if (members == true) runCatching { FirstRun.request(context, FirstRunTrigger.RESTORE) }
    }

    /**
     * R-4 (step F, F-17): after a trip to 0.4.14 and back, the group members and the message migration's marks come
     * back from the schema-9 file that build kept aside: in a transaction of their own, after the forward rules, so a
     * failure here keeps those. True when the file had the members table (a RESTORE pass is owed), null when this
     * step failed.
     */
    private fun restoreProviderTruthFromImported(newest: File): Boolean? {
        var members = false
        return runCatching {
            DatabaseBackups.withMigratedCopy(context, name, newest, migrate = ::migrateScratch) { db ->
                DatabaseBackups.inTransaction(db) {
                    if (hasMembers(db)) {
                        DatabaseBackups.copyRows(db, MEMBERS.first, MEMBERS.second)
                        members = true
                    }
                    reattachMarks(db)
                }
            }
        }.onFailure { Log.e(TAG, "Restoring group members and marks of $name from ${newest.name} failed", it) }
            .map { members }.getOrNull()
    }

    /**
     * R-4 (F-17): a held row 0.4.14 copied back without its migration mark gets the mark of the same message (sender,
     * text, kind, time) in the kept schema-9 file, so a message the user deleted from the store meanwhile is not
     * moved a second time.
     */
    private fun reattachMarks(db: SQLiteDatabase) {
        val marked = { schema: String ->
            DatabaseBackups.columns(db, schema, "blocked_messages").any { it.name == "migratedMsgId" }
        }
        if (!marked("src") || !marked("main")) return
        val t = "main.`blocked_messages`"
        val same = listOf("address", "body", "isMms", "receivedAt").joinToString(" AND ") { "s.`$it` IS $t.`$it`" }
        val source = "FROM src.`blocked_messages` s WHERE $same AND s.`migratedMsgId` IS NOT NULL"
        db.execSQL(
            "UPDATE $t SET `migratedMsgId` = (SELECT s.`migratedMsgId` $source LIMIT 1), " +
                "`migrateTries` = (SELECT s.`migrateTries` $source LIMIT 1) " +
                "WHERE `migratedMsgId` IS NULL AND EXISTS (SELECT 1 $source)"
        )
    }

    private fun migrateScratch(scratchName: String) {
        val scratch = Room.databaseBuilder(context, SpamDatabase::class.java, scratchName)
            .addMigrations(*SpamDatabase.ALL_MIGRATIONS)
            .build()
        try {
            scratch.openHelper.writableDatabase
        } finally {
            scratch.close()
        }
    }

    /**
     * SE-29: a blocked MMS whose content is kept has its file named in `mediaFile`. An older build (schema 2) copied
     * the row without the media columns, and MIGRATION_2_3 then marked it MISSING. Before merging, give such a row the
     * media state and file of the same message in the backup, so it is not merged in as a second copy and its kept
     * file (never deleted while a backup exists, see AndroidSpamPlatform.sweepMedia) is referenced again.
     */
    private fun reattachKeptMms(db: SQLiteDatabase) {
        if (!hasMediaColumns(db)) return
        val t = "main.`blocked_messages`"
        val same = listOf("address", "body", "subscriptionId", "isMms", "receivedAt", "reason")
            .joinToString(" AND ") { "s.`$it` IS $t.`$it`" }
        val source = "FROM src.`blocked_messages` s WHERE $same AND s.`mediaFile` IS NOT NULL AND " +
            known("media", MmsMedia.entries.toTypedArray())
        db.execSQL(
            "UPDATE $t SET `media` = (SELECT s.`media` $source LIMIT 1), `mediaFile` = (SELECT s.`mediaFile` $source LIMIT 1) " +
                "WHERE `isMms` = 1 AND `mediaFile` IS NULL AND `media` = 'MISSING' AND EXISTS (SELECT 1 $source)"
        )
    }

    private fun hasMediaColumns(db: SQLiteDatabase): Boolean =
        DatabaseBackups.columns(db, "src", "blocked_messages").any { it.name == "media" } &&
            DatabaseBackups.columns(db, "main", "blocked_messages").any { it.name == "media" }

    /** Blocked rows this build can read: a known reason and, when the backup has it, a known media state. */
    private fun blocked(db: SQLiteDatabase): Pair<String, String> {
        val reason = known("reason", DecisionReason.entries.toTypedArray())
        val where = if (hasMediaColumns(db)) "$reason AND ${known("media", MmsMedia.entries.toTypedArray())}" else reason
        return "blocked_messages" to where
    }

    /**
     * Forward rules (schema 4) with values this build knows. Rows come back exactly as they were, enabled state
     * included: they were set up behind the app lock, and the backup is the user's own data.
     */
    private fun forwards(db: SQLiteDatabase): Pair<String, String> {
        val cols = DatabaseBackups.columns(db, "src", FORWARD_TABLE).map { it.name }.toSet()
        val types = RuleType.entries.toTypedArray()
        val where = buildList {
            if ("senderType" in cols) add("(s.`senderType` IS NULL OR ${known("senderType", types)})")
            if ("bodyType" in cols) add("(s.`bodyType` IS NULL OR ${known("bodyType", types)})")
            if ("sendFrom" in cols) add(known("sendFrom", ForwardSim.entries.toTypedArray()))
        }.joinToString(" AND ").ifEmpty { "1" }
        return FORWARD_TABLE to where
    }

    private fun importedTables(db: SQLiteDatabase) =
        listOf(RULES, blocked(db), EVENTS, "pending_sms" to "1", forwards(db)) +
            listOfNotNull(MEMBERS.takeIf { hasMembers(db) })

    /** The group members table (schema 9) exists on both sides. */
    private fun hasMembers(db: SQLiteDatabase): Boolean =
        DatabaseBackups.columns(db, "src", MEMBERS.first).isNotEmpty() &&
            DatabaseBackups.columns(db, "main", MEMBERS.first).isNotEmpty()

    private fun mergedTables(db: SQLiteDatabase) = listOf(RULES, blocked(db), EVENTS, forwards(db))

    private companion object {
        const val TAG = "SpamDbRecovery"
        const val FORWARD_TABLE = "forward_rules"

        /** `s.column IN ('A', 'B')` for the values this build's enum knows. */
        fun known(column: String, values: Array<out Enum<*>>) =
            "s.`$column` IN (${values.joinToString { "'${it.name}'" }})"

        val RULES = "spam_rules" to "${known("kind", ListKind.entries.toTypedArray())} AND " +
            known("type", RuleType.entries.toTypedArray())
        val EVENTS = "spam_events" to known("type", SpamEventType.entries.toTypedArray())

        /** D3 A: the members of the group rules (schema 9), with a group and a source this build knows. */
        val MEMBERS = "allow_group_members" to "${known("group_type", AllowGroup.entries.toTypedArray())} AND " +
            known("source", MemberSource.entries.toTypedArray())
    }
}

/**
 * J27 (L2-1): decides the forward-rule restore by whether MIGRATION_3_4 ran on this open, never by the name of an
 * `-imported` file: that name carries the schema that was imported, not the build that imported it (0.4.14 leaves a
 * `.v9-imported` too, and it edits forward rules). MIGRATION_3_4 runs exactly when the live file was written by a
 * build older than schema 4, which could not hold, and so could not change, a forward rule. A live file still below
 * [FORWARD_SINCE] when this build opens it is marked; the marker outlives the migration, which turns the file into
 * the current schema, and is cleared once the forward rules are restored.
 *
 * The marker is dropped on every other version: a failed restore may leave it, and a later trip to 0.4.14 (schema 8)
 * must not inherit it. Only an open at the current version keeps it, for the retry of that failed restore.
 */
internal object ForwardTrip {
    private const val TAG = "SpamDbRecovery"

    /** The schema that added the forward rules table: the first one that can hold (and edit) a rule. */
    const val FORWARD_SINCE = 4

    private fun marker(context: Context, name: String) = File(context.getDatabasePath(name).path + ".forward-trip")

    /** Called before Room opens [name]: marks it when MIGRATION_3_4 is about to run on it. */
    fun markIfBeforeForwardRules(context: Context, name: String) {
        val file = marker(context, name)
        val version = DatabaseBackups.fileVersion(context.getDatabasePath(name))
        when {
            version != null && version in 1 until FORWARD_SINCE -> writeMarker(file)
            // A restore failed before and left the marker: keep it for the retry at the current schema.
            version == SpamDatabase.VERSION -> Unit
            else -> runCatching { file.delete() }
        }
    }

    /** Writes the marker; a failure is logged and never stops the database from opening. */
    private fun writeMarker(file: File) {
        if (file.exists()) return
        val created = runCatching { file.createNewFile() }.getOrElse {
            Log.w(TAG, "Could not write the forward-trip marker ${file.name}", it)
            return
        }
        if (!created) Log.w(TAG, "Could not write the forward-trip marker ${file.name}")
    }

    fun isMarked(context: Context, name: String) = marker(context, name).exists()

    fun clear(context: Context, name: String) {
        marker(context, name).delete()
    }
}
