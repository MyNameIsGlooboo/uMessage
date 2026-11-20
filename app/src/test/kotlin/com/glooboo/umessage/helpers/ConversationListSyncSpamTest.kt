package com.glooboo.umessage.helpers

import android.content.Context
import android.provider.Telephony
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.extensions.conversationsDB
import com.glooboo.umessage.extensions.getMessages
import com.glooboo.umessage.extensions.messagesDB
import com.glooboo.umessage.models.Conversation
import com.glooboo.umessage.models.Message
import com.glooboo.umessage.spam.FirstRun
import com.glooboo.umessage.spam.FirstRunPhase
import com.glooboo.umessage.spam.FirstRunState
import com.glooboo.umessage.spam.FirstRunStore
import com.glooboo.umessage.spam.FirstRunTrigger
import com.glooboo.umessage.spam.SpamSorting
import com.glooboo.umessage.spam.SpamThread
import com.glooboo.umessage.spam.engine.SpamCause
import com.glooboo.umessage.testing.CountingInboxProvider
import com.glooboo.umessage.testing.ProviderTruthFlagRule
import com.glooboo.umessage.testing.offMainThread
import com.glooboo.umessage.testing.resetSharedMessagesDatabase
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Step B in the list sync: the Messages list always leaves Spam chats out (B-4: before the first sort only texts that
 * arrived as spam have a row, and the sort's transaction is the publish, so no state can lag behind it), and a thread
 * another app deleted is forgotten with everything the app kept of it (S19).
 */
@RunWith(AndroidJUnit4::class)
class ConversationListSyncSpamTest {

    @get:Rule
    val flag = ProviderTruthFlagRule(enabled = true)

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var provider: CountingInboxProvider
    private val executor = Executors.newSingleThreadExecutor()
    private val published: MutableList<List<Conversation>> = Collections.synchronizedList(ArrayList())
    private lateinit var sync: ConversationListSync

    @Before
    fun setUp() {
        writeState(FirstRunState())
        provider = CountingInboxProvider.install()
        provider.seed(threads = THREADS, messagesPerThread = 2)
        sync = ConversationListSync(context, executor, { list, _ -> published += list.toList() }, Executor { }) { }
        sync.start()
        idle()
    }

    @After
    fun tearDown() {
        idle()
        executor.shutdownNow()
        writeState(FirstRunState())
        resetSharedMessagesDatabase()
    }

    private fun writeState(state: FirstRunState) {
        FirstRunStore(context.getSharedPreferences(FirstRunStore.PREFS, Context.MODE_PRIVATE)).write(state)
        FirstRun.reload(context)
    }

    /** Waits for the list sync and for the sorting thread (a new chat row asks for a sort). */
    private fun idle() {
        executor.submit {}.get(30, TimeUnit.SECONDS)
        val sorter = CountDownLatch(1)
        SpamSorting.execute(Runnable { sorter.countDown() })
        assertThat(sorter.await(30, TimeUnit.SECONDS)).isTrue()
    }

    private fun listed(): List<Long> {
        sync.requestRepublish()
        idle()
        return published.last().map { it.threadId }
    }

    private fun markSpam(threadId: Long) = offMainThread {
        val number = context.conversationsDB.getConversationWithThreadId(threadId)!!.phoneNumber
        context.conversationsDB.insertSpamThread(SpamThread(threadId, number, SpamCause.UNKNOWN, spamSince = 1L))
    }

    @Test
    fun aChatWithASpamRowIsOutOfMessagesBeforeDuringAndAfterTheFirstSort() {
        // B-4, B-25: the list never waits for the runner's state, so no publish race can show a Spam chat.
        markSpam(2)
        assertThat(listed()).containsExactly(1L, 3L, 4L)

        writeState(FirstRunState(phase = FirstRunPhase.SORT, trigger = FirstRunTrigger.UPDATE))
        assertThat(listed()).containsExactly(1L, 3L, 4L)

        writeState(FirstRunState(phase = FirstRunPhase.DONE, sortPublished = true))
        assertThat(listed()).containsExactly(1L, 3L, 4L)
    }

    @Test
    fun withTheFlagOffTheMessagesListIsAs0414() {
        markSpam(2)
        writeState(FirstRunState(phase = FirstRunPhase.DONE, sortPublished = true))
        ProviderTruthFlag.enabled = false

        assertThat(listed()).containsExactly(1L, 2L, 3L, 4L)
    }

    @Test
    fun aSpamChatKeepsItsCustomTitleThroughAFullPass() {
        // S1: the sync reads every row, so a Spam chat is never taken for a new one and rewritten.
        markSpam(2)
        writeState(FirstRunState(phase = FirstRunPhase.DONE, sortPublished = true))
        offMainThread { context.conversationsDB.updateTitle(2, "My title", usesCustomTitle = true) }

        sync.requestFull()
        idle()

        val row = offMainThread { context.conversationsDB.getConversationWithThreadId(2) }!!
        assertThat(row.title).isEqualTo("My title")
        assertThat(offMainThread { context.conversationsDB.isInSpam(2) }).isTrue()
    }

    @Test
    fun aThreadDeletedByAnotherAppIsForgottenWithItsNotesButNotItsScheduledMessages() {
        seedThreeExtras()

        provider.deleteThread(3)
        sync.requestFull()
        idle()

        offMainThread {
            assertThat(context.conversationsDB.getConversationWithThreadId(3)).isNull()
            assertThat(context.conversationsDB.getSpamThread(3)).isNull()
            assertThat(context.messagesDB.getThreadMessages(3).map { it.id }).containsExactly(SCHEDULED_ID)
        }
        assertThat(runBlocking { context.messageNotes.exportAll() }).isEmpty()
    }

    @Test
    fun withTheFlagOffAVanishedThreadOnlyLeavesTheList() {
        seedThreeExtras()
        ProviderTruthFlag.enabled = false

        provider.deleteThread(3)
        sync.requestFull()
        idle()

        offMainThread { assertThat(context.conversationsDB.getConversationWithThreadId(3)).isNull() }
        assertThat(runBlocking { context.messageNotes.exportAll() }).hasSize(1)
    }

    @Test
    fun anEmptyStoreReadForgetsNothing() {
        // B2-1: an empty but complete read (an OEM permission layer, a store just rebuilt) is not "every chat deleted".
        seedThreeExtras()

        (1L..THREADS).forEach { provider.deleteThread(it) }
        sync.requestFull()
        idle()

        offMainThread {
            assertThat(context.conversationsDB.getAllRows()).isEmpty()
            assertThat(context.conversationsDB.getSpamThread(3)).isNotNull()
            assertThat(context.messagesDB.getThreadMessages(3)).hasSize(3)
        }
        assertThat(runBlocking { context.messageNotes.exportAll() }).hasSize(1)
    }

    @Test
    fun aMassDisappearanceForgetsNothing() {
        // B2-1: more than 20 cached chats and more than half of them gone at once: only the cached rows go.
        addThreads(BIG_CACHE)
        seedThreeExtras()

        (3L..BIG_CACHE).forEach { provider.deleteThread(it) }
        sync.requestFull()
        idle()

        offMainThread {
            assertThat(context.conversationsDB.getAllRows().map { it.threadId }).containsExactly(1L, 2L)
            assertThat(context.conversationsDB.getSpamThread(3)).isNotNull()
        }
        assertThat(runBlocking { context.messageNotes.exportAll() }).hasSize(1)
    }

    @Test
    fun aChatDeletedElsewhereIsStillForgottenInABigCache() {
        // B2-1: a few vanished chats are trusted; the one deleted by another app loses its notes as before.
        addThreads(BIG_CACHE)
        seedThreeExtras()

        provider.deleteThread(3)
        sync.requestFull()
        idle()

        offMainThread {
            assertThat(context.conversationsDB.getAllRows()).hasSize(BIG_CACHE.toInt() - 1)
            assertThat(context.conversationsDB.getSpamThread(3)).isNull()
        }
        assertThat(runBlocking { context.messageNotes.exportAll() }).isEmpty()
    }

    /** Threads 5 to [last] in the store, one text each, cached by a full pass. */
    private fun addThreads(last: Long) {
        (THREADS + 1L..last).forEach {
            val dateMs = CountingInboxProvider.BASE_MS + it * 100_000
            provider.addThread(it, smsId = 1_000 + it, body = "Hi", dateMs = dateMs)
        }
        sync.requestFull()
        idle()
        assertThat(offMainThread { context.conversationsDB.getAllRows() }).hasSize(last.toInt())
    }

    /** Thread 3: a cached message with a note, a scheduled message and a Spam row. */
    private fun seedThreeExtras() {
        markSpam(3)
        offMainThread {
            val messages = context.getMessages(3, includeScheduledMessages = false)
            context.messagesDB.insertOrIgnoreAll(messages)
            runBlocking { context.messageNotes.setNote(messages.first(), "Gate code for the visit") }
            context.messagesDB.insertOrUpdate(
                Message(
                    id = SCHEDULED_ID, body = "Later", type = Telephony.Sms.MESSAGE_TYPE_QUEUED, status = -1,
                    participants = ArrayList(), date = 2_000_000_000, read = true, threadId = 3, isMMS = false,
                    attachment = null, senderPhoneNumber = "", senderName = "", senderPhotoUri = "",
                    subscriptionId = -1, isScheduled = true,
                )
            )
        }
        assertThat(runBlocking { context.messageNotes.exportAll() }).hasSize(1)
    }

    private companion object {
        const val THREADS = 4
        const val BIG_CACHE = 30L
        const val SCHEDULED_ID = 1_500_000_000L
    }
}
