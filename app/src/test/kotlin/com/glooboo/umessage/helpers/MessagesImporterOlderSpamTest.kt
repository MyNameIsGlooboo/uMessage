package com.glooboo.umessage.helpers

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.models.BlockedBackup
import com.glooboo.umessage.models.MessagesBackup
import com.glooboo.umessage.spam.data.OlderSpamWorld
import com.glooboo.umessage.spam.data.SpamDatabase
import com.glooboo.umessage.spam.data.SpamProviderMigration
import com.glooboo.umessage.testing.ProviderTruthFlagRule
import com.glooboo.umessage.testing.offMainThread
import com.glooboo.umessage.testing.resetSharedMessagesDatabase
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * S16: with provider truth a restored old Spam entry is held as before and then moved to the phone's store by the
 * message migration at the end of the restore; with the flag off, and for a file without such entries, the migration
 * is not asked. Every number is made up.
 */
@RunWith(AndroidJUnit4::class)
class MessagesImporterOlderSpamTest {

    @get:Rule
    val flag = ProviderTruthFlagRule(enabled = true)

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val json = Json { encodeDefaults = true }
    private val before = SpamProviderMigration.moverFor
    private var runs = 0

    @After
    fun tearDown() {
        SpamProviderMigration.moverFor = before
        resetSharedMessagesDatabase()
        val field = SpamDatabase::class.java.getDeclaredField("instance").apply { isAccessible = true }
        (field.get(null) as? SpamDatabase)?.let { runCatching { it.close() } }
        field.set(null, null)
    }

    private fun countRuns() {
        SpamProviderMigration.moverFor = {
            runs++
            OlderSpamWorld().mover()
        }
    }

    private fun file(vararg entries: MessagesBackup): ByteArray =
        entries.joinToString(",", "[", "]") { json.encodeToString(MessagesBackup.serializer(), it) }
            .toByteArray()

    private val oldSpam = BlockedBackup(
        address = "+15550001234", body = "Old spam", receivedAt = 1_700_000_000_000, reason = "UNKNOWN_SENDER",
    )

    private fun import(bytes: ByteArray) = offMainThread {
        MessagesImporter(context).import(bytes.inputStream(), MessagesImporter.Options(sms = true, mms = true))
    }

    @Test
    fun aRestoredOldSpamEntryIsHandedToTheMigration() {
        countRuns()

        import(file(oldSpam))

        assertThat(runs).isEqualTo(1)
    }

    @Test
    fun withTheFlagOffTheMigrationIsNotAsked() {
        countRuns()
        ProviderTruthFlag.enabled = false

        import(file(oldSpam))

        assertThat(runs).isEqualTo(0)
    }

    @Test
    fun aFileWithoutOldSpamEntriesDoesNotAskTheMigration() {
        countRuns()

        import(file())

        assertThat(runs).isEqualTo(0)
    }
}
