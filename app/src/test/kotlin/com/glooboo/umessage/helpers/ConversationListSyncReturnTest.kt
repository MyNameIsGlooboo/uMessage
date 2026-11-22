package com.glooboo.umessage.helpers

import android.content.Context
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
import org.robolectric.shadows.ShadowLog
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * J2b: with the senders passing their thread id, a visit to a chat costs no full pass, and the list still shows what
 * changed when it comes back: a first text to a new number (J2-5), a text sent while a new screen was made (R2-2). And
 * the leftovers of J2: a first pass that throws (R2-3), a publish that throws (R2-5) and the
 * rows-built timing line (R2-1).
 */
@RunWith(AndroidJUnit4::class)
class ConversationListSyncReturnTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var provider: CountingInboxProvider
    private val executor = Executors.newSingleThreadExecutor()
    private val quick = Executors.newSingleThreadExecutor()
    private val published: MutableList<List<Conversation>> = Collections.synchronizedList(ArrayList())
    private val cachedFlags: MutableList<Boolean> = Collections.synchronizedList(ArrayList())

    /** Set by a test: the next hand-on throws, as a failing publish would. */
    @Volatile
    private var failNextPublish = false
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
            if (failNextPublish) {
                failNextPublish = false
                error("publish failed")
            }
            published += list.toList()
            cachedFlags += cached
        }, Executor { }, importMessages = {},
    ).also { it.quickExecutor = quick }

    private fun idle() {
        executor.submit {}.get(60, TimeUnit.SECONDS)
        quick.submit {}.get(60, TimeUnit.SECONDS)
    }

    private fun holdTheLane(): CountDownLatch {
        val gate = CountDownLatch(1)
        executor.execute { gate.await(30, TimeUnit.SECONDS) }
        return gate
    }

    private fun saved(): List<Conversation> =
        offMainThread { context.conversationsDB.getAllRows().filterNot { it.isArchived } }

    /** J2-5: the sent receiver names the new chat; it shows on return although no full pass ran or was held. */
    @Test
    fun aFirstTextToANewNumberShowsOnReturnWithoutAFullPass() {
        sync.requestAway()
        provider.addThread(thread = NEW_THREAD, smsId = 900_000, body = "Hi", dateMs = CountingInboxProvider.BASE_MS)

        sync.requestThread(NEW_THREAD)
        idle()
        sync.requestReturn()
        idle()

        assertThat(sync.fullRequestCount).isEqualTo(0)
        // One read of the one thread, never the whole threads table (a full pass reads it twice).
        assertThat(provider.queryCount("threads")).isEqualTo(1)
        assertThat(published.last().map { it.threadId }).contains(NEW_THREAD)
    }

    /** R3-1: the chat saves its sent text after the return's bump; the sent report's chat event moves the chat up. */
    @Test
    fun aTextSavedAfterTheReturnMovesItsChatUpOnTheChatEvent() {
        val before = saved().single { it.threadId == 11L }
        sync.requestAway()
        sync.requestReturn()
        idle()
        offMainThread { context.messagesDB.insertOrUpdate(sent(thread = 11, date = before.date + 60)) }

        sync.requestThread(11)
        idle()

        assertThat(provider.totalQueries()).isEqualTo(0)
        val row = published.last().single { it.threadId == 11L }
        assertThat(row.date).isEqualTo(before.date + 60)
        assertThat(row.snippet).isEqualTo("Sent from the chat")
    }

    /** R2-2: a new screen after the list was away bumps a sent text up, with no full pass held. */
    @Test
    fun aNewScreenAfterTheListWasAwayBumpsWithNothingHeld() {
        val before = saved().single { it.threadId == 11L }
        sync.requestAway()
        offMainThread { context.messagesDB.insertOrUpdate(sent(thread = 11, date = before.date + 60)) }

        sync.requestShown()
        idle()

        assertThat(provider.totalQueries()).isEqualTo(0)
        assertThat(published).isNotEmpty()
        val row = published.last().single { it.threadId == 11L }
        assertThat(row.date).isEqualTo(before.date + 60)
        assertThat(row.snippet).isEqualTo("Sent from the chat")
    }

    /** R2-2: a new screen of a list in sight (a rotation in front) publishes nothing. */
    @Test
    fun aNewScreenOfAListInSightPublishesNothing() {
        sync.requestShown()
        idle()

        assertThat(published).isEmpty()
    }

    /** R2-3: a first full pass that throws still ends "importing"; later lists are not cached ones. */
    @Test
    fun aFirstPassThatThrowsStillEndsImporting() {
        val first = newSync()
        provider.beforeThreadsQuery = {
            provider.beforeThreadsQuery = null
            throw OutOfMemoryError("a huge inbox")
        }
        first.start()
        idle()
        cachedFlags.clear()

        first.requestThread(7)
        idle()

        assertThat(cachedFlags).isNotEmpty()
        assertThat(cachedFlags.last()).isFalse()
    }

    /** R2-5: the chat's publish of a round throws; the round's republish is not skipped for it. */
    @Test
    fun aPublishThatThrowsDoesNotSkipTheRepublish() {
        sync.quickExecutor = Executor { }
        val gate = holdTheLane()
        sync.requestThread(7)
        sync.requestRepublish()
        failNextPublish = true
        gate.countDown()
        idle()

        assertThat(published).hasSize(1)
    }

    /** R2-1: only rows built from a list published after the mark are timed, once per mark. */
    @Test
    fun theRowsBuiltLineTimesOnlyAListPublishedAfterTheMark() {
        ShadowLog.clear()
        ListTiming.mark()
        val oldList = ListTiming.marks() - 1

        ListTiming.rowsBuilt(context, rows = 5, publishedAt = oldList)
        assertThat(rowsBuiltLines()).isEqualTo(0)

        ListTiming.rowsBuilt(context, rows = 5, publishedAt = ListTiming.marks())
        ListTiming.rowsBuilt(context, rows = 5, publishedAt = ListTiming.marks())
        assertThat(rowsBuiltLines()).isEqualTo(1)
    }

    private fun rowsBuiltLines() = ShadowLog.getLogsForTag(ListTiming.TAG).count { it.msg.startsWith("rows built") }

    private fun sent(thread: Long, date: Int) = Message(
        id = 500_000, body = "Sent from the chat", type = 2, status = -1, participants = ArrayList(), date = date,
        read = true, threadId = thread, isMMS = false, attachment = null, senderPhoneNumber = "", senderName = "",
        senderPhotoUri = "", subscriptionId = -1,
    )

    private companion object {
        const val THREADS = 60
        const val NEW_THREAD = THREADS + 1L
    }
}
