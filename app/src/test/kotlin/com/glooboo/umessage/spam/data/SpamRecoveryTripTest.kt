package com.glooboo.umessage.spam.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.databases.DatabaseBackups
import com.glooboo.umessage.spam.AllowGroup
import com.glooboo.umessage.spam.DecisionReason
import com.glooboo.umessage.spam.ForwardSim
import com.glooboo.umessage.spam.MemberSource
import com.glooboo.umessage.spam.RuleType
import com.glooboo.umessage.testing.offMainThread
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * F-17 / R-4, reviewer: a trip 9 -> 8 -> 9. The 0.4.14 build kept the schema-9 file aside as `.v9-imported` and copied
 * only the columns it knows (no members, no migration marks). Back on schema 9 the members and the marks come back from
 * that file, in a transaction of their own after the forward rules, and the file is retired. Numbers are made up.
 */
@RunWith(AndroidJUnit4::class)
class SpamRecoveryTripTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    @After
    fun deleteFiles() {
        context.getDatabasePath(TEST_DB).parentFile?.listFiles { _, name -> name.startsWith(TEST_DB) }
            ?.forEach { it.delete() }
    }

    private fun held(mark: Long? = null, tries: Int = 0) = BlockedMessageEntity(
        address = SENDER, body = "held", subscriptionId = 1, isMms = false, receivedAt = 1_000,
        reason = DecisionReason.UNKNOWN_SENDER, ruleId = null, migratedMsgId = mark, migrateTries = tries,
    )

    private fun withDatabase(name: String, block: (SpamDao) -> Unit) {
        val db = SpamDatabase.build(context, name)
        try {
            offMainThread { block(db.spamDao()) }
        } finally {
            db.close()
        }
    }

    @Test
    fun membersAndMarksComeBackFromTheFileTheOlderBuildKeptAside() {
        // The schema-9 file as it was before the downgrade: a member and a row the migration moved.
        withDatabase(KEPT_DB) { dao ->
            dao.insertMember(
                AllowGroupMemberEntity(AllowGroup.CODE, SENDER, SENDER, 7L, MemberSource.LIVE, acknowledged = true)
            )
            dao.insertBlocked(held(mark = 55L))
        }
        // 0.4.14's copy, upgraded again: the row without its mark, no member.
        withDatabase(TEST_DB) { dao -> dao.insertBlocked(held()) }
        val imported = File(context.getDatabasePath(TEST_DB).path + ".v${SpamDatabase.VERSION}-imported")
        DatabaseBackups.copyWithCompanions(context.getDatabasePath(KEPT_DB), imported)

        withDatabase(TEST_DB) { dao ->
            assertThat(dao.getMembers().map { it.senderKey }).containsExactly(SENDER)
            assertThat(dao.getAllBlocked().single().migratedMsgId).isEqualTo(55L)
        }
        assertThat(imported.exists()).isFalse()
    }

    @Test
    fun aRowWithAMarkOfItsOwnKeepsItAndOtherMessagesGetNone() {
        withDatabase(KEPT_DB) { dao ->
            dao.insertBlocked(held(mark = 55L))
            dao.insertBlocked(held(mark = 66L).copy(body = "other text"))
        }
        withDatabase(TEST_DB) { dao ->
            dao.insertBlocked(held(mark = MigrationMark.written(77L), tries = 1))
            dao.insertBlocked(held().copy(body = "not in the kept file"))
        }
        DatabaseBackups.copyWithCompanions(
            context.getDatabasePath(KEPT_DB),
            File(context.getDatabasePath(TEST_DB).path + ".v${SpamDatabase.VERSION}-imported"),
        )

        withDatabase(TEST_DB) { dao ->
            val rows = dao.getAllBlocked().associateBy { it.body }
            assertThat(rows.getValue("held").migratedMsgId).isEqualTo(MigrationMark.written(77L))
            assertThat(rows.getValue("not in the kept file").migratedMsgId).isNull()
        }
    }

    private fun forward(sender: String, target: String, enabled: Boolean = true) = ForwardRuleEntity(
        senderType = RuleType.NUMBER, senderPattern = sender, bodyType = null, bodyPattern = "", windowStart = null,
        windowEnd = null, subscriptionId = null, targets = target, sendFrom = ForwardSim.SAME, includeCodes = true,
        enabled = enabled, note = "", createdAt = 1L,
    )

    private fun withForwards(name: String, block: (ForwardDao) -> Unit) {
        val db = SpamDatabase.build(context, name)
        try {
            offMainThread { block(db.forwardDao()) }
        } finally {
            db.close()
        }
    }

    /** J27 (L2-1): 0.4.14 edits forward rules, so its kept file never brings one back. */
    @Test
    fun aForwardRuleDeletedDisabledOrRetargetedOnTheOlderBuildStaysThatWay() {
        withForwards(KEPT_DB) { dao ->
            dao.insert(forward("+15550000001", OLD_TARGET))
            dao.insert(forward("+15550000002", OLD_TARGET))
            dao.insert(forward("+15550000003", OLD_TARGET))
        }
        // The first rule was deleted there, the second switched off, the third sent to another number.
        withForwards(TEST_DB) { dao ->
            dao.insert(forward("+15550000002", OLD_TARGET, enabled = false))
            dao.insert(forward("+15550000003", NEW_TARGET))
        }
        val imported = File(context.getDatabasePath(TEST_DB).path + ".v${SpamDatabase.VERSION}-imported")
        DatabaseBackups.copyWithCompanions(context.getDatabasePath(KEPT_DB), imported)

        withForwards(TEST_DB) { dao ->
            val rules = dao.getAll()
            assertThat(rules.map { it.senderPattern }).containsExactly("+15550000002", "+15550000003")
            assertThat(rules.filter { it.enabled }.map { it.targets }).containsExactly(NEW_TARGET)
        }
        assertThat(imported.exists()).isFalse()
    }

    private companion object {
        const val OLD_TARGET = "+15550000009"
        const val NEW_TARGET = "+15550000008"
        const val TEST_DB = "spam-trip-test.db"
        val KEPT_DB = "$TEST_DB-kept"
        const val SENDER = "+15550001234"
    }
}
