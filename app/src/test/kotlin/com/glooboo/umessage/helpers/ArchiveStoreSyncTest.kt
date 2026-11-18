package com.glooboo.umessage.helpers

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.backup.AppDataBackupSupport
import com.glooboo.umessage.backup.PendingArchive
import com.glooboo.umessage.extensions.config
import com.glooboo.umessage.extensions.conversationsDB
import com.glooboo.umessage.extensions.getMessagesDB
import com.glooboo.umessage.extensions.insertOrUpdateConversation
import com.glooboo.umessage.extensions.renameConversation
import com.glooboo.umessage.extensions.updateConversationArchivedStatus
import com.glooboo.umessage.models.AppDataBackup
import com.glooboo.umessage.models.ChatRefBackup
import com.glooboo.umessage.models.Conversation
import com.glooboo.umessage.spam.data.deliverIncomingSms
import com.glooboo.umessage.testing.CountingInboxProvider
import com.glooboo.umessage.testing.CountingInboxProvider.ArchiveWrites
import com.glooboo.umessage.testing.offMainThread
import com.glooboo.umessage.testing.resetSharedMessagesDatabase
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
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

    private fun deliver(threadId: Long) = offMainThread {
        context.deliverIncomingSms(
            address = CountingInboxProvider.address(threadId),
            subject = "",
            body = "Hello",
            date = CountingInboxProvider.BASE_MS + 999_000_000,
            subscriptionId = -1,
            status = -1,
            notify = NotifyContext(NotifyMode.NONE),
            threadId = threadId,
        )
    }

    private fun restoreArchive(threadId: Long) = offMainThread {
        val db = context.getMessagesDB()
        val support = AppDataBackupSupport(
            stars = db.StarredMessagesDao(), labels = db.LabelsDao(), conversations = db.ConversationsDao(),
            pendingArchive = PendingArchive(context),
            addressesOf = { emptySet() },
            threadFor = { threadId },
            archive = { context.updateConversationArchivedStatus(it, true) },
        )
        val chat = ChatRefBackup(listOf(CountingInboxProvider.address(threadId)))
        runBlocking { support.restore(AppDataBackup(archived = listOf(chat))) }
    }

    /** The restore the way the app builds it: the thread of the number comes from the phone's store (the double). */
    private fun restoreArchiveThroughApp(threadId: Long) = offMainThread {
        val chat = ChatRefBackup(listOf(CountingInboxProvider.address(threadId)))
        runBlocking { AppDataBackupSupport.of(context).restore(AppDataBackup(archived = listOf(chat))) }
    }

    private fun savedRow(threadId: Long) =
        offMainThread { context.conversationsDB.getConversationWithThreadId(threadId)!! }

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
    fun aNewMessageUnarchivesTheChatThroughTheAppsOwnFlagWhenTheStoreDropsWrites() {
        provider.archiveWrites = ArchiveWrites.IGNORE
        setArchived(THREAD, true)
        assertThat(archivedIds()).containsExactly(THREAD)

        deliver(THREAD)
        fullRefresh()

        assertThat(archivedIds()).isEmpty()
        assertThat(inboxIds()).contains(THREAD)
        assertThat(context.config.isArchiveStoreUnreliable).isTrue()
    }

    @Test
    fun aNewMessageKeepsTheChatArchivedWhenTheSettingSaysSoAndTheStoreDropsWrites() {
        context.config.keepConversationsArchived = true
        provider.archiveWrites = ArchiveWrites.IGNORE
        setArchived(THREAD, true)

        deliver(THREAD)
        fullRefresh()

        assertThat(archivedIds()).containsExactly(THREAD)
    }

    @Test
    fun aNewMessageUnarchivesTheChatInTheStoreToo() {
        setArchived(THREAD, true)
        assertThat(provider.isArchived(THREAD)).isTrue()

        deliver(THREAD)
        fullRefresh()

        assertThat(provider.isArchived(THREAD)).isFalse()
        assertThat(archivedIds()).isEmpty()
        assertThat(context.config.isArchiveStoreUnreliable).isFalse()
    }

    @Test
    fun archivingAChatWithOnlyScheduledMessagesKeepsTheStoreTrusted() {
        provider.archiveWrites = ArchiveWrites.IGNORE
        offMainThread {
            context.conversationsDB.insertOrUpdate(
                Conversation(
                    threadId = SCHEDULED_THREAD, snippet = "", date = 1, read = true, title = "", photoUri = "",
                    isGroupConversation = false, phoneNumber = CountingInboxProvider.address(SCHEDULED_THREAD),
                    isScheduled = true,
                )
            )
        }

        setArchived(SCHEDULED_THREAD, true)

        assertThat(archivedIds()).containsExactly(SCHEDULED_THREAD)
        assertThat(context.config.isArchiveStoreUnreliable).isFalse()
    }

    @Test
    fun archivingAChatTheStoreNoLongerHasKeepsTheStoreTrusted() {
        provider.deleteThread(THREAD)
        provider.archiveWrites = ArchiveWrites.NO_ROWS

        setArchived(THREAD, true)

        assertThat(context.config.isArchiveStoreUnreliable).isFalse()
    }

    @Test
    fun aReadBackOfAnotherThreadSaysNothingAboutTheWrite() {
        // The other thread reads "not archived", the same as the dropped write: only the row's id says it is not ours.
        provider.archiveWrites = ArchiveWrites.IGNORE
        provider.readsOtherThread = true

        setArchived(THREAD, true)

        assertThat(context.config.isArchiveStoreUnreliable).isFalse()
    }

    @Test
    fun unarchivingTheLastArchivedChatKeepsAWorkingStoreTrusted() {
        setArchived(THREAD, true)
        setArchived(THREAD, false)
        fullRefresh()

        assertThat(archivedIds()).isEmpty()
        assertThat(context.config.isArchiveStoreUnreliable).isFalse()
    }

    @Test
    fun anArchivedScheduledOnlyChatAloneDoesNotMarkAWorkingStore() {
        offMainThread {
            context.conversationsDB.insertOrUpdate(
                Conversation(
                    threadId = SCHEDULED_THREAD, snippet = "", date = 1, read = true, title = "", photoUri = "",
                    isGroupConversation = false, phoneNumber = CountingInboxProvider.address(SCHEDULED_THREAD),
                    isScheduled = true, isArchived = true,
                )
            )
        }

        fullRefresh()

        assertThat(context.config.isArchiveStoreUnreliable).isFalse()
    }

    @Test
    fun aRestoredArchiveStaysArchivedNextToOneTheStoreAlreadyHolds() {
        setArchived(OTHER_THREAD, true)
        fullRefresh()
        assertThat(archivedIds()).containsExactly(OTHER_THREAD)

        restoreArchive(THREAD)
        fullRefresh()

        assertThat(provider.isArchived(THREAD)).isTrue()
        assertThat(archivedIds()).containsExactly(THREAD, OTHER_THREAD)
        assertThat(context.config.isArchiveStoreUnreliable).isFalse()
    }

    @Test
    fun aRestoredArchiveWaitingForItsRowIsWrittenToTheStoreToo() {
        setArchived(OTHER_THREAD, true)
        PendingArchive(context).add(THREAD)

        fullRefresh()
        fullRefresh()

        assertThat(provider.isArchived(THREAD)).isTrue()
        assertThat(archivedIds()).containsExactly(THREAD, OTHER_THREAD)
        assertThat(context.config.isArchiveStoreUnreliable).isFalse()
    }

    @Test
    fun aRestoredArchiveThroughTheAppsOwnWiringReachesTheStoreAndTheList() {
        restoreArchiveThroughApp(THREAD)
        fullRefresh()

        assertThat(provider.isArchived(THREAD)).isTrue()
        assertThat(archivedIds()).containsExactly(THREAD)
        assertThat(context.config.isArchiveStoreUnreliable).isFalse()
    }

    @Test
    fun anArchiveMadeWhileARefreshHeldAnOlderRowStaysArchivedWhenTheStoreDropsWrites() {
        provider.archiveWrites = ArchiveWrites.IGNORE
        val snapshot = savedRow(THREAD)
        setArchived(THREAD, true)
        assertThat(context.config.isArchiveStoreUnreliable).isTrue()

        offMainThread { context.insertOrUpdateConversation(snapshot.copy(snippet = "Newer"), snapshot) }

        assertThat(archivedIds()).containsExactly(THREAD)
        assertThat(savedRow(THREAD).snippet).isEqualTo("Newer")
    }

    @Test
    fun aRenameKeepsTheChatArchived() {
        val opened = savedRow(THREAD)
        setArchived(THREAD, true)

        offMainThread { context.renameConversation(opened, "New name") }

        assertThat(archivedIds()).containsExactly(THREAD)
        assertThat(savedRow(THREAD).title).isEqualTo("New name")
        assertThat(savedRow(THREAD).usesCustomTitle).isTrue()
    }

    @Test
    fun aRestoredArchiveStaysWhenTheStoreDropsWrites() {
        provider.archiveWrites = ArchiveWrites.IGNORE

        restoreArchive(THREAD)
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
        // A thread the store has too: only the isScheduled guards keep such a chat from marking the store.
        const val SCHEDULED_THREAD = 7L
    }
}
