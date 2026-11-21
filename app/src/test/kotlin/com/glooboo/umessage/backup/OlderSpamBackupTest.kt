package com.glooboo.umessage.backup

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.helpers.ProviderTruthFlag
import com.glooboo.umessage.models.BlockedBackup
import com.glooboo.umessage.spam.DecisionReason
import com.glooboo.umessage.spam.MMS_PLACEHOLDER_BODY
import com.glooboo.umessage.spam.data.BlockedMessageEntity
import com.glooboo.umessage.spam.data.MigrationMark
import com.glooboo.umessage.spam.data.SpamDatabase
import com.glooboo.umessage.testing.ProviderTruthFlagRule
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Section 9: with provider truth the backup reads Spam from the phone's store like any message, so old Spam entries
 * are written only for held rows not moved yet. With the flag off every held row goes along, as in 0.4.14. Every
 * number is made up.
 */
@RunWith(AndroidJUnit4::class)
class OlderSpamBackupTest {

    @get:Rule
    val flag = ProviderTruthFlagRule(enabled = true)

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var db: SpamDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, SpamDatabase::class.java).allowMainThreadQueries().build()
        val dao = db.spamDao()
        dao.insertBlocked(row("not moved", at = 1, mark = null))
        dao.insertBlocked(row("moved", at = 2, mark = 501))
        dao.insertBlocked(row("written, not verified", at = 3, mark = MigrationMark.written(502)))
        dao.insertBlocked(row(MMS_PLACEHOLDER_BODY, at = 4, mark = MigrationMark.NOTHING_TO_MOVE, mms = true))
    }

    @After
    fun tearDown() = db.close()

    private fun row(body: String, at: Long, mark: Long?, mms: Boolean = false) = BlockedMessageEntity(
        address = "+15550001234", body = body, subscriptionId = 1, isMms = mms, receivedAt = 1_700_000_000_000 + at,
        reason = DecisionReason.UNKNOWN_SENDER, ruleId = null, migratedMsgId = mark,
    )

    private fun collected(): List<String> {
        val out = mutableListOf<BlockedBackup>()
        BlockedBackupSupport(db.spamDao(), { null }, { null }).collect(sms = true, mms = true) { out += it }
        return out.map { it.body }
    }

    @Test
    fun onlyRowsNotMovedYetGoAlongAsOldSpamEntries() {
        assertThat(collected()).containsExactly("not moved", "written, not verified").inOrder()
    }

    @Test
    fun withTheFlagOffEveryHeldRowGoesAlongAsIn0414() {
        ProviderTruthFlag.enabled = false

        assertThat(collected()).hasSize(4)
    }
}
