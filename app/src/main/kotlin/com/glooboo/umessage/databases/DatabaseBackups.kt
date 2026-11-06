package com.glooboo.umessage.databases

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import androidx.sqlite.db.SupportSQLiteDatabase
import java.io.File

/**
 * File handling and row copying for the downgrade policy of both app databases.
 *
 * When a build opens a database file written by a newer schema (the user sideloaded an older build), it moves the
 * file aside as `<name>.v<N>-backup` instead of crashing or silently wiping it. A later build whose schema is at least
 * N finds that backup, migrates a copy of it to its own schema and merges the rows that cannot be rebuilt from the
 * telephony provider back in, then renames the backup to `<name>.v<N>-restored` so it is used only once.
 *
 * Each database decides which rows to bring back: see MessagesDatabaseRecovery and SpamDatabaseRecovery.
 */
internal object DatabaseBackups {
    private const val TAG = "DatabaseBackups"

    /** Files SQLite may keep next to a database; they belong to it and travel with it. */
    private val COMPANIONS = listOf("", "-wal", "-shm", "-journal")

    private val BACKUP_NAME = Regex("""\.v(\d+)-backup$""")
    private val IMPORTED_NAME = Regex("""\.v(\d+)-imported$""")
    private val STATE_SUFFIX = Regex("""-(backup|imported)$""")

    /** Where a database file written by schema [version] is kept after a downgrade. */
    fun backupFile(context: Context, name: String, version: Int): File =
        File(context.getDatabasePath(name).path + ".v$version-backup")

    /** The SQLite user_version of [file], or null when it does not exist or cannot be read. */
    fun fileVersion(file: File): Int? {
        if (!file.exists()) return null
        return runCatching {
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { it.version }
        }.getOrNull()
    }

    /**
     * If database [name] on disk was written by a schema newer than [currentVersion], moves it (with its WAL and
     * journal) to [backupFile] so the caller opens a fresh, empty database instead. Falls back to copying when the
     * file cannot be renamed. Returns the backup, or null when there was nothing to do or it failed. Never throws.
     */
    fun moveAsideIfNewer(context: Context, name: String, currentVersion: Int): File? = runCatching {
        val file = context.getDatabasePath(name)
        val version = fileVersion(file) ?: return null
        if (version <= currentVersion) return null
        val backup = backupFile(context, name, version)
        deleteWithCompanions(backup)
        for (suffix in COMPANIONS) {
            val source = File(file.path + suffix)
            if (!source.exists()) continue
            val target = File(backup.path + suffix)
            if (!source.renameTo(target)) {
                source.copyTo(target, overwrite = true)
                source.delete()
            }
        }
        Log.w(TAG, "$name was written by schema $version, newer than $currentVersion: kept as ${backup.name}")
        backup
    }.onFailure { Log.e(TAG, "Could not move $name aside", it) }.getOrNull()

    /** Backups of [name] that a build with schema [currentVersion] can read, newest schema first. */
    fun restorableBackups(context: Context, name: String, currentVersion: Int): List<File> =
        backupsNamed(context, name, BACKUP_NAME) { it <= currentVersion }

    /**
     * `-imported` copies of [name] with a schema in [versions], newest first. An older build that imported a newer
     * file renames it `-imported` once every table *it* knows was copied, so tables added later (such as the forward
     * rules of spamless.db schema 4) exist only there; the next build that knows them merges them back from it.
     */
    fun importedBackups(context: Context, name: String, versions: IntRange): List<File> =
        backupsNamed(context, name, IMPORTED_NAME) { it in versions }

    private fun backupsNamed(context: Context, name: String, pattern: Regex, wanted: (Int) -> Boolean): List<File> {
        val file = context.getDatabasePath(name)
        val prefix = file.name
        val candidates = file.parentFile?.listFiles { _, fileName ->
            fileName.startsWith(prefix) && pattern.matches(fileName.substring(prefix.length))
        } ?: return emptyList()
        return candidates
            .mapNotNull { candidate ->
                val version = pattern.find(candidate.name)!!.groupValues[1].toIntOrNull()
                version?.takeIf(wanted)?.let { it to candidate }
            }
            .sortedByDescending { it.first }
            .map { it.second }
    }

    /**
     * Renames `<name>.vN-backup` (or `-imported`) to `<name>.vN-<state>` (with companions), so it is never offered
     * again.
     */
    fun retire(backup: File, state: String = "restored") {
        val target = File(backup.path.replace(STATE_SUFFIX, "") + "-$state")
        deleteWithCompanions(target)
        for (suffix in COMPANIONS) {
            val source = File(backup.path + suffix)
            if (source.exists() && !source.renameTo(File(target.path + suffix))) source.delete()
        }
    }

    fun copyWithCompanions(from: File, to: File) {
        deleteWithCompanions(to)
        for (suffix in COMPANIONS) {
            val source = File(from.path + suffix)
            if (source.exists()) source.copyTo(File(to.path + suffix), overwrite = true)
        }
    }

    fun deleteWithCompanions(file: File) {
        for (suffix in COMPANIONS) File(file.path + suffix).delete()
    }

    /**
     * Copies a backup to a scratch database next to [name], lets [migrate] bring it to the current schema (it opens
     * and closes the scratch database by its name), then runs [block] with it attached to [db] as `src`.
     * The scratch files are always deleted.
     */
    fun <T> withMigratedCopy(
        context: Context,
        name: String,
        backup: File,
        db: SupportSQLiteDatabase,
        migrate: (scratchName: String) -> Unit,
        block: () -> T,
    ): T {
        val scratchName = "$name.restore-tmp"
        val scratch = context.getDatabasePath(scratchName)
        try {
            copyWithCompanions(backup, scratch)
            // Checked first: SQLite's corruption handler would quietly replace an unreadable file with an empty one.
            checkNotNull(fileVersion(scratch)) { "${backup.name} is not a readable database" }
            migrate(scratchName)
            return withAttached(db, scratch, block)
        } finally {
            deleteWithCompanions(scratch)
        }
    }

    /** Runs [block] with [file] attached to [db] as `src`. Must not be called inside a transaction. */
    fun <T> withAttached(db: SupportSQLiteDatabase, file: File, block: () -> T): T {
        db.execSQL("ATTACH DATABASE ? AS src", arrayOf<Any?>(file.path))
        try {
            return block()
        } finally {
            db.execSQL("DETACH DATABASE src")
        }
    }

    data class Column(val name: String, val notNull: Boolean, val hasDefault: Boolean, val primaryKey: Boolean)

    /** The columns of [table] in the attached [schema] (`main` or `src`); empty when the table does not exist. */
    fun columns(db: SupportSQLiteDatabase, schema: String, table: String): List<Column> =
        db.query("PRAGMA $schema.table_info(`$table`)").use { cursor ->
            val name = cursor.getColumnIndexOrThrow("name")
            val notNull = cursor.getColumnIndexOrThrow("notnull")
            val default = cursor.getColumnIndexOrThrow("dflt_value")
            val pk = cursor.getColumnIndexOrThrow("pk")
            buildList {
                while (cursor.moveToNext()) {
                    add(Column(cursor.getString(name), cursor.getInt(notNull) != 0, !cursor.isNull(default),
                        cursor.getInt(pk) != 0))
                }
            }
        }

    /**
     * The columns `main.[table]` and `src.[table]` share, or null when a row of `src` cannot become a row of `main`:
     * the table is missing there, or `main` needs a value (NOT NULL, no default) that `src` does not have. A rowid
     * primary key is exempt, SQLite fills it in.
     */
    fun sharedColumns(db: SupportSQLiteDatabase, table: String): List<String>? {
        val main = columns(db, "main", table)
        val src = columns(db, "src", table).map { it.name }.toSet()
        if (main.isEmpty() || src.isEmpty()) return null
        val missing = main.filter { it.notNull && !it.hasDefault && !it.primaryKey && it.name !in src }
        if (missing.isNotEmpty()) return null
        return main.map { it.name }.filter { it in src }
    }

    /**
     * Copies the rows of `src.[table]` matching [where] (an SQL condition on `s`, the source row) into `main.[table]`,
     * keeping every row `main` already has (INSERT OR IGNORE on its keys). Returns false when the table cannot be
     * copied (see [sharedColumns]).
     */
    fun copyRows(db: SupportSQLiteDatabase, table: String, where: String = "1"): Boolean {
        val shared = sharedColumns(db, table) ?: return false
        val list = shared.joinToString { "`$it`" }
        val select = shared.joinToString { "s.`$it`" }
        db.execSQL("INSERT OR IGNORE INTO main.`$table` ($list) SELECT $select FROM src.`$table` s WHERE $where")
        return true
    }

    /**
     * Merges `src.[table]` into `main.[table]` for a table with a generated [id] column, where both sides may have
     * handed out the same ids to different rows: a source row identical (in every other shared column) to a row
     * already in `main` is skipped; otherwise it keeps its id when that id is free, or gets a new one. Rows that
     * break a unique index are skipped. Returns false when the table cannot be copied.
     */
    fun mergeRows(db: SupportSQLiteDatabase, table: String, id: String = "id", where: String = "1"): Boolean {
        val shared = sharedColumns(db, table) ?: return false
        val content = shared.filter { it != id }
        val same = content.joinToString(" AND ") { "m.`$it` IS s.`$it`" }.ifEmpty { "1" }
        val notThere = "NOT EXISTS (SELECT 1 FROM main.`$table` m WHERE $same)"
        val idTaken = "EXISTS (SELECT 1 FROM main.`$table` m WHERE m.`$id` = s.`$id`)"
        val withId = shared.joinToString { "`$it`" } to shared.joinToString { "s.`$it`" }
        val withoutId = content.joinToString { "`$it`" } to content.joinToString { "s.`$it`" }
        db.execSQL(
            "INSERT OR IGNORE INTO main.`$table` (${withId.first}) SELECT ${withId.second} FROM src.`$table` s " +
                "WHERE ($where) AND NOT $idTaken AND $notThere"
        )
        db.execSQL(
            "INSERT OR IGNORE INTO main.`$table` (${withoutId.first}) SELECT ${withoutId.second} FROM src.`$table` s " +
                "WHERE ($where) AND $idTaken AND $notThere"
        )
        return true
    }

    /** Runs [block] in a transaction on [db]; a throw rolls everything back. */
    inline fun <T> inTransaction(db: SupportSQLiteDatabase, block: () -> T): T {
        db.beginTransaction()
        try {
            return block().also { db.setTransactionSuccessful() }
        } finally {
            db.endTransaction()
        }
    }
}
