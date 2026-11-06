package com.glooboo.umessage.ui.thread

import android.provider.Telephony
import com.glooboo.umessage.models.Attachment
import com.glooboo.umessage.models.Message
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

class ThreadMessageStateTest {

    private fun message(id: Long, date: Int = id.toInt(), scheduled: Boolean = false) = Message(
        id = id,
        body = "m$id",
        type = Telephony.Sms.MESSAGE_TYPE_INBOX,
        status = 0,
        participants = ArrayList(),
        date = date,
        read = true,
        threadId = 1,
        isMMS = false,
        attachment = null,
        senderPhoneNumber = "+1",
        senderName = "+1",
        senderPhotoUri = "",
        subscriptionId = 1,
        isScheduled = scheduled,
    )

    private fun ids(messages: List<Message>) = messages.map { it.id }.sorted()

    // TU-06: a refresh after a new message kept only the newest page.
    @Test
    fun refreshKeepsOlderMessagesThatWerePagedIn() {
        val current = (1L..120L).map { message(it) } // 70 older ones were paged in on top of the first 50
        val fresh = (72L..121L).map { message(it) } // newest 50, including the new message 121
        val merged = mergeRefreshedMessages(current, fresh, freshIsWholeThread = false)
        assertEquals((1L..121L).toList(), ids(merged))
    }

    @Test
    fun refreshOfTheWholeThreadDropsMessagesDeletedElsewhere() {
        val current = (1L..10L).map { message(it) }
        val fresh = (1L..10L).filter { it != 4L }.map { message(it) }
        assertEquals(fresh.map { it.id }, ids(mergeRefreshedMessages(current, fresh, freshIsWholeThread = true)))
    }

    @Test
    fun refreshDoesNotKeepStaleScheduledMessages() {
        val current = listOf(message(1), message(2, date = 5_000, scheduled = true))
        val fresh = listOf(message(3))
        assertEquals(listOf(1L, 3L), ids(mergeRefreshedMessages(current, fresh, freshIsWholeThread = false)))
    }

    // TU-10: background tasks overwrote each other's changes to the list.
    @Test
    fun concurrentUpdatesAreAllKept() {
        val list = ThreadMessageList()
        val start = CountDownLatch(1)
        val workers = (0 until 8).map { worker ->
            thread {
                start.await()
                repeat(200) { i ->
                    val id = worker * 1_000L + i
                    list.update { current -> current + message(id) }
                }
            }
        }
        start.countDown()
        workers.forEach { it.join() }
        assertEquals(8 * 200, list.current.size)
    }

    // TU-08: one failed page load used to stop paging for good.
    @Test
    fun historyIsExhaustedOnlyWhenTheProviderConfirmsIt() {
        assertTrue(isHistoryExhausted(pageSize = 0, probeHasOlder = false))
        assertFalse(isHistoryExhausted(pageSize = 0, probeHasOlder = null))
        assertFalse(isHistoryExhausted(pageSize = 0, probeHasOlder = true))
        assertFalse(isHistoryExhausted(pageSize = 3, probeHasOlder = false))
    }

    // TU-02 / T6: the composer was cleared even when the send failed.
    @Test
    fun composerIsClearedOnlyAfterAHandedOffSend() {
        assertTrue(shouldClearComposerAfterSend(handedOff = true, fromComposer = true))
        assertFalse(shouldClearComposerAfterSend(handedOff = false, fromComposer = true))
        // TU-14: "Send now" on a scheduled bubble leaves the user's draft alone.
        assertFalse(shouldClearComposerAfterSend(handedOff = true, fromComposer = false))
    }

    // TU-12: a draft with a photo was deleted instead of saved.
    @Test
    fun draftTextIsSavedEvenWithAttachments() {
        assertEquals("See you at the party", draftToSave("  See you at the party "))
        assertNull(draftToSave("   "))
    }

    // TU-23: the save dialog offered the uri's last part ("1234") as the file name.
    @Test
    fun savedAttachmentGetsAProperName() {
        val named = Attachment(null, 1, "content://mms/part/1234", "image/jpeg", 0, 0, "IMG_2041.jpg")
        assertEquals("IMG_2041.jpg", saveFileName(named))
        val unnamed = named.copy(filename = "")
        assertEquals("1234.jpg", saveFileName(unnamed))
        val unnamedFile = named.copy(filename = "", mimetype = "application/pdf")
        assertEquals("1234", saveFileName(unnamedFile))
    }
}
