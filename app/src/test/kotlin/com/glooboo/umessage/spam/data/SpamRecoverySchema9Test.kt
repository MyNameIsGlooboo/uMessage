package com.glooboo.umessage.spam.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.databases.DatabaseBackups
import com.glooboo.umessage.spam.AllowGroup
import com.glooboo.umessage.spam.DecisionReason
import com.glooboo.umessage.spam.MemberSource
import com.glooboo.umessage.testing.offMainThread
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A-10: the downgrade policy of spamless.db (SpamDatabaseRecovery) at schema 9 keeps the group members and the
 * message-migration marks: a newer file is imported with them, and a backup of this schema merges them back without
 * doubles. Every number is made up.
 */
@RunWith(AndroidJUnit4::class)
class SpamRecoverySchema9Test {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    @After
    fun deleteFiles() {
        context.getDatabasePath(TEST_DB).parentFile?.listFiles { _, name ->
            name.startsWith(TEST_DB) || name.startsWith(OTHER_DB)
        }?.forEach { it.delete() }
    }

    private fun member(address: String, group: AllowGroup = AllowGroup.CODE) =
        AllowGroupMemberEntity(group, address, address, 7L, MemberSource.LIVE, acknowledged = false)

    private fun held() = BlockedMessageEntity(
        address = SENDER, body = "held", subscriptionId = 1, isMms = false, receivedAt = 1,
        reason = DecisionReason.UNKNOWN_SENDER, ruleId = null, migratedMsgId = 55L, migrateTries = 1,
    )

    private fun withDatabase(name: String = TEST_DB, block: (SpamDao) -> Unit) {
        val db = SpamDatabase.build(context, name)
        try {
            offMainThread { block(db.spamDao()) }
        } finally {
            db.close()
        }
    }

    @Test fun aNewerFileIsImportedWithItsMembersAndMarks() {
        withDatabase { dao ->
            dao.insertMember(member(SENDER))
            dao.insertBlocked(held())
        }
        SQLiteDatabase.openDatabase(context.getDatabasePath(TEST_DB).path, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.version = SpamDatabase.VERSION + 1
        }
        withDatabase { dao ->
            assertThat(dao.getMembers().map { it.senderKey }).containsExactly(SENDER)
            val row = dao.getAllBlocked().single()
            assertThat(row.migratedMsgId).isEqualTo(55L)
            assertThat(row.migrateTries).isEqualTo(1)
        }
    }

    @Test fun aBackupOfThisSchemaMergesMembersWithoutDoubles() {
        withDatabase(OTHER_DB) { dao ->
            dao.insertMember(member(SENDER))
            dao.insertMember(member(OTHER, AllowGroup.WROTE))
        }
        withDatabase { dao -> dao.insertMember(member(SENDER)) }
        DatabaseBackups.copyWithCompanions(
            context.getDatabasePath(OTHER_DB), DatabaseBackups.backupFile(context, TEST_DB, SpamDatabase.VERSION),
        )
        withDatabase { dao ->
            assertThat(dao.getMembers().map { it.senderKey to it.group })
                .containsExactly(SENDER to AllowGroup.CODE, OTHER to AllowGroup.WROTE)
        }
    }

    /** The model keeps the marks, so an Undo that puts the row back does not lose them. */
    @Test fun theModelKeepsTheMigrationMarks() {
        val back = BlockedMessageEntity.from(held().toModel())
        assertThat(back.migratedMsgId).isEqualTo(55L)
        assertThat(back.migrateTries).isEqualTo(1)
    }

    private companion object {
        const val TEST_DB = "spam-recovery-9-test.db"
        const val OTHER_DB = "spam-recovery-9-other.db"
        const val SENDER = "+15550001234"
        const val OTHER = "+15550005678"
    }
}
