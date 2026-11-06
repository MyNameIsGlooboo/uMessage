package com.glooboo.umessage.ui.main

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.models.Conversation
import com.glooboo.umessage.models.MessageNote
import com.glooboo.umessage.models.SearchResult
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The "Notes" section of the search page: building the hits and drawing them. */
@RunWith(AndroidJUnit4::class)
class SearchNotesTest {

    @get:Rule
    val compose = createComposeRule()

    private fun note(key: String, thread: Long, text: String, date: Int = 1_700_000_000) =
        MessageNote(key, thread, date, text, createdAt = 1, updatedAt = 2)

    private fun chat(id: Long, title: String) = Conversation(
        threadId = id, snippet = "", date = 0, read = true, title = title, photoUri = "p$id",
        isGroupConversation = false, phoneNumber = "555000$id",
    )

    @Test
    fun eachHitCarriesTheChatTitleAndTheMessageDate() {
        val hits = buildNoteHits(
            notes = listOf(note("a", 1, "rent for May"), note("b", 2, "fuel")),
            conversations = mapOf(1L to chat(1, "Dad"), 2L to chat(2, "BankX")),
            query = "rent",
            formatDate = { "d$it" },
        )

        assertThat(hits.map { it.title }).containsExactly("Dad", "BankX").inOrder()
        assertThat(hits.first().text).isEqualTo("rent for May")
        assertThat(hits.first().date).isEqualTo("d1700000000")
        assertThat(hits.first().photoUri).isEqualTo("p1")
    }

    @Test
    fun aNoteWithoutAKnownChatIsLeftOut() {
        val hits = buildNoteHits(
            notes = listOf(note("a", 0, "restored"), note("b", 9, "gone"), note("c", 1, "kept")),
            conversations = mapOf(1L to chat(1, "Dad")),
            query = "e",
            formatDate = { "" },
        )

        assertThat(hits.map { it.key }).containsExactly("c")
    }

    @Test
    fun aLongNoteIsCutAroundTheMatch() {
        val text = "a".repeat(80) + " rent " + "b".repeat(80)
        val hit = buildNoteHits(
            notes = listOf(note("a", 1, text)),
            conversations = mapOf(1L to chat(1, "Dad")),
            query = "rent",
            formatDate = { "" },
        ).single()

        assertThat(hit.text).startsWith("…")
        assertThat(hit.text).contains("rent")
    }

    @Test
    fun aTapOpensTheChatOfTheNote() {
        val hit = NoteHit("k", 7, "Dad", "rent", "Mon", "")

        val open = hit.open()

        assertThat(open.threadId).isEqualTo(7)
        assertThat(open.messageId).isEqualTo(CONVERSATION_HIT)
    }

    @Test
    fun theSectionShowsItsHeadingAndTheNotesAndOpensOnTap() {
        val opened = mutableListOf<SearchResult>()
        val hits = listOf(
            NoteHit("k1", 7, "Dad", "rent for May", "Mon", ""),
            NoteHit("k2", 8, "Mom", "rent", "Tue", ""),
        )
        compose.setContent {
            MaterialTheme {
                LazyColumn {
                    noteSection(resultPage(hits), "rent", onSeeAll = {}, onOpen = { opened += it }, heading = "Notes")
                }
            }
        }

        compose.onNodeWithText("Notes").assertIsDisplayed()
        compose.onNodeWithText("Mom").assertIsDisplayed()
        compose.onNodeWithText("Dad").performClick()

        assertThat(opened.map { it.threadId }).containsExactly(7L)
    }

    @Test
    fun anEmptySectionShowsNothing() {
        compose.setContent {
            MaterialTheme {
                LazyColumn {
                    noteSection(resultPage(emptyList()), "rent", onSeeAll = {}, onOpen = {}, heading = "Notes")
                }
            }
        }

        compose.onNodeWithText("Notes").assertDoesNotExist()
    }
}
