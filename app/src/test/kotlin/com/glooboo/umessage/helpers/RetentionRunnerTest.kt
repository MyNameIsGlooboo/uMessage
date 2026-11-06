package com.glooboo.umessage.helpers

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.extensions.config
import com.glooboo.umessage.extensions.messagesDB
import com.glooboo.umessage.models.Message
import com.glooboo.umessage.models.StarredMessage
import com.glooboo.umessage.testing.RETENTION_CODE_BODY
import com.glooboo.umessage.testing.offMainThread
import com.glooboo.umessage.testing.resetSharedMessagesDatabase
import com.glooboo.umessage.testing.retentionMessage
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** I02, I45: the runner moves the right cached messages to the recycle bin, only with the bin on, and only that. */
@RunWith(AndroidJUnit4::class)
class RetentionRunnerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val now = 400 * MessageRetention.DAY_MS
    private val oldDate = ((now - 200 * MessageRetention.DAY_MS) / 1000).toInt()
    private val newDate = ((now - 2 * MessageRetention.DAY_MS) / 1000).toInt()

    @Before
    fun setUp() {
        resetSharedMessagesDatabase()
        context.config.useRecycleBin = true
        context.cleanupPrefs.codeDays = 0
        context.cleanupPrefs.oldMessageDays = 0
    }

    @After
    fun tearDown() = resetSharedMessagesDatabase()

    private fun store(vararg messages: Message) =
        offMainThread { messages.forEach { context.messagesDB.insertOrUpdate(it) } }

    private fun binned(): List<Long> =
        offMainThread { context.messagesDB.getAllRecycleBinEntries().map { it.id }.sorted() }

    private fun star(id: Long) = offMainThread {
        runBlocking { context.starredDB.insert(StarredMessage(id, false, 7, "BankName", 1, 0, 1, "kept")) }
    }

    @Test
    fun codesGoToTheBinAndTheRestStays() {
        store(
            retentionMessage(1, RETENTION_CODE_BODY, date = oldDate),
            retentionMessage(2, RETENTION_CODE_BODY, date = oldDate, read = false),
            retentionMessage(3, "See you at nine", date = oldDate),
            retentionMessage(4, RETENTION_CODE_BODY, date = newDate),
            retentionMessage(5, RETENTION_CODE_BODY, date = oldDate),
        )
        star(5)
        context.cleanupPrefs.codeDays = 30

        val moved = offMainThread { RetentionRunner(context).runNow(now) }

        assertThat(moved).isEqualTo(1)
        assertThat(binned()).containsExactly(1L)
    }

    @Test
    fun aSecondRunMovesNothingMore() {
        store(retentionMessage(1, RETENTION_CODE_BODY, date = oldDate))
        context.cleanupPrefs.codeDays = 30

        offMainThread { RetentionRunner(context).runNow(now) }
        val again = offMainThread { RetentionRunner(context).runNow(now) }

        assertThat(again).isEqualTo(0)
        assertThat(binned()).containsExactly(1L)
    }

    @Test
    fun withTheBinOffNothingMoves() {
        store(retentionMessage(1, RETENTION_CODE_BODY, date = oldDate))
        context.cleanupPrefs.codeDays = 30
        context.config.useRecycleBin = false

        offMainThread { RetentionRunner(context).runNow(now) }
        offMainThread { RetentionRunner(context).runIfDue(now) }

        assertThat(binned()).isEmpty()
    }

    @Test
    fun keepingMessagesLeavesNewerOnesUnreadOnesAndPinnedChats() {
        store(
            retentionMessage(1, date = oldDate),
            retentionMessage(2, date = newDate),
            retentionMessage(3, date = oldDate, threadId = 9),
            retentionMessage(4, date = oldDate, read = false),
        )
        context.config.pinnedConversations = setOf("9")
        context.cleanupPrefs.oldMessageDays = 90

        val preview = offMainThread { RetentionRunner(context).countOld(90, now) }
        offMainThread { RetentionRunner(context).runNow(now) }

        assertThat(preview).isEqualTo(1)
        assertThat(binned()).containsExactly(1L)
    }

    @Test
    fun theDailyCheckRunsOnlyOncePerInterval() {
        store(retentionMessage(1, RETENTION_CODE_BODY, date = oldDate))
        context.cleanupPrefs.codeDays = 30

        offMainThread { RetentionRunner(context).runIfDue(now) }
        store(retentionMessage(2, RETENTION_CODE_BODY, date = oldDate))
        offMainThread { RetentionRunner(context).runIfDue(now + 1000) }

        assertThat(binned()).containsExactly(1L)

        offMainThread { RetentionRunner(context).runIfDue(now + CleanupPrefs.CLEANUP_INTERVAL_MS) }

        assertThat(binned()).containsExactly(1L, 2L)
    }

    @Test
    fun nothingRunsWhileBothSettingsAreOff() {
        store(retentionMessage(1, RETENTION_CODE_BODY, date = oldDate))

        offMainThread { RetentionRunner(context).runIfDue(now) }

        assertThat(binned()).isEmpty()
    }
}
