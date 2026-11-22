package com.glooboo.umessage.helpers

import android.content.Context
import android.provider.Telephony.Sms
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.extensions.conversationsDB
import com.glooboo.umessage.extensions.messagesDB
import com.glooboo.umessage.models.Conversation
import com.glooboo.umessage.models.Message
import com.glooboo.umessage.testing.CountingInboxProvider
import com.glooboo.umessage.testing.offMainThread
import com.glooboo.umessage.testing.resetSharedMessagesDatabase
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * J2 (list speed): the saved rows are shown before the phone's store is read in full, a return to the list never waits
 * behind a running full pass, a chat event is never a full pass, and full passes asked for while the list is away wait
 * for it. "publish" and "store" in [events] are a list handed on and a read of the store's threads table.
 */
@RunWith(AndroidJUnit4::class)
class ConversationListSyncSpeedTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var provider: CountingInboxProvider
    private val executor = Executors.newSingleThreadExecutor()
    private val quick = Executors.newSingleThreadExecutor()
    private val published: MutableList<List<Conversation>> = Collections.synchronizedList(ArrayList())
    private val events: MutableList<String> = Collections.synchronizedList(ArrayList())
    private lateinit var sync: ConversationListSync

    @Before
    fun setUp() {
        provider = CountingInboxProvider.install()
        provider.seed(threads = THREADS, messagesPerThread = 2)
        sync = newSync()
        sync.start()
        idle()
        provider.resetCounts()
        published.clear()
        events.clear()
    }

    @After
    fun tearDown() {
        provider.beforeThreadsQuery = null
        executor.shutdownNow()
        quick.shutdownNow()
        resetSharedMessagesDatabase()
    }

    private fun newSync() = ConversationListSync(
        context, executor, { list, _ ->
            published += list.toList()
            events += "publish"
        }, Executor { }, importMessages = {},
    ).also { it.quickExecutor = quick }

    private fun idle() {
        executor.submit {}.get(60, TimeUnit.SECONDS)
        quick.submit {}.get(60, TimeUnit.SECONDS)
    }

    /** Holds the sync's one thread until the returned latch is counted down, so requests made meanwhile merge. */
    private fun holdTheLane(): CountDownLatch {
        val gate = CountDownLatch(1)
        executor.execute { gate.await(30, TimeUnit.SECONDS) }
        return gate
    }

    private fun saved(): List<Conversation> = offMainThread { context.conversationsDB.getNonArchived() }

    private fun recordStoreReads() {
        provider.beforeThreadsQuery = { events += "store" }
    }

    @Test
    fun theSavedRowsArePublishedBeforeTheStoreIsReadInTheSameJob() {
        sync.quickExecutor = Executor { }
        recordStoreReads()
        val gate = holdTheLane()
        sync.requestFull()
        sync.requestRepublish()
        gate.countDown()
        idle()

        // Before J2 the job ran the full pass first and the republish after it.
        assertThat(events.first()).isEqualTo("publish")
        assertThat(events).contains("store")
    }

    @Test
    fun aReturnShowsTheSavedRowsWhileAFullPassIsStillRunning() {
        val row = saved().single { it.threadId == 15L }
        offMainThread { context.conversationsDB.insertOrUpdate(row.copy(read = false, unreadCount = 1)) }
        val inPass = CountDownLatch(1)
        val release = CountDownLatch(1)
        provider.beforeThreadsQuery = {
            provider.beforeThreadsQuery = null
            inPass.countDown()
            release.await(30, TimeUnit.SECONDS)
        }
        sync.requestFull()
        assertThat(inPass.await(30, TimeUnit.SECONDS)).isTrue()

        try {
            // The chat marked itself read; the user comes back to the list while the full pass reads the store.
            offMainThread { context.conversationsDB.markRead(15L) }
            published.clear()
            sync.requestReturn()
            quick.submit {}.get(30, TimeUnit.SECONDS)

            assertThat(release.count).isEqualTo(1)
            assertThat(published).isNotEmpty()
            assertThat(published.last().single { it.threadId == 15L }.read).isTrue()
        } finally {
            release.countDown()
        }
        idle()
    }

    @Test
    fun aChatEventWithAFullPassDueIsShownFirstAndReadsNothingMore() {
        sync.quickExecutor = Executor { }
        val row = saved().single { it.threadId == 7L }
        offMainThread { context.conversationsDB.insertOrUpdate(row.copy(snippet = "Saved by the receiver")) }
        offMainThread { context.conversationsDB.deleteThreadId(9) }
        recordStoreReads()
        val gate = holdTheLane()
        sync.requestFull()
        sync.requestThread(7)
        // A chat without a saved row is left to the full pass of the same job, not read from the store twice.
        sync.requestThread(9)
        gate.countDown()
        idle()

        assertThat(events.first()).isEqualTo("publish")
        assertThat(published.first().single { it.threadId == 7L }.snippet).isEqualTo("Saved by the receiver")
        // One full pass reads the threads table twice (the newest 50, then all); the chat events added no read.
        assertThat(provider.queryCount("threads")).isEqualTo(2)
        assertThat(published.last().map { it.threadId }).contains(9L)
    }

    @Test
    fun aChatEventIsNeverAFullPass() {
        sync.requestThread(7)
        idle()

        assertThat(provider.totalQueries()).isEqualTo(0)
        assertThat(published).hasSize(1)
    }

    @Test
    fun fullPassesAskedForWhileTheListIsAwayWaitAndRunOnceAfterTheSavedRows() {
        sync.requestAway()
        repeat(3) { sync.requestFull() }
        idle()
        assertThat(provider.totalQueries()).isEqualTo(0)

        recordStoreReads()
        sync.requestReturn()
        idle()

        assertThat(provider.queryCount("threads")).isEqualTo(2)
        assertThat(events.first()).isEqualTo("publish")
        assertThat(events).contains("store")
    }

    @Test
    fun aNewScreenReleasesAFullPassHeldWhileTheListWasAway() {
        sync.requestAway()
        sync.requestFull()
        idle()
        assertThat(provider.totalQueries()).isEqualTo(0)

        sync.requestShown()
        idle()

        assertThat(provider.queryCount("threads")).isEqualTo(2)
    }

    @Test
    fun withTheListShownAFullPassRunsAtOnce() {
        sync.requestAway()
        sync.requestReturn()
        idle()
        provider.resetCounts()

        sync.requestFull()
        idle()

        assertThat(provider.queryCount("threads")).isEqualTo(2)
    }

    /** The start's clean-up of placeholder chats, now from one read of the scheduled messages. */
    @Test
    fun startRemovesAnEmptyPlaceholderChatAndKeepsOneWithAScheduledMessage() {
        val empty = placeholder(EMPTY_PLACEHOLDER)
        val waiting = placeholder(WAITING_PLACEHOLDER)
        val inAnHour = (System.currentTimeMillis() / 1000 + 3600).toInt()
        offMainThread {
            context.conversationsDB.insertOrUpdate(empty)
            context.conversationsDB.insertOrUpdate(waiting)
            context.messagesDB.insertOrUpdate(scheduled(WAITING_PLACEHOLDER, inAnHour))
        }

        newSync().start()
        idle()

        val ids = saved().map { it.threadId }
        assertThat(ids).doesNotContain(EMPTY_PLACEHOLDER)
        assertThat(ids).contains(WAITING_PLACEHOLDER)
    }

    private fun placeholder(threadId: Long) = Conversation(
        threadId = threadId, snippet = "", date = 1, read = true, title = "Placeholder", photoUri = "",
        isGroupConversation = false, phoneNumber = "+15550001234", isScheduled = true,
    )

    private fun scheduled(threadId: Long, date: Int) = Message(
        id = threadId + 1, body = "Later", type = Sms.MESSAGE_TYPE_QUEUED, status = -1, participants = ArrayList(),
        date = date, read = true, threadId = threadId, isMMS = false, attachment = null, senderPhoneNumber = "",
        senderName = "", senderPhotoUri = "", subscriptionId = -1, isScheduled = true,
    )

    private companion object {
        const val THREADS = 300
        const val EMPTY_PLACEHOLDER = 1_500_000_001L
        const val WAITING_PLACEHOLDER = 1_500_000_003L
    }
}
