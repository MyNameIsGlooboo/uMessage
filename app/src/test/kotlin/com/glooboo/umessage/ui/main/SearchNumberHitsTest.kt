package com.glooboo.umessage.ui.main

import com.glooboo.umessage.models.Conversation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** CL-15 and CL-10: number hits in the Conversations section, shown in the shared display form. */
class SearchNumberHitsTest {

    private fun conversation(threadId: Long, title: String, number: String, group: Boolean = false) = Conversation(
        threadId = threadId, snippet = "", date = threadId.toInt(), read = true, title = title, photoUri = "",
        isGroupConversation = group, phoneNumber = number,
    )

    private val sara = conversation(1, "Sara Ahmadi", "5550100")
    private val mom = conversation(2, "Mom", "+44 712 123 4567")
    private val group = conversation(3, "Friends", "5550100|07121234567", group = true)

    @Test
    fun aNumberFindsTheChatHoweverItIsWritten() {
        assertEquals(listOf(sara), conversationsByNumber(listOf(sara, mom, group), "555"))
        assertEquals(listOf(mom), conversationsByNumber(listOf(sara, mom, group), "712 123"))
        assertEquals(listOf(mom), conversationsByNumber(listOf(sara, mom, group), "۷۱۲۱۲۳"))
    }

    @Test
    fun shortOrWordQueriesDoNotMatchNumbers() {
        assertTrue(conversationsByNumber(listOf(sara, mom), "55").isEmpty())
        assertTrue(conversationsByNumber(listOf(sara, mom), "Sara 555").isEmpty())
    }

    @Test
    fun mergeKeepsTitleHitsFirstAndOneEntryPerThread() {
        assertEquals(listOf(mom, sara), mergeConversationHits(listOf(mom), listOf(sara, mom)))
    }

    @Test
    fun resultsUseTheDisplayForm() {
        val results = buildSearchResults(
            conversations = listOf(conversation(4, "9850001", "9850001")),
            messages = emptyList(),
            formatDate = { "" },
            query = "985",
            displayName = { if (it == "9850001") "985-0001" else it },
        )
        assertEquals("985-0001", results.single().title)
        // N5: an unsaved number is the title already, so it is not repeated under it.
        assertEquals("", results.single().snippet)
    }

    @Test
    fun aNamedChatKeepsItsNumberUnderTheName() {
        val results = buildSearchResults(
            conversations = listOf(sara, conversation(5, "+44 712 123 4567", "+447121234567")),
            messages = emptyList(),
            formatDate = { "" },
            query = "",
            displayName = { if (it == "5550100") "555-0100" else it },
        )
        assertEquals(listOf("555-0100", ""), results.map { it.snippet })
    }

    @Test
    fun aDigitsQueryHighlightsAGroupedNumber() {
        assertEquals(listOf(0..7), highlightRanges("985-0001", "9850001"))
        assertEquals(listOf(4..7), highlightRanges("985-0001", "0001"))
        // Text queries still respect separators.
        assertTrue(highlightRanges("a-b", "ab").isEmpty())
    }
}
