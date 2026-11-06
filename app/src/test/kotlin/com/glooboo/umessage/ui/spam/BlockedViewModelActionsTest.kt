package com.glooboo.umessage.ui.spam

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.spam.BlockedMessage
import com.glooboo.umessage.spam.DecisionReason
import com.glooboo.umessage.spam.ListKind
import com.glooboo.umessage.spam.RuleType
import com.glooboo.umessage.spam.SpamRule
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

/** Not spam (T12 / SU-16) and Delete on the Blocked tab and the sender thread. */
@RunWith(AndroidJUnit4::class)
class BlockedViewModelActionsTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(Job() + Dispatchers.Main)
    private val events: MutableList<BlockedEvent> = Collections.synchronizedList(mutableListOf())
    private val now = System.currentTimeMillis()
    private val repo = FakeSpamRepository(
        initialBlocked = listOf(
            message(1, NUMBER), message(2, NUMBER), message(3, SENDER_ID),
        ),
        initialRules = listOf(SpamRule(7, ListKind.BLOCK, RuleType.KEYWORD, "free")),
    )
    private lateinit var vm: BlockedViewModel

    private fun message(id: Long, address: String) = BlockedMessage(
        id, address, "Free prize", 1, false, now - id * 1000, DecisionReason.UNKNOWN_SENDER,
    )

    @Before
    fun setUp() {
        vm = BlockedViewModel(app, repo)
        scope.launch { vm.state.collect { } }
        scope.launch { vm.eventFlow.collect { events += it } }
        awaitUntil { !vm.state.value.loading }
    }

    @After
    fun tearDown() = scope.cancel()

    private fun rules() = runBlocking { repo.rules.first() }
    private fun blocked() = runBlocking { repo.blocked.first() }

    @Test
    fun notSpamAsksBeforeAllowingAndNotNowAddsNoRule() {
        vm.notSpam(setOf(NUMBER))
        awaitUntil { vm.state.value.allowPrompt != null }
        val prompt = vm.state.value.allowPrompt!!
        assertThat(prompt.addresses).containsExactly(NUMBER)
        assertThat(prompt.spoofable).isEmpty()
        // Round 4: the prompt knows both messages went back, so it says "The messages go back".
        assertThat(prompt.messageCount).isEqualTo(2)
        assertThat(vm.state.value.groups.map { it.address }).doesNotContain(NUMBER)
        assertThat(events).isEmpty()

        vm.answerAllow(prompt.token, allow = false)
        awaitUntil { events.isNotEmpty() }
        val restored = events.single() as BlockedEvent.Restored
        assertThat(restored.count).isEqualTo(2)
        assertThat(restored.allowed).isEqualTo(0)

        vm.commitRestore(restored.token)
        awaitUntil { blocked().none { it.address == NUMBER } }
        assertThat(rules().filter { it.kind == ListKind.ALLOW }).isEmpty()
    }

    /** Yes stores the rule at once (closing the app keeps it); the "View rule" snackbar waits for the window. */
    @Test
    fun alwaysAllowStoresTheRuleAtOnce() {
        vm.notSpam(setOf(NUMBER))
        awaitUntil { vm.state.value.allowPrompt != null }
        vm.answerAllow(vm.state.value.allowPrompt!!.token, allow = true)
        awaitUntil { events.isNotEmpty() }
        val restored = events.single() as BlockedEvent.Restored
        assertThat(restored.allowed).isEqualTo(1)
        assertThat(rules().single { it.kind == ListKind.ALLOW }.pattern).isEqualTo(NUMBER)

        vm.commitRestore(restored.token)
        awaitUntil { events.any { it is BlockedEvent.AllowRulesAdded } }
        assertThat(rules().filter { it.kind == ListKind.ALLOW }).hasSize(1)
    }

    /** Never answering the prompt still restores the messages, and adds no rule. */
    @Test
    fun anUnansweredPromptRestoresWithoutAllowing() {
        vm.notSpam(setOf(NUMBER))
        awaitUntil { vm.state.value.allowPrompt != null }
        assertThat(blocked().none { it.address == NUMBER }).isTrue()
        assertThat(rules().filter { it.kind == ListKind.ALLOW }).isEmpty()
    }

    @Test
    fun undoAfterAlwaysAllowLeavesEverythingAsItWas() {
        vm.notSpam(setOf(NUMBER))
        awaitUntil { vm.state.value.allowPrompt != null }
        vm.answerAllow(vm.state.value.allowPrompt!!.token, allow = true)
        awaitUntil { events.isNotEmpty() }
        vm.undoRestore((events.single() as BlockedEvent.Restored).token)
        awaitUntil { NUMBER in vm.state.value.groups.map { it.address } }
        assertThat(blocked()).hasSize(3)
        assertThat(rules().filter { it.kind == ListKind.ALLOW }).isEmpty()
    }

    @Test
    fun senderIdIsFlaggedAsSpoofableInThePrompt() {
        vm.notSpam(setOf(NUMBER, SENDER_ID))
        awaitUntil { vm.state.value.allowPrompt != null }
        assertThat(vm.state.value.allowPrompt!!.spoofable).containsExactly(SENDER_ID)
    }

    @Test
    fun deletingOneMessageHidesOnlyItAndCanBeUndone() {
        // The sender thread's per-message Delete (owner notes r3).
        vm.deleteMessages(setOf(1L))
        awaitUntil { events.isNotEmpty() }
        val event = events.single() as BlockedEvent.Deleted
        assertThat(event.count).isEqualTo(1)
        assertThat(vm.state.value.groups.single { it.address == NUMBER }.messages.map { it.id }).containsExactly(2L)
        vm.undoDelete(event.token)
        awaitUntil { vm.state.value.groups.single { it.address == NUMBER }.messages.size == 2 }
        assertThat(blocked()).hasSize(3)
    }

    @Test
    fun committedDeleteOfOneMessageRemovesItForGood() {
        vm.deleteMessages(setOf(1L))
        awaitUntil { events.isNotEmpty() }
        vm.commitDelete((events.single() as BlockedEvent.Deleted).token)
        awaitUntil { blocked().none { it.id == 1L } }
        assertThat(blocked()).hasSize(2)
    }

    @Test
    fun stateCarriesRulesAndAutoDeleteDays() {
        assertThat(vm.state.value.rules.keys).containsExactly(7L)
        assertThat(vm.state.value.purgeAfterDays).isEqualTo(30)
    }

    private fun idleMain() = shadowOf(Looper.getMainLooper()).idle()

    private fun awaitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (true) {
            idleMain()
            if (condition()) return
            check(System.currentTimeMillis() < deadline) { "condition not met; events=$events" }
            Thread.sleep(10)
        }
    }

    private companion object {
        const val NUMBER = "+14155550133"
        const val SENDER_ID = "BANK-X"
    }
}
