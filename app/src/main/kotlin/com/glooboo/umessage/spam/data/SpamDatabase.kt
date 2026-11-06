package com.glooboo.umessage.spam.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.glooboo.umessage.databases.DatabaseBackups

@Database(
    entities = [
        SpamRuleEntity::class, BlockedMessageEntity::class, SpamEventEntity::class, PendingSmsEntity::class,
        ForwardRuleEntity::class, ForwardLogEntity::class,
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

        internal const val DATABASE_NAME = "spamless.db"

        /** Must equal the `version` in [Database] above. */
        internal const val VERSION = 5

        /** Every migration, oldest first. */
        internal val ALL_MIGRATIONS: Array<Migration> =
            arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)

        fun getInstance(context: Context): SpamDatabase = instance ?: synchronized(this) {
            instance ?: build(context.applicationContext, DATABASE_NAME).also { instance = it }
        }

        /** Builds the database exactly as the app opens it. Tests call this directly to get an unshared instance. */
        internal fun build(context: Context, name: String): SpamDatabase {
            // Downgrade policy, see SpamDatabaseRecovery: a file from a newer schema is moved aside and its rows
            // copied into a fresh database of this schema, never wiped.
            val downgradedFrom = DatabaseBackups.moveAsideIfNewer(context, name, VERSION)
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
