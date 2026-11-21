package com.glooboo.umessage.spam.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.spam.BlockedMessage
import com.glooboo.umessage.spam.DecisionReason
import com.glooboo.umessage.spam.MmsMedia
import com.glooboo.umessage.spam.mms.BlockedMmsStore
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * F4-1: which done rows Older spam still lists. A row moved as text whose kept pictures came later gets the neutral
 * line ("pictures only here"); a row marked "nothing to move" whose late file could not be moved is stuck (red line);
 * a row that went as an MMS, or has no file on disk, is neither. Numbers are made up.
 */
@RunWith(AndroidJUnit4::class)
class OlderSpamListTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var journal: PrefsMigrationJournal

    @Before
    fun setUp() {
        val prefs = context.getSharedPreferences(PrefsMigrationJournal.PREFS, Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        journal = PrefsMigrationJournal(prefs)
    }

    private fun row(id: Long, mark: Long?, file: String?) = BlockedMessage(
        id = id, address = "+15550001234", body = "Look", subscriptionId = 1, isMms = true, receivedAt = 1_000L + id,
        reason = DecisionReason.UNKNOWN_SENDER, media = MmsMedia.STORED, mediaFile = file, migratedMsgId = mark,
    )

    private fun keptFile(name: String): String {
        BlockedMmsStore.dir(context).mkdirs()
        BlockedMmsStore.pduFile(context, name)!!.writeBytes(byteArrayOf(1, 2, 3))
        return name
    }

    @Test
    fun picturesOfARowDoneAsTextAreKeptHereAndALateFileOfANothingRowIsStuck() {
        val asText = row(1, mark = 55, file = keptFile("aaaa1111-0000-0000-0000-000000000001"))
        val asMms = row(2, mark = 56, file = keptFile("aaaa1111-0000-0000-0000-000000000002"))
        val late = keptFile("aaaa1111-0000-0000-0000-000000000003")
        val nothingLate = row(3, mark = MigrationMark.NOTHING_TO_MOVE, file = late)
        val nothingNoFile = row(4, mark = MigrationMark.NOTHING_TO_MOVE, file = "aaaa1111-0000-0000-0000-000000000004")
        val noKind = row(5, mark = 57, file = keptFile("aaaa1111-0000-0000-0000-000000000005"))
        journal.setKind(RowKey.of(asText), MigrationJournal.KIND_TEXT)
        journal.setKind(RowKey.of(asMms), MigrationJournal.KIND_MMS)
        val rows = listOf(asText, asMms, nothingLate, nothingNoFile, noKind)

        assertThat(SpamProviderMigration.picturesOnlyHere(context, rows)).containsExactly(asText.id)
        assertThat(SpamProviderMigration.lateFilesNotMoved(context, rows)).containsExactly(nothingLate.id)
        assertThat(SpamProviderMigration.stuck(context, rows)).containsExactly(nothingLate.id)
        assertThat(SpamProviderMigration.keptHere(context, rows)).containsExactly(asText.id, nothingLate.id)
    }
}
