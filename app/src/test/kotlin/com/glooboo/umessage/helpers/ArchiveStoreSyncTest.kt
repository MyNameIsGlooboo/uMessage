package com.glooboo.umessage.helpers

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.extensions.config
import com.glooboo.umessage.extensions.conversationsDB
import com.glooboo.umessage.extensions.updateConversationArchivedStatus
import com.glooboo.umessage.testing.CountingInboxProvider
import com.glooboo.umessage.testing.CountingInboxProvider.ArchiveWrites
import com.glooboo.umessage.testing.offMainThread
import com.glooboo.umessage.testing.resetSharedMessagesDatabase
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * A phone store that takes the archive write and does not keep it (seen on a Samsung phone) must not undo the app's own
 * archive on the next list refresh; a store that works stays the authority.
 */
@RunWith(AndroidJUnit4::class)
class ArchiveStoreSyncTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var provider: CountingInboxProvider
    private val executor = Executors.newSingleThreadExecutor()
    private val importer = Executors.newSingleThreadExecutor()
    private lateinit var sync: ConversationListSync

    @Before
    fun setUp() {
        provider = CountingInboxProvider.install()
        provider.seed(threads = THREADS, messagesPerThread = 2)
        sync = ConversationListSync(context, executor, { _, _ -> }, importer, importMessages = {})
        sync.start()
        idle()
    }

    @After
    fun tearDown() {
        executor.shutdownNow()
        importer.shutdownNow()
        importer.awaitTermination(30, TimeUnit.SECONDS)
        resetSharedMessagesDatabase()
    }

    private fun idle() {
        executor.submit {}.get(60, TimeUnit.SECONDS)
    }

    private fun archivedIds() = offMainThread { context.conversationsDB.getAllArchived() }.map { it.threadId }

    private fun inboxIds() = offMainThread { context.conversationsDB.getNonArchived() }.map { it.threadId }

    private fun setArchived(threadId: Long, archived: Boolean) =
        offMainThread { context.updateConversationArchivedStatus(threadId, archived) }

    private fun fullRefresh() {
        sync.requestFull()
        idle()
    }

    @Test
    fun anArchiveTheStoreIgnoredStaysArchivedAfterARefresh() {
        provider.archiveWrites = ArchiveWrites.IGNORE

        setArchived(THREAD, true)
        fullRefresh()

        assertThat(archivedIds()).containsExactly(THREAD)
        assertThat(inboxIds()).doesNotContain(THREAD)
        assertThat(context.config.isArchiveStoreUnreliable).isTrue()
    }

    @Test
    fun anArchiveTheStoreReportedNoRowsForStaysArchivedAfterARefresh() {
        provider.archiveWrites = ArchiveWrites.NO_ROWS

        setArchived(THREAD, true)
        fullRefresh()

        assertThat(archivedIds()).containsExactly(THREAD)
        assertThat(inboxIds()).doesNotContain(THREAD)
        assertThat(context.config.isArchiveStoreUnreliable).isTrue()
    }

    @Test
    fun theArchivedTabStaysAvailableWhenTheStoreDropsWrites() {
        provider.archiveWrites = ArchiveWrites.IGNORE

        setArchived(THREAD, true)

        assertThat(context.config.isArchiveAvailable).isTrue()
    }

    @Test
    fun aNewMessageInAnArchivedChatKeepsItArchivedWhenTheStoreDropsWrites() {
        provider.archiveWrites = ArchiveWrites.IGNORE
        setArchived(THREAD, true)

        provider.receive(
            id = 900_000, thread = THREAD, body = "Hello", dateMs = CountingInboxProvider.BASE_MS + 999_000_000,
        )
        fullRefresh()

        assertThat(archivedIds()).containsExactly(THREAD)
    }

    @Test
    fun anArchiveTheStoreKeepsMakesTheStoreTheAuthority() {
        setArchived(THREAD, true)
        fullRefresh()

        assertThat(provider.isArchived(THREAD)).isTrue()
        assertThat(archivedIds()).containsExactly(THREAD)
        assertThat(context.config.isArchiveStoreUnreliable).isFalse()
        assertThat(context.config.isArchiveAvailable).isTrue()

        // Another app archives one chat and unarchives this one: the list follows the store.
        provider.setArchived(OTHER_THREAD, true)
        provider.setArchived(THREAD, false)
        fullRefresh()

        assertThat(archivedIds()).containsExactly(OTHER_THREAD)
        assertThat(inboxIds()).contains(THREAD)
    }

    @Test
    fun anUnarchiveWorksThroughTheAppsOwnFlagWhenTheStoreDropsWrites() {
        provider.setArchived(THREAD, true)
        fullRefresh()
        assertThat(archivedIds()).containsExactly(THREAD)
        provider.archiveWrites = ArchiveWrites.IGNORE

        setArchived(THREAD, false)
        fullRefresh()

        assertThat(archivedIds()).isEmpty()
        assertThat(inboxIds()).contains(THREAD)
        assertThat(context.config.isArchiveStoreUnreliable).isTrue()
    }

    @Test
    fun archivesTheStoreNeverHeldAreKeptByTheFirstRefresh() {
        // Archived in the app's own list only, as an earlier version did on such a phone.
        offMainThread { context.conversationsDB.moveToArchive(THREAD) }

        fullRefresh()

        assertThat(archivedIds()).containsExactly(THREAD)
        assertThat(context.config.isArchiveStoreUnreliable).isTrue()
    }

    private companion object {
        const val THREADS = 20
        const val THREAD = 5L
        const val OTHER_THREAD = 6L
    }
}
