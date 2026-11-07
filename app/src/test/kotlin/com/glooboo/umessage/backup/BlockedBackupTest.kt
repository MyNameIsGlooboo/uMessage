package com.glooboo.umessage.backup

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.models.BlockedBackup
import com.glooboo.umessage.models.MessagesBackup
import com.glooboo.umessage.spam.DecisionReason
import com.glooboo.umessage.spam.MMS_PLACEHOLDER_BODY
import com.glooboo.umessage.spam.MmsMedia
import com.glooboo.umessage.spam.data.BlockedMessageEntity
import com.glooboo.umessage.spam.data.SpamDatabase
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The Blocked folder in a backup: backup, wipe, restore, equal, and never in the inbox, never twice. */
@RunWith(AndroidJUnit4::class)
class BlockedBackupTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private lateinit var old: SpamDatabase
    private lateinit var fresh: SpamDatabase
    private val files = mapOf("abcdef12-0000" to byteArrayOf(1, 2, 3, 4))
    private val saved = mutableListOf<ByteArray>()

    @Before
    fun setUp() {
        old = Room.inMemoryDatabaseBuilder(context, SpamDatabase::class.java).allowMainThreadQueries().build()
        fresh = Room.inMemoryDatabaseBuilder(context, SpamDatabase::class.java).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        old.close()
        fresh.close()
    }

    private fun support(db: SpamDatabase) = BlockedBackupSupport(
        dao = db.spamDao(),
        readMedia = { files[it] },
        saveMedia = { pdu -> "new-file-${saved.size + 1}0000".also { saved += pdu } },
    )

    private fun seed() {
        val dao = old.spamDao()
        dao.insertBlocked(
            BlockedMessageEntity(
                address = "+15550001", body = "win a prize", subscriptionId = 3, isMms = false,
                receivedAt = 1_700_000_000_000, reason = DecisionReason.BLOCK_RULE, ruleId = 9,
            )
        )
        dao.insertBlocked(
            BlockedMessageEntity(
                address = "+15550002", body = "look", subscriptionId = 3, isMms = true,
                receivedAt = 1_700_000_100_000, reason = DecisionReason.UNKNOWN_SENDER, ruleId = null,
                media = MmsMedia.STORED, mediaFile = "abcdef12-0000",
            )
        )
        dao.insertBlocked(
            BlockedMessageEntity(
                address = "+15550003", body = MMS_PLACEHOLDER_BODY, subscriptionId = 3, isMms = true,
                receivedAt = 1_700_000_200_000, reason = DecisionReason.FOREIGN_NUMBER, ruleId = null,
                media = MmsMedia.MISSING,
            )
        )
    }

    private fun entriesOf(db: SpamDatabase, sms: Boolean = true, mms: Boolean = true): List<BlockedBackup> {
        val out = mutableListOf<BlockedBackup>()
        support(db).collect(sms, mms) { out += it }
        return out.map {
            val text = json.encodeToString(MessagesBackup.serializer(), it)
            json.decodeFromString(MessagesBackup.serializer(), text) as BlockedBackup
        }
    }

    private fun eventCount(db: SpamDatabase) =
        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM spam_events").use {
            it.moveToFirst()
            it.getInt(0)
        }

    @Test
    fun blockedMessagesSurviveBackupWipeRestoreAndStayInTheBlockedFolder() {
        seed()
        val entries = entriesOf(old)
        assertThat(entries).hasSize(3)

        val taken = entries.count { support(fresh).restore(it) }

        assertThat(taken).isEqualTo(3)
        val rows = fresh.spamDao().getAllBlocked().sortedBy { it.receivedAt }
        assertThat(rows.map { it.address to it.body }).containsExactly(
            "+15550001" to "win a prize", "+15550002" to "look", "+15550003" to MMS_PLACEHOLDER_BODY,
        ).inOrder()
        assertThat(rows.map { it.reason }).containsExactly(
            DecisionReason.BLOCK_RULE, DecisionReason.UNKNOWN_SENDER, DecisionReason.FOREIGN_NUMBER,
        ).inOrder()
        assertThat(rows.map { it.media }).containsExactly(MmsMedia.NONE, MmsMedia.STORED, MmsMedia.MISSING).inOrder()
        assertThat(rows.all { it.ruleId == null }).isTrue()
        assertThat(rows[1].mediaFile).isEqualTo("new-file-10000")
        assertThat(saved.single().toList()).containsExactly(1.toByte(), 2.toByte(), 3.toByte(), 4.toByte()).inOrder()
        // Restoring counts no "blocked" event, so the weekly digest does not report old spam as new.
        assertThat(eventCount(fresh)).isEqualTo(0)
    }

    @Test
    fun restoringTwiceOrOntoAPhoneThatHasThemAddsNothing() {
        seed()
        val entries = entriesOf(old)
        entries.forEach { support(fresh).restore(it) }

        val again = entries.count { support(fresh).restore(it) }
        val onTheSamePhone = entries.count { support(old).restore(it) }

        assertThat(again).isEqualTo(0)
        assertThat(onTheSamePhone).isEqualTo(0)
        assertThat(fresh.spamDao().getAllBlocked()).hasSize(3)
        assertThat(old.spamDao().getAllBlocked()).hasSize(3)
    }

    @Test
    fun onlyTheChosenKindsAreWritten() {
        seed()

        assertThat(entriesOf(old, sms = true, mms = false).map { it.address }).containsExactly("+15550001")
        assertThat(entriesOf(old, sms = false, mms = true)).hasSize(2)
    }

    @Test
    fun aKeptMmsThatIsGoneComesBackAsOneWithoutContent() {
        seed()
        val entry = entriesOf(old).first { it.address == "+15550002" }.copy(mediaData = null)

        support(fresh).restore(entry)

        val row = fresh.spamDao().getAllBlocked().single()
        assertThat(row.media).isEqualTo(MmsMedia.MISSING)
        assertThat(row.mediaFile).isNull()
    }

    @Test
    fun anEntryWithoutASenderOrTimeIsRefused() {
        val ok = BlockedBackup(address = "+15550001", body = "x", receivedAt = 5, reason = "NO_MATCH")

        assertThat(support(fresh).restore(ok.copy(address = " "))).isFalse()
        assertThat(support(fresh).restore(ok.copy(receivedAt = 0))).isFalse()
        assertThat(fresh.spamDao().getAllBlocked()).isEmpty()
    }

    @Test
    fun aFileFromALaterVersionWithUnknownFieldsAndReasonsStillReads() {
        val read = json.decodeFromString(
            MessagesBackup.serializer(),
            """{"backupType":"umessage_blocked","address":"+15550001","body":"hi","received_at":7,""" +
                """"reason":"SOMETHING_NEW","future":1}""",
        ) as BlockedBackup

        assertThat(support(fresh).restore(read)).isTrue()
        assertThat(fresh.spamDao().getAllBlocked().single().reason).isEqualTo(DecisionReason.BLOCK_RULE)
    }
}
