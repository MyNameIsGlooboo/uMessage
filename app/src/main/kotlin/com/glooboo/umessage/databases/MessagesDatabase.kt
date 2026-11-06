@file:Suppress("MagicNumber")
package com.glooboo.umessage.databases

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.glooboo.umessage.helpers.Converters
import com.glooboo.umessage.interfaces.AttachmentsDao
import com.glooboo.umessage.interfaces.ConversationsDao
import com.glooboo.umessage.interfaces.DraftsDao
import com.glooboo.umessage.interfaces.LabelsDao
import com.glooboo.umessage.interfaces.MessageAttachmentsDao
import com.glooboo.umessage.interfaces.MessageNotesDao
import com.glooboo.umessage.interfaces.MessageRemindersDao
import com.glooboo.umessage.interfaces.MessagesDao
import com.glooboo.umessage.interfaces.ScheduledRepeatsDao
import com.glooboo.umessage.interfaces.StarredMessagesDao
import com.glooboo.umessage.models.Attachment
import com.glooboo.umessage.models.Conversation
import com.glooboo.umessage.models.ConversationLabel
import com.glooboo.umessage.models.Draft
import com.glooboo.umessage.models.Label
import com.glooboo.umessage.models.Message
import com.glooboo.umessage.models.MessageAttachment
import com.glooboo.umessage.models.MessageNote
import com.glooboo.umessage.models.MessageReminder
import com.glooboo.umessage.models.RecycleBinMessage
import com.glooboo.umessage.models.ScheduledRepeat
import com.glooboo.umessage.models.StarredMessage
import java.io.File

@Database(
    entities = [
        Conversation::class,
        Attachment::class,
        MessageAttachment::class,
        Message::class,
        RecycleBinMessage::class,
        Draft::class,
        StarredMessage::class,
        ScheduledRepeat::class,
        Label::class,
        ConversationLabel::class,
        MessageReminder::class,
        MessageNote::class
    ],
    version = MessagesDatabase.VERSION
)
@TypeConverters(Converters::class)
abstract class MessagesDatabase : RoomDatabase() {

    abstract fun ConversationsDao(): ConversationsDao

    abstract fun AttachmentsDao(): AttachmentsDao

    abstract fun MessageAttachmentsDao(): MessageAttachmentsDao

    abstract fun MessagesDao(): MessagesDao

    abstract fun DraftsDao(): DraftsDao

    abstract fun StarredMessagesDao(): StarredMessagesDao

    abstract fun ScheduledRepeatsDao(): ScheduledRepeatsDao

    abstract fun LabelsDao(): LabelsDao

    abstract fun MessageRemindersDao(): MessageRemindersDao

    abstract fun MessageNotesDao(): MessageNotesDao

    companion object {
        internal const val DATABASE_NAME = "conversations.db"

        @Volatile
        private var db: MessagesDatabase? = null

        fun getInstance(context: Context): MessagesDatabase {
            if (db == null) {
                synchronized(MessagesDatabase::class) {
                    if (db == null) {
                        db = build(context.applicationContext, DATABASE_NAME)
                    }
                }
            }
            return db!!
        }

        /** Must equal the `version` in [Database] above. */
        internal const val VERSION = 14

        /** Builds the database exactly as the app opens it. Tests call this directly to get an unshared instance. */
        internal fun build(context: Context, name: String): MessagesDatabase {
            // Downgrade policy, see MessagesDatabaseRecovery: a file from a newer schema is moved aside (kept as
            // name.v<N>-backup) and this build starts empty; a later build restores what the provider cannot rebuild.
            DatabaseBackups.moveAsideIfNewer(context, name, VERSION)
            return Room.databaseBuilder(
                context = context,
                klass = MessagesDatabase::class.java,
                name = name
            )
                // No destructive fallback on upgrade: this DB is the only copy of drafts, scheduled
                // messages and recycle-bin entries. Every version 1..14 has a migration below, so no
                // existing install needs one; a future bump without a migration must fail loudly in
                // testing rather than silently wipe user data. The downgrade fallback only ever acts when
                // moving a newer file aside failed: an empty cache beats crashing on every launch.
                .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
                .addMigrations(*ALL_MIGRATIONS)
                .addCallback(MessagesDatabaseRecovery(context, name))
                .build()
        }

        /** Where a database file written by a newer [version] is kept after a downgrade. */
        internal fun downgradeBackup(context: Context, name: String, version: Int): File =
            DatabaseBackups.backupFile(context, name, version)

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.apply {
                    execSQL("CREATE TABLE IF NOT EXISTS `messages` (`id` INTEGER PRIMARY KEY NOT NULL, `body` TEXT NOT NULL, `type` INTEGER NOT NULL, `participants` TEXT NOT NULL, `date` INTEGER NOT NULL, `read` INTEGER NOT NULL, `thread_id` INTEGER NOT NULL, `is_mms` INTEGER NOT NULL, `attachment` TEXT, `sender_name` TEXT NOT NULL, `sender_photo_uri` TEXT NOT NULL, `subscription_id` INTEGER NOT NULL)")

                    execSQL("CREATE TABLE IF NOT EXISTS `message_attachments` (`id` INTEGER PRIMARY KEY NOT NULL, `text` TEXT NOT NULL, `attachments` TEXT NOT NULL)")

                    execSQL("CREATE TABLE IF NOT EXISTS `attachments` (`id` INTEGER PRIMARY KEY AUTOINCREMENT, `message_id` INTEGER NOT NULL, `uri_string` TEXT NOT NULL, `mimetype` TEXT NOT NULL, `width` INTEGER NOT NULL, `height` INTEGER NOT NULL, `filename` TEXT NOT NULL)")
                    execSQL("CREATE UNIQUE INDEX `index_attachments_message_id` ON `attachments` (`message_id`)")
                }
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.apply {
                    execSQL("CREATE TABLE conversations_new (`thread_id` INTEGER NOT NULL PRIMARY KEY, `snippet` TEXT NOT NULL, `date` INTEGER NOT NULL, `read` INTEGER NOT NULL, `title` TEXT NOT NULL, `photo_uri` TEXT NOT NULL, `is_group_conversation` INTEGER NOT NULL, `phone_number` TEXT NOT NULL)")

                    execSQL(
                        "INSERT OR IGNORE INTO conversations_new (thread_id, snippet, date, read, title, photo_uri, is_group_conversation, phone_number) " +
                                "SELECT thread_id, snippet, date, read, title, photo_uri, is_group_conversation, phone_number FROM conversations"
                    )

                    execSQL("DROP TABLE conversations")

                    execSQL("ALTER TABLE conversations_new RENAME TO conversations")

                    execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_conversations_id` ON `conversations` (`thread_id`)")
                }
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.apply {
                    execSQL("ALTER TABLE messages ADD COLUMN status INTEGER NOT NULL DEFAULT -1")
                }
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.apply {
                    execSQL("ALTER TABLE messages ADD COLUMN is_scheduled INTEGER NOT NULL DEFAULT 0")
                    execSQL("ALTER TABLE conversations ADD COLUMN is_scheduled INTEGER NOT NULL DEFAULT 0")
                }
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.apply {
                    execSQL("ALTER TABLE conversations ADD COLUMN uses_custom_title INTEGER NOT NULL DEFAULT 0")
                }
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.apply {
                    execSQL("ALTER TABLE messages ADD COLUMN sender_phone_number TEXT NOT NULL DEFAULT ''")
                }
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.apply {
                    execSQL("ALTER TABLE conversations ADD COLUMN archived INTEGER NOT NULL DEFAULT 0")
                    execSQL("CREATE TABLE IF NOT EXISTS `recycle_bin_messages` (`id` INTEGER NOT NULL PRIMARY KEY, `deleted_ts` INTEGER NOT NULL)")
                    execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_recycle_bin_messages_id` ON `recycle_bin_messages` (`id`)")
                }
            }
        }

        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.apply {
                    execSQL("CREATE TABLE IF NOT EXISTS `drafts` (`thread_id` INTEGER NOT NULL PRIMARY KEY, `body` TEXT NOT NULL, `date` INTEGER NOT NULL)")
                }
            }
        }

        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.apply {
                    execSQL("ALTER TABLE conversations ADD COLUMN unread_count INTEGER NOT NULL DEFAULT 0")
                }
            }
        }

        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_messages_thread_id_date` " +
                        "ON `messages` (`thread_id`, `date`)"
                )
            }
        }

        /**
         * DL-01: SMS and MMS ids come from two independent provider sequences, so keying `messages` and
         * `recycle_bin_messages` by id alone let SMS 57 and MMS 57 overwrite each other. Both tables are rebuilt with
         * `PRIMARY KEY(id, is_mms)` (see MessageKey). Every version-11 row is kept: ids were unique then, so they are
         * unique pairs now. A recycle-bin entry takes is_mms from the message it points at (an orphan entry, whose
         * message is gone, becomes an SMS entry; it pointed at nothing either way).
         */
        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE `messages_new` (`id` INTEGER NOT NULL, `body` TEXT NOT NULL, `type` INTEGER NOT NULL, " +
                        "`status` INTEGER NOT NULL, `participants` TEXT NOT NULL, `date` INTEGER NOT NULL, " +
                        "`read` INTEGER NOT NULL, `thread_id` INTEGER NOT NULL, `is_mms` INTEGER NOT NULL, " +
                        "`attachment` TEXT, `sender_phone_number` TEXT NOT NULL, `sender_name` TEXT NOT NULL, " +
                        "`sender_photo_uri` TEXT NOT NULL, `subscription_id` INTEGER NOT NULL, " +
                        "`is_scheduled` INTEGER NOT NULL, PRIMARY KEY(`id`, `is_mms`))"
                )
                val columns = "`id`, `body`, `type`, `status`, `participants`, `date`, `read`, `thread_id`, `is_mms`, " +
                    "`attachment`, `sender_phone_number`, `sender_name`, `sender_photo_uri`, `subscription_id`, " +
                    "`is_scheduled`"
                db.execSQL("INSERT INTO `messages_new` ($columns) SELECT $columns FROM `messages`")

                db.execSQL(
                    "CREATE TABLE `recycle_bin_messages_new` (`id` INTEGER NOT NULL, `is_mms` INTEGER NOT NULL, " +
                        "`deleted_ts` INTEGER NOT NULL, PRIMARY KEY(`id`, `is_mms`))"
                )
                db.execSQL(
                    "INSERT INTO `recycle_bin_messages_new` (`id`, `is_mms`, `deleted_ts`) " +
                        "SELECT r.`id`, COALESCE((SELECT m.`is_mms` FROM `messages` m WHERE m.`id` = r.`id`), 0), " +
                        "r.`deleted_ts` FROM `recycle_bin_messages` r"
                )

                db.execSQL("DROP TABLE `messages`")
                db.execSQL("ALTER TABLE `messages_new` RENAME TO `messages`")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_messages_thread_id_date` ON `messages` (`thread_id`, `date`)"
                )
                db.execSQL("DROP TABLE `recycle_bin_messages`")
                db.execSQL("ALTER TABLE `recycle_bin_messages_new` RENAME TO `recycle_bin_messages`")
            }
        }

        /**
         * Stars, repeating scheduled messages, labels and message reminders. New tables only: `messages` is a provider
         * cache that gets rebuilt, so none of this can live in it. MessagesDatabaseRecovery copies all of them.
         */
        private val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `starred_messages` (`id` INTEGER NOT NULL, " +
                        "`is_mms` INTEGER NOT NULL, `thread_id` INTEGER NOT NULL, `address` TEXT NOT NULL, " +
                        "`date` INTEGER NOT NULL, " +
                        "`body_hash` INTEGER NOT NULL, `starred_ts` INTEGER NOT NULL, `snapshot_body` TEXT NOT NULL, " +
                        "PRIMARY KEY(`id`, `is_mms`))"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `scheduled_repeats` (`message_id` INTEGER NOT NULL, " +
                        "`rule` TEXT NOT NULL, `calendar` TEXT NOT NULL, `anchor_day` INTEGER NOT NULL, " +
                        "`anchor_month` INTEGER NOT NULL, PRIMARY KEY(`message_id`))"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `labels` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`name` TEXT NOT NULL, `color` INTEGER NOT NULL, `sort_order` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `conversation_labels` (`thread_id` INTEGER NOT NULL, " +
                        "`label_id` INTEGER NOT NULL, PRIMARY KEY(`thread_id`, `label_id`))"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `message_reminders` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `message_id` INTEGER NOT NULL, " +
                        "`is_mms` INTEGER NOT NULL, `thread_id` INTEGER NOT NULL, " +
                        "`remind_at` INTEGER NOT NULL, `snapshot_body` TEXT NOT NULL)"
                )
            }
        }

        /**
         * Private notes on messages. A new table only, with the note key (see MessageNoteKey) as its primary key and an
         * index on the chat. Like stars, notes live outside the rebuildable `messages` cache; MessagesDatabaseRecovery
         * copies them.
         */
        private val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `message_notes` (`note_key` TEXT NOT NULL, " +
                        "`thread_id` INTEGER NOT NULL, `message_date` INTEGER NOT NULL, `note` TEXT NOT NULL, " +
                        "`created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`note_key`))"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_message_notes_thread_id` ON `message_notes` (`thread_id`)"
                )
            }
        }

        /** Every migration, oldest first. Declared after them so none is still null when the array is built. */
        internal val ALL_MIGRATIONS: Array<Migration> = arrayOf(
            MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6,
            MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12,
            MIGRATION_12_13, MIGRATION_13_14,
        )
    }
}
