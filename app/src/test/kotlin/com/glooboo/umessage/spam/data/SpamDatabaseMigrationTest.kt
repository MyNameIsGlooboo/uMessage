package com.glooboo.umessage.spam.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.spam.MmsMedia
import com.glooboo.umessage.spam.RestoreOutcome
import com.glooboo.umessage.testing.migrationTestHelper
import com.glooboo.umessage.testing.offMainThread
import com.google.common.truth.Truth.assertThat
import com.glooboo.umessage.databases.DatabaseBackups
import com.glooboo.umessage.spam.DecisionReason
import com.glooboo.umessage.spam.ForwardSim
import com.glooboo.umessage.spam.ListKind
import com.glooboo.umessage.spam.RuleType
import java.io.File
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Upgrade and downgrade tests for spamless.db.
 *
 * Only schema 2 was ever exported by Room. schemas/.../SpamDatabase/1.json is a reconstruction: schema 2 without the
 * pending_sms table, which is exactly what MIGRATION_1_2 adds (the other entities have not changed since version 1).
 * Its identity hash is a placeholder.
 */
@RunWith(AndroidJUnit4::class)
class SpamDatabaseMigrationTest {

    @get:Rule
    val helper = migrationTestHelper(SpamDatabase::class.java)

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    @After
    fun deleteFiles() {
        context.getDatabasePath(TEST_DB).parentFile?.listFiles { _, name ->
            name.startsWith(TEST_DB) || name.startsWith(OTHER_DB)
        }?.forEach { it.delete() }
    }

    @Test
    fun migratesFromVersionOneToLatestAndKeepsRules() {
        helper.createDatabase(TEST_DB, 1).use { db ->
            db.execSQL(
                "INSERT INTO spam_rules (id, kind, type, pattern, subscriptionId, note, createdAt) " +
                    "VALUES (1, 'BLOCK', 'KEYWORD', 'lottery', NULL, '', 1000)"
            )
        }

        helper.runMigrationsAndValidate(TEST_DB, LATEST, true, *SpamDatabase.ALL_MIGRATIONS).use { db ->
            db.query("SELECT pattern FROM spam_rules WHERE id = 1").use { cursor ->
                assertThat(cursor.moveToFirst()).isTrue()
                assertThat(cursor.getString(0)).isEqualTo("lottery")
            }
            db.query("SELECT COUNT(*) FROM pending_sms").use { cursor ->
                assertThat(cursor.moveToFirst()).isTrue()
                assertThat(cursor.getInt(0)).isEqualTo(0)
            }
        }
    }

    /** SE-29: version 3 records what is kept of a blocked MMS. Older MMS rows kept nothing; SMS rows have no media. */
    @Test
    fun migratesFromVersionTwoAndMarksOldMmsAsNotKept() {
        helper.createDatabase(TEST_DB, 2).use { db ->
            db.execSQL(
                "INSERT INTO blocked_messages (id, address, body, subscriptionId, isMms, receivedAt, reason, ruleId) " +
                    "VALUES (1, '+15551234567', 'MMS blocked', 1, 1, 1000, 'UNKNOWN_SENDER', NULL), " +
                    "(2, '+15551234567', 'Hello', 1, 0, 2000, 'BLOCK_RULE', 7)"
            )
        }

        helper.runMigrationsAndValidate(TEST_DB, LATEST, true, *SpamDatabase.ALL_MIGRATIONS).use { db ->
            db.query("SELECT id, media, mediaFile, body FROM blocked_messages ORDER BY id").use { cursor ->
                assertThat(cursor.moveToNext()).isTrue()
                assertThat(cursor.getString(1)).isEqualTo("MISSING")
                assertThat(cursor.isNull(2)).isTrue()
                assertThat(cursor.getString(3)).isEqualTo("MMS blocked")
                assertThat(cursor.moveToNext()).isTrue()
                assertThat(cursor.getString(1)).isEqualTo("NONE")
                assertThat(cursor.getString(3)).isEqualTo("Hello")
            }
        }

        // And Room reads the migrated rows into the model the Blocked screen uses.
        val db = SpamDatabase.build(context, TEST_DB)
        try {
            val rows = offMainThread { db.spamDao().getAllBlocked() }.associateBy { it.id }
            assertThat(rows.getValue(1).toModel().expectedRestore).isEqualTo(RestoreOutcome.NOTHING)
            assertThat(rows.getValue(2).toModel().media).isEqualTo(MmsMedia.NONE)
        } finally {
            db.close()
        }
    }

    @Test
    fun downgradeOpensAndKeepsRulesBlockedMessagesAndEvents() {
        withDatabase { dao ->
            dao.insertRule(rule("lottery"))
            dao.insertBlocked(blocked("5550100", "win now"))
            dao.insertEvent(SpamEventEntity(type = SpamEventType.BLOCKED, timestamp = 7))
        }
        // A newer build wrote this file: one extra column, one extra table, and a rule kind this build lacks.
        raw { db ->
            db.execSQL("ALTER TABLE blocked_messages ADD COLUMN attachments TEXT NOT NULL DEFAULT ''")
            db.execSQL("CREATE TABLE future_things (id INTEGER PRIMARY KEY)")
            db.execSQL(
                "INSERT INTO spam_rules (kind, type, pattern, subscriptionId, note, createdAt) " +
                    "VALUES ('FUTURE', 'KEYWORD', 'unknown kind', NULL, '', 1)"
            )
            db.version = NEWER
        }

        repeat(2) { // and the second open neither crashes nor duplicates anything
            withDatabase { dao ->
                assertThat(dao.getRules().map { it.pattern }).containsExactly("lottery")
                assertThat(dao.getAllBlocked().map { it.body }).containsExactly("win now")
            }
        }
        // Everything this schema can hold was copied, so the newer file is kept only as a safety copy.
        assertThat(backup(NEWER).exists()).isFalse()
        assertThat(file("$TEST_DB.v$NEWER-imported").exists()).isTrue()
        raw { db ->
            db.rawQuery("SELECT COUNT(*) FROM spam_events", null).use {
                it.moveToFirst()
                assertThat(it.getInt(0)).isEqualTo(1)
            }
        }
    }

    @Test
    fun downgradeWithATableThisSchemaCannotHoldKeepsTheBackupForTheNextUpgrade() {
        withDatabase { dao ->
            dao.insertRule(rule("lottery"))
            dao.insertBlocked(blocked("5550100", "win now"))
        }
        // The newer schema dropped blocked_messages.reason, which this one requires.
        raw { db ->
            db.execSQL(
                "CREATE TABLE blocked_new (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, address TEXT NOT NULL, " +
                    "body TEXT NOT NULL, subscriptionId INTEGER NOT NULL, isMms INTEGER NOT NULL, " +
                    "receivedAt INTEGER NOT NULL, ruleId INTEGER)"
            )
            db.execSQL(
                "INSERT INTO blocked_new SELECT id, address, body, subscriptionId, isMms, receivedAt, ruleId " +
                    "FROM blocked_messages"
            )
            db.execSQL("DROP TABLE blocked_messages")
            db.execSQL("ALTER TABLE blocked_new RENAME TO blocked_messages")
            db.version = NEWER
        }

        withDatabase { dao ->
            assertThat(dao.getRules().map { it.pattern }).containsExactly("lottery")
            assertThat(dao.getAllBlocked()).isEmpty()
        }
        assertThat(backup(NEWER).exists()).isTrue()
    }

    @Test
    fun upgradeMergesABackupIntoTheLiveDatabase() {
        // The backup, here of this schema: rule A, rule B, blocked X and Y.
        SpamDatabase.build(context, OTHER_DB).also { db ->
            offMainThread {
                db.spamDao().insertRule(rule("A"))
                db.spamDao().insertRule(rule("B"))
                db.spamDao().insertBlocked(blocked("1", "X"))
                db.spamDao().insertBlocked(blocked("2", "Y"))
            }
        }.close()
        // The live database the older build used meanwhile: rule B under id 1 and blocked X under id 1.
        withDatabase { dao ->
            dao.insertRule(rule("B"))
            dao.insertBlocked(blocked("1", "X"))
            dao.insertBlocked(blocked("3", "Z"))
        }
        DatabaseBackups.copyWithCompanions(context.getDatabasePath(OTHER_DB), backup(SpamDatabase.VERSION))

        withDatabase { dao ->
            // A clashes with B's id and gets a new one; B and X are already there; Y is added.
            assertThat(dao.getRules().map { it.pattern }).containsExactly("A", "B")
            assertThat(dao.getAllBlocked().map { it.body }).containsExactly("X", "Y", "Z")
            assertThat(dao.getAllBlocked().map { it.id }.toSet()).hasSize(3)
        }
        assertThat(backup(SpamDatabase.VERSION).exists()).isFalse()
        withDatabase { dao -> assertThat(dao.getAllBlocked()).hasSize(3) } // used once
    }

    /** Wave 8: schema 4 adds the forward_rules table; everything else is kept as it was. */
    @Test
    fun migratesFromVersionThreeAndAddsAnEmptyForwardTable() {
        helper.createDatabase(TEST_DB, 3).use { db ->
            db.execSQL(
                "INSERT INTO spam_rules (id, kind, type, pattern, subscriptionId, note, createdAt) " +
                    "VALUES (1, 'BLOCK', 'KEYWORD', 'lottery', NULL, '', 1000)"
            )
        }

        helper.runMigrationsAndValidate(TEST_DB, LATEST, true, *SpamDatabase.ALL_MIGRATIONS).use { db ->
            db.query("SELECT COUNT(*) FROM spam_rules").use { assertThat(it.moveToFirst() && it.getInt(0) == 1).isTrue() }
            db.query("SELECT COUNT(*) FROM forward_rules").use { assertThat(it.moveToFirst() && it.getInt(0) == 0).isTrue() }
            // The column defaults the migration declares are the ones Room expects (validated above) and work.
            db.execSQL("INSERT INTO forward_rules (targets, createdAt) VALUES ('+15550001111', 5)")
            db.query("SELECT sendFrom, includeCodes, enabled, senderPattern FROM forward_rules").use {
                assertThat(it.moveToFirst()).isTrue()
                assertThat(it.getString(0)).isEqualTo("SAME")
                assertThat(it.getInt(1)).isEqualTo(0)
                assertThat(it.getInt(2)).isEqualTo(1)
                assertThat(it.getString(3)).isEmpty()
            }
        }
    }

    @Test
    fun forwardRulesRoundTripThroughRoom() {
        withForwards { dao ->
            dao.insert(forward("+15550001111\n+15550002222", includeCodes = true))
        }
        withForwards { dao ->
            val rule = dao.getAll().single().toModel()
            assertThat(rule.targets).containsExactly("+15550001111", "+15550002222").inOrder()
            assertThat(rule.senderType).isEqualTo(RuleType.NUMBER)
            assertThat(rule.bodyType).isNull()
            assertThat(rule.windowStart).isEqualTo(60)
            assertThat(rule.sendFrom).isEqualTo(ForwardSim.SIM2)
            assertThat(rule.includeCodes).isTrue()
        }
    }

    /** A newer file (schema 5) opened by this build: its forward rules are copied, unknown SIM choices skipped. */
    @Test
    fun downgradeCopiesForwardRules() {
        withForwards { dao -> dao.insert(forward("+15550001111")) }
        raw { db ->
            db.execSQL(
                "INSERT INTO forward_rules (senderType, senderPattern, targets, sendFrom, createdAt) " +
                    "VALUES ('NUMBER', '1', '+15550009999', 'SIM3', 2)"
            )
            db.version = NEWER
        }
        withForwards { dao -> assertThat(dao.getAll().map { it.targets }).containsExactly("+15550001111") }
    }

    /** A backup of this schema merges its forward rules back without doubling ones already there. */
    @Test
    fun upgradeMergesForwardRulesFromABackup() {
        SpamDatabase.build(context, OTHER_DB).also { db ->
            offMainThread {
                db.forwardDao().insert(forward("+15550001111"))
                db.forwardDao().insert(forward("+15550002222"))
            }
        }.close()
        withForwards { dao -> dao.insert(forward("+15550001111")) }
        DatabaseBackups.copyWithCompanions(context.getDatabasePath(OTHER_DB), backup(SpamDatabase.VERSION))

        withForwards { dao ->
            assertThat(dao.getAll().map { it.targets }).containsExactly("+15550001111", "+15550002222")
        }
    }

    /**
     * Schema 4 -> a schema-3 build -> schema 4 again. The schema-3 build copied every table it knows and renamed the
     * file `-imported`, so the forward rules exist only there. They come back once, and the file is retired.
     */
    @Test
    fun forwardRulesComeBackAfterATripToSchemaThree() {
        SpamDatabase.build(context, OTHER_DB).also { db ->
            offMainThread { db.forwardDao().insert(forward("+15550001111")) }
        }.close()
        val imported = file("$TEST_DB.v${SpamDatabase.VERSION}-imported")
        DatabaseBackups.copyWithCompanions(context.getDatabasePath(OTHER_DB), imported)

        repeat(2) {
            withForwards { dao -> assertThat(dao.getAll().map { it.targets }).containsExactly("+15550001111") }
        }
        assertThat(imported.exists()).isFalse()
        assertThat(file("$TEST_DB.v${SpamDatabase.VERSION}-restored").exists()).isTrue()
    }

    @Test
    fun versionConstantMatchesTheLatestSchema() {
        assertThat(SpamDatabase.VERSION).isEqualTo(LATEST)
    }

    /**
     * SE-29 across a downgrade: an older build (schema 2) copied the blocked MMS without its media columns, and its
     * upgrade marked it MISSING. Merging the backup back must reattach the kept file, not add a second copy.
     */
    @Test
    fun upgradeAfterADowngradeReattachesKeptMmsContent() {
        SpamDatabase.build(context, OTHER_DB).also { db ->
            offMainThread {
                db.spamDao().insertBlocked(blocked("1", "photo").copy(isMms = true, media = MmsMedia.STORED, mediaFile = FILE))
                db.spamDao().insertBlocked(blocked("2", "other").copy(isMms = true, media = MmsMedia.STORED, mediaFile = "aa$FILE"))
            }
        }.close()
        // What the live database holds after schema 2 and MIGRATION_2_3: same message, nothing kept.
        withDatabase { dao -> dao.insertBlocked(blocked("1", "photo").copy(isMms = true, media = MmsMedia.MISSING)) }
        DatabaseBackups.copyWithCompanions(context.getDatabasePath(OTHER_DB), backup(SpamDatabase.VERSION))

        withDatabase { dao ->
            val all = dao.getAllBlocked().map { it.toModel() }
            assertThat(all.map { it.body }).containsExactly("photo", "other")
            val rows = all.associateBy { it.body }
            assertThat(rows.getValue("photo").media).isEqualTo(MmsMedia.STORED)
            assertThat(rows.getValue("photo").mediaFile).isEqualTo(FILE)
            assertThat(rows.getValue("other").mediaFile).isEqualTo("aa$FILE")
        }
    }

    /** A newer file imported into this schema keeps its media references and skips media states it cannot read. */
    @Test
    fun downgradeKeepsMediaReferencesOfKeptMms() {
        withDatabase { dao ->
            dao.insertBlocked(blocked("1", "photo").copy(isMms = true, media = MmsMedia.STORED, mediaFile = FILE))
        }
        raw { db ->
            db.execSQL(
                "INSERT INTO blocked_messages (address, body, subscriptionId, isMms, receivedAt, reason, ruleId, " +
                    "media, mediaFile) VALUES ('2', 'future', 0, 1, 1, 'BLOCK_RULE', NULL, 'HOLOGRAM', NULL)"
            )
            db.version = NEWER
        }
        withDatabase { dao ->
            val row = dao.getAllBlocked().single().toModel()
            assertThat(row.mediaFile).isEqualTo(FILE)
            assertThat(row.hasStoredMedia).isTrue()
        }
    }

    private fun withDatabase(block: (SpamDao) -> Unit) {
        val db = SpamDatabase.build(context, TEST_DB)
        try {
            offMainThread { block(db.spamDao()) }
        } finally {
            db.close()
        }
    }

    private fun withForwards(block: (ForwardDao) -> Unit) {
        val db = SpamDatabase.build(context, TEST_DB)
        try {
            offMainThread { block(db.forwardDao()) }
        } finally {
            db.close()
        }
    }

    private fun forward(targets: String, includeCodes: Boolean = false) = ForwardRuleEntity(
        senderType = RuleType.NUMBER, senderPattern = "07121234567", bodyType = null, bodyPattern = "",
        windowStart = 60, windowEnd = 120, subscriptionId = null, targets = targets, sendFrom = ForwardSim.SIM2,
        includeCodes = includeCodes, enabled = true, note = "", createdAt = 1,
    )

    private fun raw(block: (SQLiteDatabase) -> Unit) =
        SQLiteDatabase.openDatabase(context.getDatabasePath(TEST_DB).path, null, SQLiteDatabase.OPEN_READWRITE)
            .use(block)

    private fun file(name: String) = File(context.getDatabasePath(TEST_DB).parentFile, name)

    private fun backup(version: Int) = DatabaseBackups.backupFile(context, TEST_DB, version)

    private fun rule(pattern: String) = SpamRuleEntity(
        kind = ListKind.BLOCK, type = RuleType.KEYWORD, pattern = pattern, subscriptionId = null, note = "",
        createdAt = 1,
    )

    private fun blocked(address: String, body: String) = BlockedMessageEntity(
        address = address, body = body, subscriptionId = 0, isMms = false, receivedAt = 1,
        reason = DecisionReason.BLOCK_RULE, ruleId = null,
    )

    private companion object {
        const val TEST_DB = "spam-migration-test.db"
        const val OTHER_DB = "spam-migration-other.db"
        const val LATEST = 5
        const val NEWER = SpamDatabase.VERSION + 1
        const val FILE = "0f9c2a3e-1111-2222-3333-444455556666"
    }
}
