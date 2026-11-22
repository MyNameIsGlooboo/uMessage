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

    private val cachedFlags: MutableList<Boolean> = Collections.synchronizedList(ArrayList())

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
        cachedFlags.clear()
    }

    @After
    fun tearDown() {
        provider.beforeThreadsQuery = null
        executor.shutdownNow()
        quick.shutdownNow()
        resetSharedMessagesDatabase()
    }

    private fun newSync() = ConversationListSync(
        context, executor, { list, cached ->
            published += list.toList()
            cachedFlags += cached
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

    private fun saved(): List<Conversation> =
        offMainThread { context.conversationsDB.getAllRows().filterNot { it.isArchived } }

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

    /** J2-1: the full pass writes the store's date over a bumped row; the bump after it puts the chat back on top. */
    @Test
    fun aBumpedChatStaysBumpedAfterTheFullPassOfTheSameJob() {
        val before = saved().single { it.threadId == 11L }
        val sent = message(500_000, thread = 11, body = "Sent from the chat", date = before.date + 60)
        offMainThread { context.messagesDB.insertOrUpdate(sent) }
        sync.requestAway()
        sync.requestFull()
        sync.requestReturn()
        idle()

        assertThat(provider.queryCount("threads")).isEqualTo(2)
        assertThat(saved().single { it.threadId == 11L }.date).isEqualTo(before.date + 60)
        val last = published.last().single { it.threadId == 11L }
        assertThat(last.date).isEqualTo(before.date + 60)
        assertThat(last.snippet).isEqualTo("Sent from the chat")
    }

    /** J2-2: a burst of chat events during a full pass gives a bounded number of quick publishes, on one thread. */
    @Test
    fun aBurstOfChatEventsDuringAPassGivesFewPublishesOnOneThread() {
        val burst = ConversationListSync(
            context, executor, { list, _ -> published += list.toList() }, Executor { }, importMessages = {},
        )
        val inPass = CountDownLatch(1)
        val release = CountDownLatch(1)
        provider.beforeThreadsQuery = {
            provider.beforeThreadsQuery = null
            inPass.countDown()
            release.await(30, TimeUnit.SECONDS)
        }
        burst.requestFull()
        assertThat(inPass.await(30, TimeUnit.SECONDS)).isTrue()
        try {
            val lane = ConversationListSync.sharedQuickLane
            val gate = CountDownLatch(1)
            lane.execute { gate.await(30, TimeUnit.SECONDS) }
            published.clear()
            repeat(BURST) { burst.requestThread(it + 1L) }
            gate.countDown()
            lane.submit {}.get(30, TimeUnit.SECONDS)

            assertThat(published.size).isAtMost(2)
            assertThat(published).isNotEmpty()
            val quickThreads = Thread.getAllStackTraces().keys.count { it.name == "conversation-list-quick-shared" }
            assertThat(quickThreads).isAtMost(1)
        } finally {
            release.countDown()
        }
        idle()
    }

    /** J2-3: before the first full pass has published, every list is handed on as cached ("importing" stays on). */
    @Test
    fun untilTheFirstFullPassPublishesEveryListIsCached() {
        val first = newSync()
        val inPass = CountDownLatch(1)
        val release = CountDownLatch(1)
        provider.beforeThreadsQuery = {
            provider.beforeThreadsQuery = null
            inPass.countDown()
            release.await(30, TimeUnit.SECONDS)
        }
        first.start()
        assertThat(inPass.await(30, TimeUnit.SECONDS)).isTrue()
        try {
            first.requestReturn()
            first.requestThread(7)
            quick.submit {}.get(30, TimeUnit.SECONDS)

            assertThat(cachedFlags.size).isAtLeast(2)
            assertThat(cachedFlags.all { it }).isTrue()
        } finally {
            release.countDown()
        }
        idle()
        assertThat(cachedFlags.last()).isFalse()
    }

    /** Reviewer: a chat event and a republish in one job publish once; the chat's publish read after the request. */
    @Test
    fun aJobWithAChatEventAndARepublishPublishesOnce() {
        sync.quickExecutor = Executor { }
        val gate = holdTheLane()
        sync.requestThread(7)
        sync.requestRepublish()
        gate.countDown()
        idle()

        assertThat(published).hasSize(1)
    }

    @Test
    fun aChatEventWhileTheListIsAwayIsPublishedAndNotHeld() {
        sync.requestAway()
        sync.requestThread(7)
        idle()

        assertThat(published).hasSize(1)
        assertThat(provider.totalQueries()).isEqualTo(0)
    }

    /** MC-03 with two lanes: a quick publish waits while the job's publish is being handed on. */
    @Test
    fun theTwoLanesPublishInReadOrder() {
        val worker = Executors.newSingleThreadExecutor { Thread(it, "test-worker") }
        val lane = Executors.newSingleThreadExecutor { Thread(it, "test-quick") }
        val order: MutableList<String> = Collections.synchronizedList(ArrayList())
        val inPublish = CountDownLatch(1)
        val release = CountDownLatch(1)
        val ordered = ConversationListSync(context, worker, { _, _ ->
            val name = Thread.currentThread().name
            order += name
            if (name == "test-worker" && inPublish.count == 1L) {
                inPublish.countDown()
                release.await(30, TimeUnit.SECONDS)
            }
        }, Executor { }, importMessages = {}).also { it.quickExecutor = lane }
        try {
            ordered.start()
            assertThat(inPublish.await(30, TimeUnit.SECONDS)).isTrue()
            ordered.requestRepublish()
            Thread.sleep(300)
            assertThat(order).containsExactly("test-worker")
        } finally {
            release.countDown()
        }
        worker.submit {}.get(60, TimeUnit.SECONDS)
        lane.submit {}.get(60, TimeUnit.SECONDS)
        worker.shutdown()
        lane.shutdown()
        assertThat(order.first()).isEqualTo("test-worker")
        assertThat(order).contains("test-quick")
    }

    @Test
    fun aChatDeletedElsewhereLeavesTheListAfterTheHeldPass() {
        provider.deleteThread(3)
        sync.requestAway()
        sync.requestFull()
        idle()
        assertThat(saved().map { it.threadId }).contains(3L)

        sync.requestReturn()
        idle()

        assertThat(saved().map { it.threadId }).doesNotContain(3L)
        assertThat(published.last().map { it.threadId }).doesNotContain(3L)
    }

    /** Reviewer: a restore or an import asks for a full pass that is not held while the list is away. */
    @Test
    fun anUrgentFullPassRunsWhileTheListIsAway() {
        sync.requestAway()
        sync.requestFull(urgent = true)
        idle()

        assertThat(provider.queryCount("threads")).isEqualTo(2)

        // Only that one: the next request is held again.
        provider.resetCounts()
        sync.requestFull()
        idle()
        assertThat(provider.totalQueries()).isEqualTo(0)
    }

    /** J2-6: a new screen with a held pass shows the saved rows before the pass reads the store. */
    @Test
    fun aNewScreenShowsTheSavedRowsBeforeTheHeldPass() {
        sync.requestAway()
        sync.requestFull()
        idle()
        recordStoreReads()

        sync.requestShown()
        idle()

        assertThat(events.first()).isEqualTo("publish")
        assertThat(events).contains("store")
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

    private fun message(id: Long, thread: Long, body: String, date: Int) = Message(
        id = id, body = body, type = 2, status = -1, participants = ArrayList(), date = date, read = true,
        threadId = thread, isMMS = false, attachment = null, senderPhoneNumber = "", senderName = "",
        senderPhotoUri = "", subscriptionId = -1,
    )

    private companion object {
        const val THREADS = 300
        const val BURST = 20
        const val EMPTY_PLACEHOLDER = 1_500_000_001L
        const val WAITING_PLACEHOLDER = 1_500_000_003L
    }
}
