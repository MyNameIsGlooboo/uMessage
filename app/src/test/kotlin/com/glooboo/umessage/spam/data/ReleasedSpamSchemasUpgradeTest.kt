package com.glooboo.umessage.spam.data

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.spam.DecisionReason
import com.glooboo.umessage.spam.ForwardStatus
import com.glooboo.umessage.spam.ListKind
import com.glooboo.umessage.spam.MmsMedia
import com.glooboo.umessage.spam.RuleType
import com.glooboo.umessage.testing.migrationTestHelper
import com.glooboo.umessage.testing.offMainThread
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * J13 (upgrade safety): spamless.db as each released version left it, straight to the current schema through the
 * app's own [SpamDatabase.ALL_MIGRATIONS], then read through the app's DAOs. Released schemas: 5 (0.3.0 to 0.4.4),
 * 6 (0.4.5), 7 (0.4.6), 8 (0.4.7 to 0.4.14). Each file holds what a user of that version has: block and allow rules
 * (one number blocked on both SIMs as two rows, a word stored twice, a sender name for one SIM, an automatic allow),
 * held SMS and a held MMS with its kept content, events of the weekly summary, a queued text, a forward rule for SIM 2
 * and its log. Data is made up (555 numbers only).
 */
@RunWith(AndroidJUnit4::class)
class ReleasedSpamSchemasUpgradeTest {

    @get:Rule
    val helper = migrationTestHelper(SpamDatabase::class.java)

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    @After
    fun deleteFiles() {
        context.getDatabasePath(TEST_DB).parentFile
            ?.listFiles { _, name -> name.startsWith(TEST_DB) }
            ?.forEach { it.delete() }
    }

    @Test
    fun theSchemaOf030To044UpgradesStraightToCurrent() = upgradeFrom(5)

    @Test
    fun theSchemaOf045UpgradesStraightToCurrent() = upgradeFrom(6)

    @Test
    fun theSchemaOf046UpgradesStraightToCurrent() = upgradeFrom(7)

    @Test
    fun theSchemaOf047To0414UpgradesStraightToCurrent() = upgradeFrom(8)

    private fun upgradeFrom(version: Int) {
        helper.createDatabase(TEST_DB, version).use { seed(it, version) }

        helper.runMigrationsAndValidate(TEST_DB, SpamDatabase.VERSION, true, *SpamDatabase.ALL_MIGRATIONS)
            .use { checkRows(it, version) }

        val db = SpamDatabase.build(context, TEST_DB)
        try {
            offMainThread { checkDaos(db, version) }
        } finally {
            db.close()
        }
    }

    private fun SupportSQLiteDatabase.rule(
        id: Long, kind: String, type: String, pattern: String, sim: Int?, note: String,
    ) = execSQL(
            "INSERT INTO spam_rules (id, kind, type, pattern, subscriptionId, note, createdAt) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?)",
            arrayOf<Any?>(id, kind, type, pattern, sim, note, id * 100),
        )

    private fun SupportSQLiteDatabase.held(
        id: Long, address: String, body: String, mms: Boolean, reason: String, ruleId: Long?, media: String,
        file: String?,
    ) = execSQL(
        "INSERT INTO blocked_messages (id, address, body, subscriptionId, isMms, receivedAt, reason, ruleId, media, " +
            "mediaFile) VALUES (?, ?, ?, 1, ?, ?, ?, ?, ?, ?)",
        arrayOf<Any?>(id, address, body, if (mms) 1 else 0, id * 1_000, reason, ruleId, media, file),
    )

    private fun seed(db: SupportSQLiteDatabase, version: Int) {
        // The same number blocked on SIM 1 by the user and on SIM 2 by a held message: one rule after the update.
        db.rule(1, "BLOCK", "NUMBER", SPAMMER, 1, "@spamless:blocked_by_you")
        db.rule(2, "BLOCK", "NUMBER", SPAMMER, 2, "")
        db.rule(3, "BLOCK", "KEYWORD", "lottery", null, "")
        db.rule(4, "ALLOW", "SENDER_ID", "MYBANK", 1, "My bank")
        db.rule(5, "BLOCK", "PREFIX", "+1555000", null, "")
        db.rule(6, "ALLOW", "NUMBER", FRIEND, null, "@spamless:allowed_from_keyword")
        // The old unique index let two rows for both SIMs (NULL) through: the same word twice, merged into rule 3.
        db.rule(7, "BLOCK", "KEYWORD", "lottery", null, "")
        if (version >= RULE_STATS_SINCE) {
            db.execSQL("UPDATE spam_rules SET hitCount = 3, lastHit = 7000 WHERE id = 1")
            db.execSQL("UPDATE spam_rules SET hitCount = 2, lastHit = 9000 WHERE id = 2")
        }

        db.held(1, SPAMMER, "Win a prize", mms = false, "BLOCK_RULE", ruleId = 2, "NONE", file = null)
        db.held(2, STRANGER, "", mms = true, "UNKNOWN_SENDER", ruleId = null, "STORED", MEDIA_FILE)
        db.held(3, STRANGER, "lottery tickets", mms = false, "BLOCK_RULE", ruleId = 7, "NONE", file = null)
        if (version >= UNREAD_SINCE) db.execSQL("UPDATE blocked_messages SET unread = 1 WHERE id = 1")

        db.execSQL("INSERT INTO spam_events (id, type, timestamp) VALUES (1, 'BLOCKED', 1000), (2, 'RESTORED', 2000)")
        db.execSQL(
            "INSERT INTO pending_sms (id, address, subject, body, date, subscriptionId, status, createdAt) " +
                "VALUES (1, '$FRIEND', '', 'On my way', 5000, 1, 0, 5001)"
        )
        if (version >= DATE_SENT_SINCE) db.execSQL("UPDATE pending_sms SET dateSent = 4990 WHERE id = 1")
        db.execSQL(
            "INSERT INTO forward_rules (id, senderType, senderPattern, bodyType, bodyPattern, windowStart, " +
                "windowEnd, subscriptionId, targets, sendFrom, includeCodes, enabled, note, createdAt) VALUES (1, " +
                "'SENDER_ID', 'MYBANK', NULL, '', NULL, NULL, 2, '$TARGET', 'SAME', 1, 1, 'Bank to my other phone', 10)"
        )
        db.execSQL(
            "INSERT INTO forward_log (id, at, updatedAt, ruleId, sourceAddress, sourceDate, sourceIsMms, target, " +
                "subscriptionId, status, errorCode, attempts) VALUES (1, 6000, 6001, 1, 'MYBANK', 5900, 0, " +
                "'$TARGET', 2, 'SENT', NULL, 1)"
        )
    }

    private fun checkRows(db: SupportSQLiteDatabase, version: Int) {
        val (hits, lastHit) = if (version >= RULE_STATS_SINCE) 5 to 9_000L else 0 to 0L
        assertThat(
            rows(db, "SELECT id, kind, pattern, subscriptionId, note, hitCount, lastHit, acknowledged FROM spam_rules")
        ).containsExactly(
            "1|BLOCK|$SPAMMER|null|@spamless:blocked_by_you|$hits|$lastHit|0",
            "3|BLOCK|lottery|null||0|0|0",
            "4|ALLOW|MYBANK|null|My bank|0|0|0",
            "5|BLOCK|+1555000|null||0|0|0",
            "6|ALLOW|$FRIEND|null|@spamless:allowed_from_keyword|0|0|1",
        ).inOrder()

        val unread = if (version >= UNREAD_SINCE) 1 else 0
        assertThat(
            rows(db, "SELECT id, ruleId, media, mediaFile, unread, migratedMsgId, migrateTries FROM blocked_messages")
        ).containsExactly(
            "1|1|NONE|null|$unread|null|0",
            "2|null|STORED|$MEDIA_FILE|0|null|0",
            "3|3|NONE|null|0|null|0",
        ).inOrder()

        assertThat(rows(db, "SELECT type, timestamp FROM spam_events")).containsExactly("BLOCKED|1000", "RESTORED|2000")
        val dateSent = if (version >= DATE_SENT_SINCE) 4990 else 0
        assertThat(rows(db, "SELECT body, dateSent, approved FROM pending_sms"))
            .containsExactly("On my way|$dateSent|0")
        assertThat(rows(db, "SELECT senderPattern, subscriptionId, targets, enabled FROM forward_rules"))
            .containsExactly("MYBANK|2|$TARGET|1")
        assertThat(rows(db, "SELECT ruleId, target, status FROM forward_log")).containsExactly("1|$TARGET|SENT")
        assertThat(rows(db, "SELECT COUNT(*) FROM allow_group_members")).containsExactly("0")
    }

    private fun checkDaos(db: SpamDatabase, version: Int) {
        val dao = db.spamDao()
        val rules = dao.getRules().map { it.toRule() }
        assertThat(rules.map { it.subscriptionId }.toSet()).containsExactly(null)
        assertThat(dao.findRule(ListKind.BLOCK, RuleType.NUMBER, SPAMMER)?.id).isEqualTo(1L)
        assertThat(dao.findRule(ListKind.ALLOW, RuleType.SENDER_ID, "MYBANK")?.note).isEqualTo("My bank")
        assertThat(rules.single { it.id == 6L }.acknowledged).isTrue()

        val held = dao.getAllBlocked().map { it.toModel() }.associateBy { it.id }
        assertThat(held.keys).containsExactly(1L, 2L, 3L)
        assertThat(held.getValue(1).reason).isEqualTo(DecisionReason.BLOCK_RULE)
        assertThat(held.getValue(1).unread).isEqualTo(version >= UNREAD_SINCE)
        assertThat(held.getValue(2).media).isEqualTo(MmsMedia.STORED)
        assertThat(held.getValue(2).hasStoredMedia).isTrue()
        assertThat(held.values.map { it.migratedMsgId }.toSet()).containsExactly(null)

        assertThat(dao.getPending().map { it.body }).containsExactly("On my way")
        val forward = db.forwardDao().getAll().single()
        assertThat(forward.subscriptionId).isEqualTo(2)
        assertThat(forward.toModel().targets).containsExactly(TARGET)
        assertThat(db.forwardLogDao().withStatus(ForwardStatus.SENT).map { it.target }).containsExactly(TARGET)
        assertThat(dao.getMembers()).isEmpty()
    }

    private fun rows(db: SupportSQLiteDatabase, sql: String): List<String> = db.query("$sql ORDER BY 1").use { c ->
        buildList {
            while (c.moveToNext()) add((0 until c.columnCount).joinToString("|") { c.getString(it) ?: "null" })
        }
    }

    private companion object {
        const val TEST_DB = "released-spam-upgrade-test.db"
        const val SPAMMER = "+15550009876"
        const val STRANGER = "+15550001299"
        const val FRIEND = "+15550001234"
        const val TARGET = "+15550004321"
        const val MEDIA_FILE = "0f9c2a3e-1111-2222-3333-444455556666"
        const val UNREAD_SINCE = 6
        const val DATE_SENT_SINCE = 7
        const val RULE_STATS_SINCE = 8
    }
}
