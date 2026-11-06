package com.glooboo.umessage.helpers

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.extensions.conversationsDB
import com.glooboo.umessage.extensions.getConversations
import com.glooboo.umessage.extensions.insertOrUpdateConversation
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
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * MC-02, MC-03, MC-19: how much of the phone's store each kind of refresh reads, on an inbox of 2000 chats and 20000
 * messages, and that refreshes run in order. Counts are provider queries and rows, not time.
 */
@RunWith(AndroidJUnit4::class)
class ConversationListSyncTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var provider: CountingInboxProvider
    private val executor = Executors.newSingleThreadExecutor()
    private val importer = Executors.newSingleThreadExecutor()
    private val published: MutableList<List<Conversation>> = Collections.synchronizedList(ArrayList())
    private lateinit var sync: ConversationListSync

    @Before
    fun setUp() {
        provider = CountingInboxProvider.install()
        provider.seed(threads = THREADS, messagesPerThread = MESSAGES_PER_THREAD)
        // The real message cache import, on a thread the test can wait for: the cache ends up full, as on a phone.
        sync = ConversationListSync(context, executor, { list, _ -> published += list.toList() }, importer)
        sync.start()
        idle()
        provider.resetCounts()
        published.clear()
    }

    @After
    fun tearDown() {
        executor.shutdownNow()
        importer.shutdownNow()
        importer.awaitTermination(30, TimeUnit.SECONDS)
        resetSharedMessagesDatabase()
    }

    /** Waits until every request made so far has run, the cache import included (each is a single thread). */
    private fun idle() {
        executor.submit {}.get(60, TimeUnit.SECONDS)
        importer.submit {}.get(120, TimeUnit.SECONDS)
    }

    private fun saved(): List<Conversation> = offMainThread { context.conversationsDB.getNonArchived() }

    @Test
    fun startFillsTheSavedListFromTheProvider() {
        assertThat(saved()).hasSize(THREADS)
    }

    @Test
    fun aNewMessageUpdatesOnlyItsChat() {
        // Before MC-02 every refresh event was a full pass. Measure one for comparison.
        sync.requestFull()
        idle()
        val fullQueries = provider.totalQueries()
        val fullRows = provider.rows.get()
        provider.resetCounts()

        // What SmsDelivery does on an incoming SMS: the phone stores it, the app caches the message and saves the
        // one chat's row.
        val dateMs = CountingInboxProvider.BASE_MS + 999_000_000
        provider.receive(id = 100_000, thread = 7, body = "Your code is 4821", dateMs = dateMs)
        offMainThread {
            context.messagesDB.insertOrUpdate(message(100_000, thread = 7, body = "Your code is 4821", date = (dateMs / 1000).toInt()))
            context.getConversations(threadId = 7).first().let { context.insertOrUpdateConversation(it) }
        }
        val receiverQueries = provider.totalQueries()
        val receiverRows = provider.rows.get()
        provider.resetCounts()
        published.clear()

        sync.requestThread(7)
        idle()

        println(
            "MC-02 query counts: full pass $fullQueries queries / $fullRows rows; receiver's one-chat read " +
                "$receiverQueries queries / $receiverRows rows; list update after it ${provider.totalQueries()} " +
                "queries / ${provider.rows.get()} rows"
        )
        assertThat(fullRows).isAtLeast(2 * THREADS)
        assertThat(provider.totalQueries()).isEqualTo(0)
        assertThat(receiverRows).isLessThan(10)
        val list = published.last()
        assertThat(list).hasSize(THREADS)
        assertThat(list.single { it.threadId == 7L }.snippet).isEqualTo("Your code is 4821")
    }

    @Test
    fun theCacheImportFillsEveryThreadWithoutChangingThePreviews() {
        val cached = offMainThread { context.messagesDB.getThreadMessages(7) }
        assertThat(cached).hasSize(MESSAGES_PER_THREAD)
        assertThat(saved().single { it.threadId == 7L }.snippet).isEqualTo("Message 70")
    }

    @Test
    fun aNewMessageDoesNotWaitForALongCacheImport() {
        val importing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val slowImporter = Executors.newSingleThreadExecutor()
        val updates: MutableList<List<Conversation>> = Collections.synchronizedList(ArrayList())
        val slow = ConversationListSync(
            context, executor, { list, _ -> updates += list.toList() }, slowImporter,
            importMessages = { importing.countDown(); release.await(30, TimeUnit.SECONDS) },
        )
        try {
            slow.requestFull()
            assertThat(importing.await(30, TimeUnit.SECONDS)).isTrue()

            offMainThread { context.conversationsDB.deleteThreadId(9) }
            slow.requestThread(9)
            executor.submit {}.get(30, TimeUnit.SECONDS)

            // The import is still held, yet the chat is back in the list.
            assertThat(release.count).isEqualTo(1)
            assertThat(updates.last().map { it.threadId }).contains(9L)
        } finally {
            release.countDown()
            slowImporter.shutdown()
            slowImporter.awaitTermination(30, TimeUnit.SECONDS)
        }
    }

    @Test
    fun aChatWithoutASavedRowIsReadAloneFromTheProvider() {
        offMainThread { context.conversationsDB.deleteThreadId(9) }
        provider.resetCounts()

        sync.requestThread(9)
        idle()

        assertThat(provider.queryCount("threads")).isEqualTo(1)
        assertThat(provider.rows.get()).isLessThan(10)
        assertThat(published.last().map { it.threadId }).contains(9L)
    }

    @Test
    fun deletedChatsStillDisappearOnTheFullPass() {
        provider.deleteThread(3)

        sync.requestThread(4)
        idle()
        assertThat(saved().map { it.threadId }).contains(3L)

        sync.requestFull()
        idle()
        assertThat(saved().map { it.threadId }).doesNotContain(3L)
        assertThat(published.last().map { it.threadId }).doesNotContain(3L)
        assertThat(published.last()).hasSize(THREADS - 1)
    }

    @Test
    fun aRotatedScreenDoesNotReadEverythingAgain() {
        sync.start()
        idle()
        assertThat(provider.totalQueries()).isEqualTo(0)
    }

    @Test
    fun refreshesWhileOneRunsAreMergedAndTheNewestListWins() {
        val inPass = CountDownLatch(1)
        val release = CountDownLatch(1)
        provider.beforeThreadsQuery = {
            provider.beforeThreadsQuery = null
            inPass.countDown()
            release.await(30, TimeUnit.SECONDS)
        }
        sync.requestFull()
        assertThat(inPass.await(30, TimeUnit.SECONDS)).isTrue()

        // While the first pass is held: a message arrives in a new chat, and several refreshes are asked for.
        provider.addThread(thread = THREADS + 1L, smsId = 900_000, body = "Hello from a new number", dateMs = CountingInboxProvider.BASE_MS + 999_000_000)
        repeat(3) { sync.requestFull() }
        sync.requestThread(THREADS + 1L)
        release.countDown()
        idle()

        // Each pass reads the threads table twice (the first 50, then all). Four requests made two passes.
        assertThat(provider.queryCount("threads")).isEqualTo(4)
        assertThat(published.last().map { it.threadId }).contains(THREADS + 1L)
        assertThat(published.last()).hasSize(THREADS + 1)
    }

    @Test
    fun resumeMovesUpChatsWithNewerSavedMessagesWithoutReadingTheProvider() {
        val before = saved().single { it.threadId == 11L }
        offMainThread {
            context.messagesDB.insertOrUpdate(message(id = 500_000, thread = 11, body = "Sent from the chat", date = before.date + 60))
        }

        sync.requestBump()
        idle()

        assertThat(provider.totalQueries()).isEqualTo(0)
        val bumped = published.last().single { it.threadId == 11L }
        assertThat(bumped.date).isEqualTo(before.date + 60)
        assertThat(bumped.snippet).isEqualTo("Sent from the chat")
    }

    @Test
    fun resumeWithNothingNewPublishesNothing() {
        sync.requestBump()
        idle()
        assertThat(published).isEmpty()
    }

    private fun message(id: Long, thread: Long, body: String, date: Int) = Message(
        id = id, body = body, type = 2, status = -1, participants = ArrayList(), date = date, read = true,
        threadId = thread, isMMS = false, attachment = null, senderPhoneNumber = "", senderName = "",
        senderPhotoUri = "", subscriptionId = -1,
    )

    private companion object {
        const val THREADS = 2000
        const val MESSAGES_PER_THREAD = 10
    }
}
