package com.glooboo.umessage.spam.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.glooboo.umessage.databases.DatabaseBackups
import com.glooboo.umessage.ui.spam.RuleNotes

@Database(
    entities = [
        SpamRuleEntity::class, BlockedMessageEntity::class, SpamEventEntity::class, PendingSmsEntity::class,
        ForwardRuleEntity::class, ForwardLogEntity::class, AllowGroupMemberEntity::class,
    ],
    version = SpamDatabase.VERSION,
    exportSchema = true
)
abstract class SpamDatabase : RoomDatabase() {

    abstract fun spamDao(): SpamDao

    abstract fun forwardDao(): ForwardDao

    abstract fun forwardLogDao(): ForwardLogDao

    companion object {
        @Volatile private var instance: SpamDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `pending_sms` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`address` TEXT NOT NULL, `subject` TEXT NOT NULL, `body` TEXT NOT NULL, `date` INTEGER NOT NULL, " +
                        "`subscriptionId` INTEGER NOT NULL, `status` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL)"
                )
            }
        }

        /**
         * Blocked MMS can keep their downloaded content (SE-29). Existing MMS rows kept nothing, so they are MISSING;
         * SMS rows are NONE.
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `blocked_messages` ADD COLUMN `media` TEXT NOT NULL DEFAULT 'NONE'")
                db.execSQL("ALTER TABLE `blocked_messages` ADD COLUMN `mediaFile` TEXT")
                db.execSQL("UPDATE `blocked_messages` SET `media` = 'MISSING' WHERE `isMms` = 1")
            }
        }

        /** Forward rules (wave 8): a new table, nothing else changes. */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `forward_rules` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`senderType` TEXT, `senderPattern` TEXT NOT NULL DEFAULT '', `bodyType` TEXT, " +
                        "`bodyPattern` TEXT NOT NULL DEFAULT '', `windowStart` INTEGER, `windowEnd` INTEGER, " +
                        "`subscriptionId` INTEGER, `targets` TEXT NOT NULL, `sendFrom` TEXT NOT NULL DEFAULT 'SAME', " +
                        "`includeCodes` INTEGER NOT NULL DEFAULT 0, `enabled` INTEGER NOT NULL DEFAULT 1, " +
                        "`note` TEXT NOT NULL DEFAULT '', `createdAt` INTEGER NOT NULL)"
                )
            }
        }

        /** Schema version that added the forward log. */
        private const val VERSION_FORWARD_LOG = 5

        /** Forward log (design review 2): a new table with one row per forward and target, nothing else changes. */
        private val MIGRATION_4_5 = object : Migration(4, VERSION_FORWARD_LOG) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `forward_log` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`at` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `ruleId` INTEGER NOT NULL, " +
                        "`sourceAddress` TEXT NOT NULL, `sourceDate` INTEGER NOT NULL, " +
                        "`sourceIsMms` INTEGER NOT NULL, " +
                        "`target` TEXT NOT NULL, `subscriptionId` INTEGER, `status` TEXT NOT NULL, " +
                        "`errorCode` TEXT, `attempts` INTEGER NOT NULL)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_forward_log_at` ON `forward_log` (`at`)")
            }
        }

        /**
         * Unread state of held messages (Blocked tab rows are bold while unread). Existing rows are read: the column
         * defaults to 0, and only messages stored from now on start unread.
         */
        private val MIGRATION_5_6 = object : Migration(VERSION_FORWARD_LOG, VERSION_UNREAD) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `blocked_messages` ADD COLUMN `unread` INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** Schema version that added the unread flag of held messages. */
        private const val VERSION_UNREAD = 6

        /**
         * Incoming SMS keep the carrier's send time (core-3): a new column on the pending queue. Rows already queued
         * have no send time, which is 0 (unknown).
         */
        private val MIGRATION_6_7 = object : Migration(VERSION_UNREAD, VERSION_DATE_SENT) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `pending_sms` ADD COLUMN `dateSent` INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** Schema version that added the carrier send time to the pending queue. */
        private const val VERSION_DATE_SENT = 7

        /**
         * Schema 8 (owner decisions on the triage): a restored message in the pending queue skips the filter on
         * replay (`approved`), and block rules count their hits (`hitCount`, `lastHit`). Existing rows keep behavior:
         * nothing is approved, no rule has a hit. The new DOMAIN rule type needs no column (the type is stored by
         * name).
         */
        private val MIGRATION_7_8 = object : Migration(VERSION_DATE_SENT, VERSION_RULE_STATS) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `pending_sms` ADD COLUMN `approved` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `spam_rules` ADD COLUMN `hitCount` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `spam_rules` ADD COLUMN `lastHit` INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** Schema version that added the approved flag and the rule hit counters. */
        private const val VERSION_RULE_STATS = 8

        /**
         * Schema 9, provider truth (7.2, S15, U15): every spam rule covers both SIMs, duplicates merged; the Keep
         * state of automatic allow rules; the members of the two group rules; the done mark of the message migration.
         * See [ProviderTruthRuleMigration] for why the old filter still works on it.
         */
        private val MIGRATION_8_9 = object : Migration(VERSION_RULE_STATS, VERSION_PROVIDER_TRUTH) {
            override fun migrate(db: SupportSQLiteDatabase) = ProviderTruthRuleMigration.migrate(db)
        }

        /** Schema version of provider truth: rules for both SIMs, group members, migratedMsgId. */
        private const val VERSION_PROVIDER_TRUTH = 9

        internal const val DATABASE_NAME = "spamless.db"

        /** Must equal the `version` in [Database] above. */
        internal const val VERSION = VERSION_PROVIDER_TRUTH

        /** Every migration, oldest first. */
        internal val ALL_MIGRATIONS: Array<Migration> =
            arrayOf(
                MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7,
                MIGRATION_7_8, MIGRATION_8_9,
            )

        fun getInstance(context: Context): SpamDatabase = instance ?: synchronized(this) {
            instance ?: build(context.applicationContext, DATABASE_NAME).also { instance = it }
        }

        /** Builds the database exactly as the app opens it. Tests call this directly to get an unshared instance. */
        internal fun build(context: Context, name: String): SpamDatabase {
            // Downgrade policy, see SpamDatabaseRecovery: a file from a newer schema is moved aside and its rows
            // copied into a fresh database of this schema, never wiped.
            val downgradedFrom = DatabaseBackups.moveAsideIfNewer(context, name, VERSION)
            ForwardTrip.markIfBeforeForwardRules(context, name)
            return Room.databaseBuilder(context, SpamDatabase::class.java, name)
                .addMigrations(*ALL_MIGRATIONS)
                // Acts only when moving a newer file aside failed (so nothing was copied): opening empty beats the
                // SMS app crashing on every launch. Never on upgrade: a missing migration must fail in testing.
                .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
                .addCallback(SpamDatabaseRecovery(context, name, downgradedFrom))
                .build()
        }
    }
}

/**
 * spamless.db 8 to 9 (provider truth, 7.2; S15, U15). Android's open helper runs every upgrade step inside one
 * transaction, so this applies whole or not at all; a failure leaves the file at version 8 for the next start.
 *
 * 1. Rules with the same kind, type and pattern (the same rule on two SIMs, or for one SIM and both) become one: the
 *    oldest id stays, hit counts are added up, the newest "last hit" and the oldest creation time are kept, and a
 *    note of the user ("Blocked by you", a typed note, none) wins over a note the filter wrote (allowed or blocked
 *    from a word). Held messages that named a merged rule name the kept one.
 * 2. Every spam rule covers both SIMs (subscriptionId null); the unique index is on kind, type and pattern.
 *    Forward rules are another table and keep their SIM.
 * 3. spam_rules.acknowledged: automatic allow rules made by an allowed word before the update start acknowledged
 *    (they get no line in their chat).
 * 4. The table of the group members, empty.
 * 5. blocked_messages.migratedMsgId and migrateTries for the message migration, empty.
 *
 * Safe for the 0.4.14 filter (after a downgrade): it reads no new column or table, and a rule for one SIM now
 * matches on both, which only widens a rule the user made (the owner decided every rule covers both SIMs). No rule,
 * held message or event is lost; a downgrade copies the shared columns back (SpamDatabaseRecovery).
 */
internal object ProviderTruthRuleMigration {

    private const val CREATE_MEMBERS = "CREATE TABLE IF NOT EXISTS `allow_group_members` (" +
        "`group_type` TEXT NOT NULL, `sender_key` TEXT NOT NULL, `address` TEXT NOT NULL, " +
        "`added_at` INTEGER NOT NULL, `source` TEXT NOT NULL, `acknowledged` INTEGER NOT NULL, " +
        "PRIMARY KEY(`group_type`, `sender_key`))"

    /** One rule row; [identity] is its kind, type and pattern, what the new unique index allows once. */
    private class Row(
        val id: Long,
        val identity: Triple<String, String, String>,
        val note: String,
        val createdAt: Long,
        val hitCount: Int,
        val lastHit: Long,
    )

    fun migrate(db: SupportSQLiteDatabase) {
        mergeDuplicates(db)
        db.execSQL("UPDATE `spam_rules` SET `subscriptionId` = NULL")
        db.execSQL("DROP INDEX IF EXISTS `index_spam_rules_kind_type_pattern_subscriptionId`")
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_spam_rules_kind_type_pattern` " +
                "ON `spam_rules` (`kind`, `type`, `pattern`)"
        )
        db.execSQL("ALTER TABLE `spam_rules` ADD COLUMN `acknowledged` INTEGER NOT NULL DEFAULT 0")
        val forms = RuleNotes.storedForms(RuleNotes.ALLOWED_FROM_KEYWORD)
        db.execSQL(
            "UPDATE `spam_rules` SET `acknowledged` = 1 WHERE `kind` = 'ALLOW' AND TRIM(`note`) IN " +
                "(${forms.joinToString { "?" }})",
            forms.toTypedArray(),
        )
        db.execSQL(CREATE_MEMBERS)
        db.execSQL("ALTER TABLE `blocked_messages` ADD COLUMN `migratedMsgId` INTEGER")
        db.execSQL("ALTER TABLE `blocked_messages` ADD COLUMN `migrateTries` INTEGER NOT NULL DEFAULT 0")
    }

    private fun mergeDuplicates(db: SupportSQLiteDatabase) {
        val rows = mutableListOf<Row>()
        db.query(
            "SELECT `id`, `kind`, `type`, `pattern`, `note`, `createdAt`, `hitCount`, `lastHit` FROM `spam_rules` " +
                "ORDER BY `id`"
        ).use { c ->
            fun at(name: String) = c.getColumnIndexOrThrow(name)
            while (c.moveToNext()) {
                rows += Row(
                    id = c.getLong(at("id")),
                    identity = Triple(c.getString(at("kind")), c.getString(at("type")), c.getString(at("pattern"))),
                    note = c.getString(at("note")).orEmpty(),
                    createdAt = c.getLong(at("createdAt")),
                    hitCount = c.getInt(at("hitCount")),
                    lastHit = c.getLong(at("lastHit")),
                )
            }
        }
        rows.groupBy { it.identity }.values.filter { it.size > 1 }.forEach { merge(db, it) }
    }

    /** [group] is ordered by id: the first row is the oldest and stays. */
    private fun merge(db: SupportSQLiteDatabase, group: List<Row>) {
        val keep = group.first()
        val gone = group.drop(1).joinToString(",") { it.id.toString() }
        val note = if (!RuleNotes.isMadeByFilter(keep.note)) {
            keep.note
        } else {
            group.firstOrNull { !RuleNotes.isMadeByFilter(it.note) }?.note ?: keep.note
        }
        db.execSQL("DELETE FROM `spam_rules` WHERE `id` IN ($gone)")
        db.execSQL(
            "UPDATE `spam_rules` SET `note` = ?, `createdAt` = ?, `hitCount` = ?, `lastHit` = ? WHERE `id` = ?",
            arrayOf<Any>(
                note, group.minOf { it.createdAt }, group.sumOf { it.hitCount }, group.maxOf { it.lastHit }, keep.id,
            ),
        )
        db.execSQL("UPDATE `blocked_messages` SET `ruleId` = ? WHERE `ruleId` IN ($gone)", arrayOf<Any>(keep.id))
    }
}
