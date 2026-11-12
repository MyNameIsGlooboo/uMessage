package com.glooboo.umessage.ui.main

import android.app.Application
import androidx.core.os.bundleOf
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.models.SearchResult
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The main screen's search outside the screen: debounce, short queries and restoring after the process is killed. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class MainSearchViewModelTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val dispatcher = StandardTestDispatcher()
    private val searched = mutableListOf<String>()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun result(query: String) = SearchResult(
        messageId = 1, title = query, snippet = "", date = "", threadId = 1, photoUri = "",
    )

    private fun viewModel(handle: SavedStateHandle = SavedStateHandle()) = MainSearchViewModel(app, handle).apply {
        runSearch = { query ->
            searched += query
            listOf(result(query))
        }
        runNoteSearch = { emptyList() }
        runBlockedSearch = { emptyList() }
    }

    /** main-22: blocked messages are searched with the notes and cleared with them. */
    @Test
    fun blockedMessagesAreFoundWithTheMessagesAndClearedWithThem() = runTest(dispatcher) {
        val vm = viewModel().apply {
            runBlockedSearch = { query ->
                listOf(BlockedHit("+14155550133", "+1 415 555 0133", "free $query", "Mon", 2))
            }
        }
        vm.onQueryChange("prize")
        advanceUntilIdle()
        assertThat(vm.state.blockedHits.map { it.snippet }).containsExactly("free prize")

        vm.onQueryChange("p")
        assertThat(vm.state.blockedHits).isEmpty()
    }

    @Test
    fun typingRunsOneSearchAfterThePause() = runTest(dispatcher) {
        val vm = viewModel()
        vm.onQueryChange("he")
        vm.onQueryChange("hel")
        vm.onQueryChange("hell")
        advanceTimeBy(SEARCH_DEBOUNCE_MS - 1)
        assertThat(searched).isEmpty()

        advanceUntilIdle()
        assertThat(searched).containsExactly("hell")
        assertThat(vm.state.query).isEqualTo("hell")
        assertThat(vm.state.results.map { it.title }).containsExactly("hell")
    }

    @Test
    fun matchingNotesAreFoundWithTheMessagesAndClearedWithThem() = runTest(dispatcher) {
        val vm = viewModel().apply {
            runNoteSearch = { query ->
                listOf(NoteHit("k1", 7, "Dad", "note about $query", "Mon", ""))
            }
        }
        vm.onQueryChange("rent")
        advanceUntilIdle()
        assertThat(vm.state.noteHits.map { it.text }).containsExactly("note about rent")
        assertThat(vm.state.noteHits.single().open().threadId).isEqualTo(7)

        vm.onQueryChange("r")
        assertThat(vm.state.noteHits).isEmpty()
    }

    @Test
    fun aShortQueryClearsResultsAndCancelsThePendingSearch() = runTest(dispatcher) {
        val vm = viewModel()
        vm.onQueryChange("ab")
        advanceUntilIdle()
        assertThat(vm.state.results).isNotEmpty()

        vm.onQueryChange("abc")
        vm.onQueryChange("a")
        assertThat(vm.state.results).isEmpty()
        advanceUntilIdle()
        assertThat(searched).containsExactly("ab")
        assertThat(vm.state.results).isEmpty()
    }

    @Test
    fun anOpenSearchComesBackAfterTheProcessIsKilled() = runTest(dispatcher) {
        val saved = SavedStateHandle(
            mapOf(
                MainSearchViewModel.KEY_SAVED to bundleOf(
                    MainSearchViewModel.KEY_ACTIVE to true,
                    MainSearchViewModel.KEY_QUERY to "bank",
                ),
            ),
        )
        val vm = viewModel(saved)
        assertThat(vm.state.active).isTrue()
        assertThat(vm.state.query).isEqualTo("bank")
        advanceUntilIdle()
        assertThat(searched).containsExactly("bank")
    }

    @Test
    fun aClosedSearchIsNotRestored() = runTest(dispatcher) {
        val saved = SavedStateHandle(
            mapOf(MainSearchViewModel.KEY_SAVED to bundleOf(MainSearchViewModel.KEY_ACTIVE to false)),
        )
        val vm = viewModel(saved)
        advanceUntilIdle()
        assertThat(vm.state.active).isFalse()
        assertThat(searched).isEmpty()
    }
}
