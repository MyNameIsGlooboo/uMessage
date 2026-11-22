package com.glooboo.umessage.helpers

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.models.BlockedBackup
import com.glooboo.umessage.models.MessagesBackup
import com.glooboo.umessage.spam.FirstRun
import com.glooboo.umessage.spam.FirstRunPhase
import com.glooboo.umessage.spam.FirstRunState
import com.glooboo.umessage.spam.FirstRunStore
import com.glooboo.umessage.spam.SpamSorting
import com.glooboo.umessage.spam.data.OlderSpamWorld
import com.glooboo.umessage.spam.data.SpamDatabase
import com.glooboo.umessage.spam.data.SpamProviderMigration
import com.glooboo.umessage.testing.offMainThread
import com.glooboo.umessage.testing.resetSharedMessagesDatabase
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * S16: with provider truth a restored old Spam entry is held as before and then moved to the phone's store by the
 * message migration at the end of the restore; for a file without such entries the migration is not asked. Every
 * number is made up.
 */
@RunWith(AndroidJUnit4::class)
class MessagesImporterOlderSpamTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val json = Json { encodeDefaults = true }
    private val before = SpamProviderMigration.moverFor
    private var runs = 0

    @After
    fun tearDown() {
        // The RESTORE pass the import asked for runs on the sorting thread: it ends before the stores are reset.
        val sorter = CountDownLatch(1)
        SpamSorting.execute(Runnable { sorter.countDown() })
        sorter.await(30, TimeUnit.SECONDS)
        SpamProviderMigration.moverFor = before
        resetSharedMessagesDatabase()
        val field = SpamDatabase::class.java.getDeclaredField("instance").apply { isAccessible = true }
        (field.get(null) as? SpamDatabase)?.let { runCatching { it.close() } }
        field.set(null, null)
        context.getSharedPreferences(FirstRunStore.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        FirstRun.reload(context)
    }

    @Before
    fun firstSortPublished() {
        // Outside a pass, after the first sort: the only time a restore moves the held rows itself.
        val prefs = context.getSharedPreferences(FirstRunStore.PREFS, Context.MODE_PRIVATE)
        FirstRunStore(prefs).write(FirstRunState(phase = FirstRunPhase.DONE, sortPublished = true))
        FirstRun.reload(context)
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
    fun aFileWithoutOldSpamEntriesDoesNotAskTheMigration() {
        countRuns()

        import(file())

        assertThat(runs).isEqualTo(0)
    }
}
