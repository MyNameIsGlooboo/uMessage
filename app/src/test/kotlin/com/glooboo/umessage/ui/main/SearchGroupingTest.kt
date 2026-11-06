package com.glooboo.umessage.ui.main

import com.glooboo.umessage.helpers.MAX_RECENT_SEARCHES
import com.glooboo.umessage.helpers.pushRecentSearch
import com.glooboo.umessage.models.SearchResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchGroupingTest {

    private fun hit(messageId: Long, threadId: Long, snippet: String = "s") = SearchResult(
        messageId = messageId, title = "t$threadId", snippet = snippet, date = "d", threadId = threadId, photoUri = "",
    )

    @Test
    fun `hits of one thread merge into the first with a count`() {
        val items = groupSearchResults(listOf(hit(10, 1, "first"), hit(11, 2), hit(12, 1, "second"), hit(13, 1)))
        val hits = items.filterIsInstance<SearchListItem.Hit>()
        assertEquals(listOf(1L, 2L), hits.map { it.result.threadId })
        assertEquals(listOf(3, 1), hits.map { it.count })
        assertEquals("first", hits[0].result.snippet)
    }

    @Test
    fun `conversations and messages get their own headers`() {
        val items = groupSearchResults(listOf(hit(-1, 7), hit(5, 7), hit(6, 8)))
        assertEquals(
            listOf(
                SearchListItem.Header(conversations = true),
                SearchListItem.Hit(hit(-1, 7), 1),
                SearchListItem.Header(conversations = false),
                SearchListItem.Hit(hit(5, 7), 1),
                SearchListItem.Hit(hit(6, 8), 1),
            ),
            items,
        )
        assertEquals(items.size, items.map { it.key }.toSet().size)
    }

    @Test
    fun `a section without hits has no header`() {
        assertTrue(groupSearchResults(emptyList()).isEmpty())
        val onlyMessages = groupSearchResults(listOf(hit(1, 1)))
        assertEquals(SearchListItem.Header(conversations = false), onlyMessages.first())
        assertEquals(2, onlyMessages.size)
    }

    @Test
    fun `recent searches keep the newest first without duplicates`() {
        assertEquals(listOf("code", "bank"), pushRecentSearch(listOf("bank", "code"), "Code".lowercase()))
        assertEquals(listOf("Code", "bank"), pushRecentSearch(listOf("bank", "code"), "  Code "))
        assertEquals(listOf("bank"), pushRecentSearch(listOf("bank"), "   "))
    }

    @Test
    fun `recent searches are capped`() {
        var recent = emptyList<String>()
        for (i in 1..(MAX_RECENT_SEARCHES + 3)) recent = pushRecentSearch(recent, "q$i")
        assertEquals(MAX_RECENT_SEARCHES, recent.size)
        assertEquals("q${MAX_RECENT_SEARCHES + 3}", recent.first())
    }
}
