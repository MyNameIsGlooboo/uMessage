package com.glooboo.umessage.spam.data

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.spam.AllowGroup
import com.glooboo.umessage.spam.ListKind
import com.glooboo.umessage.spam.MemberSource
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
 * spamless.db 8 to 9 (provider truth, 7.2; S15, U15) on a real schema-8 file: rules for one SIM and for both, the
 * same pattern on two SIMs, a user rule merged with an automatic one, an allow on one SIM against a block on the
 * other, a made-up Iranian prefix on two SIMs, held messages that name merged rules, and a forward rule for one SIM.
 * Every number is made up (the 555 range; 0906 for the Iranian shape).
 */
@RunWith(AndroidJUnit4::class)
class SpamProviderTruthMigrationTest {

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

    private fun SupportSQLiteDatabase.rule(
        id: Long, kind: String, type: String, pattern: String, sim: Int?, note: String,
        createdAt: Long = id * 100, hits: Int = 0, lastHit: Long = 0,
    ) = execSQL(
        "INSERT INTO spam_rules (id, kind, type, pattern, subscriptionId, note, createdAt, hitCount, lastHit) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
        arrayOf(id, kind, type, pattern, sim, note, createdAt, hits, lastHit),
    )

    private fun SupportSQLiteDatabase.held(id: Long, ruleId: Long?) = execSQL(
        "INSERT INTO blocked_messages (id, address, body, subscriptionId, isMms, receivedAt, reason, ruleId) " +
            "VALUES (?, '+15550001234', 'held text', 1, 0, ?, 'BLOCK_RULE', ?)",
        arrayOf(id, id * 1000, ruleId),
    )

    private fun seedVersion8() {
        helper.createDatabase(TEST_DB, 8).use { db ->
            // The same number blocked on SIM 1 by the user, on SIM 2 with no note, and on both by a blocked word.
            db.rule(1, "BLOCK", "NUMBER", NUMBER, 1, "@spamless:blocked_by_you", hits = 3, lastHit = 1_000)
            db.rule(2, "BLOCK", "NUMBER", NUMBER, 2, "", hits = 4, lastHit = 3_000)
            db.rule(3, "BLOCK", "NUMBER", NUMBER, null, "@spamless:blocked_from_keyword", hits = 1, lastHit = 2_000)
            // A sender name allowed automatically on SIM 1 and by the user on SIM 2: the user's note wins.
            db.rule(4, "ALLOW", "SENDER_ID", "SHOP", 1, "@spamless:allowed_from_keyword")
            db.rule(5, "ALLOW", "SENDER_ID", "SHOP", 2, "My shop")
            // An allowed-word rule stored with the old English words: it starts acknowledged.
            db.rule(6, "ALLOW", "NUMBER", OTHER_NUMBER, 1, "Allowed from a keyword")
            // A word on one SIM only, and an allow on one SIM against the block of rules 1 to 3 on the other.
            db.rule(7, "BLOCK", "KEYWORD", "lottery", 2, "")
            db.rule(8, "ALLOW", "NUMBER", NUMBER, 2, "@spamless:allowed_by_you")
            // A made-up Iranian prefix on both SIMs, the older one with the higher count.
            db.rule(9, "BLOCK", "PREFIX", "0906", 1, "", createdAt = 50, hits = 9, lastHit = 10)
            db.rule(10, "BLOCK", "PREFIX", "0906", 2, "", createdAt = 40, hits = 1, lastHit = 20)
            db.held(1, ruleId = 2)
            db.held(2, ruleId = 3)
            db.held(3, ruleId = 7)
            db.held(4, ruleId = null)
            db.execSQL(
                "INSERT INTO forward_rules (id, senderType, senderPattern, bodyType, bodyPattern, subscriptionId, " +
                    "targets, sendFrom, includeCodes, enabled, note, createdAt) VALUES (1, 'NUMBER', '$NUMBER', " +
                    "NULL, '', 2, '+15550009999', 'SAME', 0, 1, '', 1)"
            )
        }
    }

    @Test
    fun mergesSimDuplicatesAndKeepsEverythingElse() {
        seedVersion8()
        helper.runMigrationsAndValidate(TEST_DB, 9, true, *SpamDatabase.ALL_MIGRATIONS).use { db ->
            val rules = db.query(
                "SELECT id, kind, type, pattern, subscriptionId, note, createdAt, hitCount, lastHit, acknowledged " +
                    "FROM spam_rules ORDER BY id"
            ).use { c ->
                buildList {
                    while (c.moveToNext()) {
                        add(
                            listOf(
                                c.getLong(0), c.getString(1), c.getString(2), c.getString(3),
                                if (c.isNull(4)) null else c.getInt(4), c.getString(5), c.getLong(6), c.getInt(7),
                                c.getLong(8), c.getInt(9),
                            )
                        )
                    }
                }
            }
            assertThat(rules).containsExactly(
                // Oldest id stays, hits add up (3 + 4 + 1), newest last hit, the user's note.
                listOf(1L, "BLOCK", "NUMBER", NUMBER, null, "@spamless:blocked_by_you", 100L, 8, 3_000L, 0),
                // The automatic note gives way to the user's; so the rule is the user's and not acknowledged.
                listOf(4L, "ALLOW", "SENDER_ID", "SHOP", null, "My shop", 400L, 0, 0L, 0),
                listOf(6L, "ALLOW", "NUMBER", OTHER_NUMBER, null, "Allowed from a keyword", 600L, 0, 0L, 1),
                listOf(7L, "BLOCK", "KEYWORD", "lottery", null, "", 700L, 0, 0L, 0),
                listOf(8L, "ALLOW", "NUMBER", NUMBER, null, "@spamless:allowed_by_you", 800L, 0, 0L, 0),
                // Oldest id (9) stays with the oldest creation time of the two (40).
                listOf(9L, "BLOCK", "PREFIX", "0906", null, "", 40L, 10, 20L, 0),
            ).inOrder()

            // Held messages that named a merged rule name the kept one.
            db.query("SELECT id, ruleId FROM blocked_messages ORDER BY id").use { c ->
                val refs = buildList {
                    while (c.moveToNext()) add(c.getLong(0) to if (c.isNull(1)) null else c.getLong(1))
                }
                assertThat(refs).containsExactly(1L to 1L, 2L to 1L, 3L to 7L, 4L to null).inOrder()
            }
            // New columns of the message migration start empty.
            db.query("SELECT COUNT(*) FROM blocked_messages WHERE migratedMsgId IS NULL AND migrateTries = 0").use {
                assertThat(it.moveToFirst() && it.getInt(0) == 4).isTrue()
            }
            // Forward rules keep their SIM.
            db.query("SELECT subscriptionId FROM forward_rules WHERE id = 1").use {
                assertThat(it.moveToFirst()).isTrue()
                assertThat(it.getInt(0)).isEqualTo(2)
            }
            db.query("SELECT COUNT(*) FROM allow_group_members").use {
                assertThat(it.moveToFirst() && it.getInt(0) == 0).isTrue()
            }
        }
    }

    @Test
    fun theNewIndexStopsADuplicateForAnySim() {
        seedVersion8()
        helper.runMigrationsAndValidate(TEST_DB, 9, true, *SpamDatabase.ALL_MIGRATIONS).use { db ->
            val inserted = runCatching {
                db.execSQL(
                    "INSERT INTO spam_rules (kind, type, pattern, subscriptionId, note, createdAt) " +
                        "VALUES ('BLOCK', 'KEYWORD', 'lottery', NULL, '', 1)"
                )
            }
            assertThat(inserted.isFailure).isTrue()
        }
    }

    /** The migrated file opens through Room; rules, members and the repository's SIM-free rules work on it. */
    @Test
    fun migratedFileWorksThroughRoom(): Unit = offMainThread {
        seedVersion8()
        val db = SpamDatabase.build(context, TEST_DB)
        try {
            val dao = db.spamDao()
            val rules = dao.getRules().map { it.toRule() }
            assertThat(rules.map { it.subscriptionId }.toSet()).containsExactly(null)
            assertThat(rules.single { it.id == 6L }.acknowledged).isTrue()
            assertThat(dao.findRule(ListKind.BLOCK, RuleType.PREFIX, "0906")?.id).isEqualTo(9L)

            dao.insertMember(
                AllowGroupMemberEntity(AllowGroup.CODE, NUMBER, NUMBER, 5L, MemberSource.LIVE, acknowledged = false)
            )
            // A second join of the same sender keeps the first row.
            dao.insertMember(
                AllowGroupMemberEntity(AllowGroup.CODE, NUMBER, NUMBER, 9L, MemberSource.HISTORY, acknowledged = true)
            )
            val member = dao.getMembers().single()
            assertThat(member.addedAt).isEqualTo(5L)
            assertThat(member.source).isEqualTo(MemberSource.LIVE)
            assertThat(dao.membersByKeys(listOf(NUMBER)).map { it.group }).containsExactly(AllowGroup.CODE)
            assertThat(dao.acknowledgeMembers(listOf(NUMBER))).isEqualTo(1)
            assertThat(dao.deleteMember(AllowGroup.CODE, NUMBER)).isEqualTo(1)
            assertThat(dao.getAllBlocked().map { it.migratedMsgId }.toSet()).containsExactly(null)
        } finally {
            db.close()
        }
    }

    /** Running the migration on a file without duplicates changes no rule but the SIM. */
    @Test
    fun aFileWithoutDuplicatesKeepsEveryRule() {
        helper.createDatabase(TEST_DB, 8).use { db ->
            db.rule(1, "BLOCK", "KEYWORD", "prize", null, "", hits = 2)
            db.rule(2, "ALLOW", "SENDER_ID", "BANK", 1, "My bank")
        }
        helper.runMigrationsAndValidate(TEST_DB, 9, true, *SpamDatabase.ALL_MIGRATIONS).use { db ->
            db.query("SELECT id, pattern, subscriptionId, hitCount, note FROM spam_rules ORDER BY id").use { c ->
                assertThat(c.moveToNext()).isTrue()
                assertThat(c.getString(1)).isEqualTo("prize")
                assertThat(c.isNull(2)).isTrue()
                assertThat(c.getInt(3)).isEqualTo(2)
                assertThat(c.moveToNext()).isTrue()
                assertThat(c.getString(1)).isEqualTo("BANK")
                assertThat(c.isNull(2)).isTrue()
                assertThat(c.getString(4)).isEqualTo("My bank")
                assertThat(c.moveToNext()).isFalse()
            }
        }
    }

    private companion object {
        const val TEST_DB = "provider-truth-migration-test.db"
        const val NUMBER = "+15550001234"
        const val OTHER_NUMBER = "+15550005678"
    }
}
