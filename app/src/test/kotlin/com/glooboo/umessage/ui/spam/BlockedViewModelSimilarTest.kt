package com.glooboo.umessage.ui.spam

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.spam.BlockedMessage
import com.glooboo.umessage.spam.DecisionReason
import com.glooboo.umessage.spam.ListKind
import com.glooboo.umessage.spam.RuleType
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import java.util.Collections

/** I22: "Also allow messages like this" after Not spam. */
@RunWith(AndroidJUnit4::class)
class BlockedViewModelSimilarTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(Job() + Dispatchers.Main)
    private val events: MutableList<BlockedEvent> = Collections.synchronizedList(mutableListOf())
    private val now = System.currentTimeMillis()
    private val repo = FakeSpamRepository(
        initialBlocked = listOf(
            BlockedMessage(1, "AX-SHOPMART", "Big sale", 1, false, now - 1000, DecisionReason.UNKNOWN_SENDER),
            BlockedMessage(2, "+14155550133", "Hello", 1, false, now - 2000, DecisionReason.UNKNOWN_SENDER),
        ),
        initialRules = emptyList(),
    )
    private lateinit var vm: BlockedViewModel

    @Before
    fun setUp() {
        vm = BlockedViewModel(app, repo)
        scope.launch { vm.state.collect { } }
        scope.launch { vm.eventFlow.collect { events += it } }
        awaitUntil { !vm.state.value.loading }
    }

    @After
    fun tearDown() = scope.cancel()

    private fun allowRules() = runBlocking { repo.rules.first() }.filter { it.kind == ListKind.ALLOW }

    @Test
    fun theSuggestionIsOfferedForOneSenderOnly() {
        vm.notSpam(setOf("AX-SHOPMART"))
        awaitUntil { vm.state.value.allowPrompt != null }
        val prompt = vm.state.value.allowPrompt!!
        assertThat(prompt.similar).isEqualTo(SimilarRuleSuggestion.Suggestion(RuleType.SENDER_ID, "SHOPMART"))
        assertThat(prompt.includeSimilar).isFalse()
    }

    @Test
    fun noSuggestionForAnOrdinaryNumber() {
        vm.notSpam(setOf("+14155550133"))
        awaitUntil { vm.state.value.allowPrompt != null }
        assertThat(vm.state.value.allowPrompt!!.similar).isNull()
    }

    @Test
    fun theBoxIsOffUntilTicked() {
        vm.notSpam(setOf("AX-SHOPMART"))
        awaitUntil { vm.state.value.allowPrompt != null }
        val token = vm.state.value.allowPrompt!!.token
        vm.answerAllow(token, allow = true)
        awaitUntil { events.isNotEmpty() }
        assertThat(allowRules().map { it.pattern }).containsExactly("AX-SHOPMART")
    }

    @Test
    fun tickedBoxAddsBothRulesAndUndoRemovesBoth() {
        vm.notSpam(setOf("AX-SHOPMART"))
        awaitUntil { vm.state.value.allowPrompt != null }
        val token = vm.state.value.allowPrompt!!.token
        vm.setIncludeSimilar(token, true)
        awaitUntil { vm.state.value.allowPrompt?.includeSimilar == true }
        vm.answerAllow(token, allow = true)
        awaitUntil { events.isNotEmpty() }
        assertThat(allowRules().map { it.pattern }).containsExactly("AX-SHOPMART", "SHOPMART")

        vm.undoRestore((events.single() as BlockedEvent.Restored).token)
        awaitUntil { allowRules().isEmpty() }
    }

    @Test
    fun notNowIgnoresATickedBox() {
        vm.notSpam(setOf("AX-SHOPMART"))
        awaitUntil { vm.state.value.allowPrompt != null }
        val token = vm.state.value.allowPrompt!!.token
        vm.setIncludeSimilar(token, true)
        vm.answerAllow(token, allow = false)
        awaitUntil { events.isNotEmpty() }
        assertThat(allowRules()).isEmpty()
    }

    private fun awaitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (true) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            check(System.currentTimeMillis() < deadline) { "condition not met; events=$events" }
            Thread.sleep(10)
        }
    }
}
