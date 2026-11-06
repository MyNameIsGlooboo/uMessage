package com.glooboo.umessage.extensions

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.helpers.messageNotes
import com.glooboo.umessage.testing.FakeThreadStore
import com.glooboo.umessage.testing.offMainThread
import com.glooboo.umessage.testing.resetSharedMessagesDatabase
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A note stays while its message is in the recycle bin (also after it comes back) and goes when the message is
 * deleted for good, emptied from the bin or its chat is deleted.
 */
@RunWith(AndroidJUnit4::class)
class MessageNotesCleanupTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var store: FakeThreadStore

    @Before
    fun setUp() {
        store = FakeThreadStore.install()
        store.addThread(THREAD, FakeThreadStore.ADDRESS)
        store.addSms(1, THREAD, "Paid 12.50 at the shop", dateMs = 1_700_000_000_000)
        store.addSms(2, THREAD, "Paid 99.00 at the garage", dateMs = 1_700_000_100_000)
    }

    @After
    fun tearDown() = resetSharedMessagesDatabase()

    /** The note texts left, sorted. */
    private fun noteTexts() = runBlocking { context.messageNotes.exportAll().map { it.text }.sorted() }

    /** Caches both messages and gives each a note. */
    private fun cacheAndNoteBoth() = runBlocking {
        val messages = context.getMessages(THREAD, includeScheduledMessages = false)
        context.messagesDB.insertOrIgnoreAll(messages)
        messages.forEach { context.messageNotes.setNote(it, "note " + it.body.take(9)) }
        assertThat(noteTexts()).hasSize(2)
    }

    @Test
    fun movingToTheBinAndBackKeepsTheNote(): Unit = offMainThread {
        cacheAndNoteBoth()

        context.moveMessageToRecycleBin(1, false)
        assertThat(noteTexts()).hasSize(2)

        context.restoreMessageFromRecycleBin(1, false)
        assertThat(noteTexts()).hasSize(2)
    }

    @Test
    fun deletingAMessageForGoodRemovesOnlyItsNote(): Unit = offMainThread {
        cacheAndNoteBoth()

        context.deleteMessage(1, false)

        assertThat(noteTexts()).containsExactly("note Paid 99.0")
    }

    @Test
    fun emptyingTheBinRemovesTheNotesOfTheBinnedMessages(): Unit = offMainThread {
        cacheAndNoteBoth()
        context.moveMessageToRecycleBin(1, false)

        context.emptyMessagesRecycleBin()

        assertThat(noteTexts()).containsExactly("note Paid 99.0")
    }

    @Test
    fun deletingTheChatRemovesItsNotes(): Unit = offMainThread {
        cacheAndNoteBoth()

        context.deleteConversation(THREAD)

        assertThat(noteTexts()).isEmpty()
    }

    private companion object {
        const val THREAD = 7L
    }
}
