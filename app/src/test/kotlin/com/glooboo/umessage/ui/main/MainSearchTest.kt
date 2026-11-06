package com.glooboo.umessage.ui.main

import android.provider.Telephony
import com.glooboo.umessage.models.Conversation
import com.glooboo.umessage.models.Message
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainSearchTest {

    private fun conversation(threadId: Long, title: String, number: String, date: Int) = Conversation(
        threadId = threadId, snippet = "", date = date, read = true, title = title, photoUri = "p$threadId",
        isGroupConversation = false, phoneNumber = number,
    )

    private fun message(id: Long, body: String, sender: String, threadId: Long = 1, date: Int = id.toInt()) = Message(
        id = id, body = body, type = Telephony.Sms.MESSAGE_TYPE_INBOX, status = 0, participants = ArrayList(),
        date = date, read = true, threadId = threadId, isMMS = false, attachment = null,
        senderPhoneNumber = "+1", senderName = sender, senderPhotoUri = "", subscriptionId = 1,
    )

    @Test
    fun searchableNeedsTwoCharacters() {
        assertFalse(isSearchable(""))
        assertFalse(isSearchable("a"))
        assertTrue(isSearchable("ab"))
    }

    @Test
    fun highlightFindsAllOccurrencesIgnoringCase() {
        assertEquals(listOf(0..1, 4..5), highlightRanges("Abcdab", "ab"))
    }

    @Test
    fun highlightDoesNotOverlapAndHandlesEmpty() {
        assertEquals(listOf(0..1, 2..3), highlightRanges("aaaa", "aa"))
        assertTrue(highlightRanges("abc", "").isEmpty())
        assertTrue(highlightRanges("", "a").isEmpty())
        assertTrue(highlightRanges("ab", "abc").isEmpty())
    }

    @Test
    fun conversationsComeFirstThenMessagesNewestFirst() {
        val results = buildSearchResults(
            conversations = listOf(conversation(7, "Anna", "+100", 50)),
            messages = listOf(message(2, "hi", "Bob"), message(9, "yo", "Cy", threadId = 3)),
            formatDate = { "d$it" },
        )
        assertEquals(listOf(-1L, 9L, 2L), results.map { it.messageId })
        assertEquals("+100", results[0].snippet)
        assertEquals("d50", results[0].date)
        assertEquals(3L, results[1].threadId)
        assertEquals("p7", results[0].photoUri)
    }

    @Test
    fun messagesAreSortedByDateNotId() {
        // MC-17: an MMS with a small id can be newer than an SMS with a large one.
        val results = buildSearchResults(
            conversations = emptyList(),
            messages = listOf(
                message(900, "old", "A", date = 1_000),
                message(3, "new", "B", date = 5_000).copy(isMMS = true),
                message(50, "mid", "C", date = 2_000),
            ),
            formatDate = { "d$it" },
        )
        assertEquals(listOf("new", "mid", "old"), results.map { it.snippet })
    }
}
