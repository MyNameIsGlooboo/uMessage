package com.glooboo.umessage.helpers

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.extensions.config
import com.glooboo.umessage.extensions.conversationsDB
import com.glooboo.umessage.extensions.messagesDB
import com.glooboo.umessage.models.Conversation
import com.glooboo.umessage.models.Message
import com.glooboo.umessage.spam.FirstRun
import com.glooboo.umessage.spam.FirstRunPhase
import com.glooboo.umessage.spam.FirstRunState
import com.glooboo.umessage.spam.FirstRunStore
import com.glooboo.umessage.spam.SpamThread
import com.glooboo.umessage.spam.engine.SpamCause
import com.glooboo.umessage.testing.RETENTION_CODE_BODY
import com.glooboo.umessage.testing.offMainThread
import com.glooboo.umessage.testing.resetSharedMessagesDatabase
import com.glooboo.umessage.testing.retentionMessage
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Provider truth, step H (answer 6, 3.5): the clean-ups never touch a chat in Spam, and nothing moves before the first
 * sort is published or while a first-run pass works. Fake numbers only.
 */
@RunWith(AndroidJUnit4::class)
class RetentionRunnerSpamTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val now = 400 * MessageRetention.DAY_MS
    private val oldDate = ((now - 200 * MessageRetention.DAY_MS) / 1000).toInt()

    @Before
    fun setUp() {
        resetSharedMessagesDatabase()
        context.config.useRecycleBin = true
        context.cleanupPrefs.codeDays = 30
        context.cleanupPrefs.oldMessageDays = 0
        writeState(FirstRunState(phase = FirstRunPhase.DONE, sortPublished = true))
        offMainThread {
            listOf(SPAM, SHOWN).forEach { thread ->
                context.conversationsDB.insertOrUpdate(
                    Conversation(
                        threadId = thread, snippet = "", date = oldDate, read = true, title = number(thread),
                        photoUri = "", isGroupConversation = false, phoneNumber = number(thread),
                    ),
                )
            }
            context.conversationsDB.insertSpamThread(SpamThread(SPAM, number(SPAM), SpamCause.UNKNOWN, spamSince = 1L))
            store(
                retentionMessage(1, RETENTION_CODE_BODY, date = oldDate, threadId = SPAM),
                retentionMessage(2, RETENTION_CODE_BODY, date = oldDate, threadId = SHOWN),
            )
        }
    }

    @After
    fun tearDown() {
        writeState(FirstRunState())
        resetSharedMessagesDatabase()
    }

    private fun number(thread: Long) = "+155500012$thread"

    private fun store(vararg messages: Message) = messages.forEach { context.messagesDB.insertOrUpdate(it) }

    private fun writeState(state: FirstRunState) {
        FirstRunStore(context.getSharedPreferences(FirstRunStore.PREFS, Context.MODE_PRIVATE)).write(state)
        FirstRun.reload(context)
    }

    private fun binned(): List<Long> =
        offMainThread { context.messagesDB.getAllRecycleBinEntries().map { it.id }.sorted() }

    @Test
    fun aCodeInASpamChatStaysAndTheOtherGoes() {
        val counted = offMainThread { RetentionRunner(context).countCodes(30, now) }
        val moved = offMainThread { RetentionRunner(context).runNow(now) }

        assertThat(counted).isEqualTo(1)
        assertThat(moved).isEqualTo(1)
        assertThat(binned()).containsExactly(2L)
    }

    @Test
    fun theDailyRunAndTheConfirmedCodeCleanupSkipSpamToo() {
        offMainThread { RetentionRunner(context).runIfDue(now) }
        val codes = offMainThread { RetentionRunner(context).moveCodesNow(30, now) }

        assertThat(binned()).containsExactly(2L)
        assertThat(codes).isEmpty()
    }

    @Test
    fun nothingMovesBeforeTheFirstSortIsPublished() {
        writeState(FirstRunState(phase = FirstRunPhase.NOT_STARTED, sortPublished = false))

        offMainThread { RetentionRunner(context).runIfDue(now) }
        val moved = offMainThread { RetentionRunner(context).runNow(now) }
        val codes = offMainThread { RetentionRunner(context).moveCodesNow(30, now) }

        assertThat(moved).isEqualTo(0)
        assertThat(codes).isEmpty()
        assertThat(binned()).isEmpty()
    }

    @Test
    fun nothingMovesWhileAFirstRunPassWorks() {
        writeState(FirstRunState(phase = FirstRunPhase.HISTORY, sortPublished = true))

        val moved = offMainThread { RetentionRunner(context).runNow(now) }

        assertThat(moved).isEqualTo(0)
        assertThat(binned()).isEmpty()
    }

    @Test
    fun theDailyRunThatWaitedForTheSortRunsOnceItIsPublished() {
        writeState(FirstRunState(phase = FirstRunPhase.NOT_STARTED, sortPublished = false))
        offMainThread { RetentionRunner(context).runIfDue(now) }
        writeState(FirstRunState(phase = FirstRunPhase.DONE, sortPublished = true))

        offMainThread { RetentionRunner(context).runIfDue(now + 1000) }

        assertThat(binned()).containsExactly(2L)
    }

    private companion object {
        const val SPAM = 61L
        const val SHOWN = 62L
    }
}
