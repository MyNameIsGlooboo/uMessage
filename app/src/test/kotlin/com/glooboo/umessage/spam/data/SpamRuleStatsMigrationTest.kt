package com.glooboo.umessage.spam.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.spam.ListKind
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
 * Schema 8 adds pending_sms.approved and spam_rules.hitCount / lastHit. Rows that exist keep behavior: nothing is
 * approved, no rule has a hit. The DOMAIN rule type needs no column (types are stored by name).
 */
@RunWith(AndroidJUnit4::class)
class SpamRuleStatsMigrationTest {

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
    fun migratesFromVersionSevenKeepingRowsWithDefaults() {
        helper.createDatabase(TEST_DB, 7).use { db ->
            db.execSQL(
                "INSERT INTO spam_rules (id, kind, type, pattern, subscriptionId, note, createdAt) " +
                    "VALUES (1, 'BLOCK', 'KEYWORD', 'lottery', NULL, 'n', 1000)"
            )
            db.execSQL(
                "INSERT INTO pending_sms (address, subject, body, date, subscriptionId, status, createdAt, dateSent) " +
                    "VALUES ('Shop', '', 'queued', 5000, 1, 0, 6000, 4000)"
            )
        }

        helper.runMigrationsAndValidate(TEST_DB, 8, true, *SpamDatabase.ALL_MIGRATIONS).use { db ->
            db.query("SELECT pattern, note, hitCount, lastHit FROM spam_rules WHERE id = 1").use {
                assertThat(it.moveToFirst()).isTrue()
                assertThat(it.getString(0)).isEqualTo("lottery")
                assertThat(it.getString(1)).isEqualTo("n")
                assertThat(it.getInt(2)).isEqualTo(0)
                assertThat(it.getLong(3)).isEqualTo(0L)
            }
            db.query("SELECT body, dateSent, approved FROM pending_sms").use {
                assertThat(it.moveToFirst()).isTrue()
                assertThat(it.getString(0)).isEqualTo("queued")
                assertThat(it.getLong(1)).isEqualTo(4000L)
                assertThat(it.getInt(2)).isEqualTo(0)
            }
        }
    }

    @Test
    fun migratedRowsReadThroughRoomWithNoHitsAndNotApproved(): Unit = offMainThread {
        helper.createDatabase(TEST_DB, 7).use { db ->
            db.execSQL(
                "INSERT INTO spam_rules (id, kind, type, pattern, subscriptionId, note, createdAt) " +
                    "VALUES (1, 'BLOCK', 'PREFIX', '+91', NULL, '', 1000)"
            )
            db.execSQL(
                "INSERT INTO pending_sms (address, subject, body, date, subscriptionId, status, createdAt) " +
                    "VALUES ('Shop', '', 'queued', 5000, 1, 0, 6000)"
            )
        }
        val db = SpamDatabase.build(context, TEST_DB)
        try {
            val rule = db.spamDao().getRules().single().toRule()
            assertThat(rule.hitCount).isEqualTo(0)
            assertThat(rule.lastHit).isEqualTo(0L)
            assertThat(db.spamDao().getPending().single().approved).isFalse()

            // A DOMAIN rule and a counted hit round-trip on the migrated file.
            val domain = db.spamDao().insertRule(
                SpamRuleEntity(
                    kind = ListKind.BLOCK, type = RuleType.DOMAIN, pattern = "example.com",
                    subscriptionId = null, note = "", createdAt = 2,
                )
            )
            db.spamDao().recordRuleHit(domain, 777L)
            val counted = db.spamDao().getRule(domain)!!.toRule()
            assertThat(counted.type).isEqualTo(RuleType.DOMAIN)
            assertThat(counted.hitCount).isEqualTo(1)
            assertThat(counted.lastHit).isEqualTo(777L)
        } finally {
            db.close()
        }
    }

    private companion object {
        const val TEST_DB = "rule-stats-migration-test.db"
    }
}
