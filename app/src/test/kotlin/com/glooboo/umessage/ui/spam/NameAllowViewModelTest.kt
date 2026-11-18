package com.glooboo.umessage.ui.spam

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.spam.BlockedMessage
import com.glooboo.umessage.spam.DecisionReason
import com.glooboo.umessage.spam.ListKind
import com.glooboo.umessage.spam.RestoreOutcome
import com.glooboo.umessage.spam.RestoreReceipt
import com.glooboo.umessage.spam.RuleType
import com.glooboo.umessage.spam.SpamRepository
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
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import java.util.Collections

/** The "Always allow this name?" question after a "Not spam" of senders that go by a name. */
@RunWith(AndroidJUnit4::class)
class NameAllowViewModelTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(Job() + Dispatchers.Main)
    private val events: MutableList<BlockedEvent> = Collections.synchronizedList(mutableListOf())
    private val now = System.currentTimeMillis()

    /** A repository whose restore never moves anything. */
    private class FailingRestore(private val base: FakeSpamRepository) : SpamRepository by base {
        val calls = java.util.concurrent.atomic.AtomicInteger()

        override suspend fun restore(blockedId: Long): RestoreReceipt {
            calls.incrementAndGet()
            return RestoreReceipt(null, RestoreOutcome.FAILED)
        }
    }

    private var failing: FailingRestore? = null

    private fun message(id: Long, address: String) = BlockedMessage(
        id, address, "Hello", 1, false, now - id * 1000, DecisionReason.UNKNOWN_SENDER,
    )

    private fun start(
        rules: List<SpamRule> = emptyList(),
        failRestore: Boolean = false,
    ): Pair<BlockedViewModel, FakeSpamRepository> {
        val fake = FakeSpamRepository(
            initialBlocked = listOf(message(1, NAME), message(2, NAME), message(3, NUMBER), message(4, OTHER_NAME)),
            initialRules = rules,
        )
        val repo: SpamRepository = if (failRestore) FailingRestore(fake).also { failing = it } else fake
        val vm = BlockedViewModel(app, repo, RestoredLog(prefs = null), moves = NotSpamMoves())
        scope.launch { vm.state.collect { } }
        scope.launch { vm.eventFlow.collect { events += it } }
        awaitUntil { !vm.state.value.loading }
        return vm to fake
    }

    @After
    fun tearDown() = scope.cancel()

    private fun rulesOf(repo: FakeSpamRepository) = runBlocking { repo.rules.first() }
    private fun allowRules(repo: FakeSpamRepository) = rulesOf(repo).filter { it.kind == ListKind.ALLOW }

    @Test
    fun aMovedNameIsAskedAboutOnceAndNoRuleIsMadeYet() {
        val (vm, repo) = start()
        vm.notSpam(setOf(NAME))
        awaitUntil { vm.namePrompt.value != null }
        awaitUntil { events.isNotEmpty() }
        val prompt = vm.namePrompt.value!!
        assertThat(prompt.names).containsExactly(NAME)
        assertThat(prompt.token).isEqualTo((events.single() as BlockedEvent.Restored).token)
        assertThat(allowRules(repo)).isEmpty()
    }

    @Test
    fun severalMovedNamesAreAskedAboutTogether() {
        val (vm, _) = start()
        vm.notSpam(setOf(NAME, OTHER_NAME, NUMBER))
        awaitUntil { vm.namePrompt.value != null }
        assertThat(vm.namePrompt.value!!.names).containsExactly(NAME, OTHER_NAME)
    }

    @Test
    fun aNumberSenderIsNeverAskedAbout() {
        val (vm, repo) = start()
        vm.notSpam(setOf(NUMBER))
        awaitUntil { events.isNotEmpty() }
        idleMain()
        assertThat(vm.namePrompt.value).isNull()
        assertThat(allowRules(repo).map { it.type }).containsExactly(RuleType.NUMBER)
    }

    @Test
    fun noQuestionWhenAnAllowRuleNamesItAlready() {
        val rule = SpamRule(5, ListKind.ALLOW, RuleType.SENDER_ID, NAME.lowercase())
        val (vm, _) = start(rules = listOf(rule))
        vm.notSpam(setOf(NAME))
        awaitUntil { events.isNotEmpty() }
        idleMain()
        assertThat(vm.namePrompt.value).isNull()
    }

    @Test
    fun noQuestionWhenNothingWasMoved() {
        val (vm, _) = start(failRestore = true)
        vm.notSpam(setOf(NAME))
        // Both of the sender's messages were tried and failed, and the move is over.
        awaitUntil { failing!!.calls.get() >= 2 }
        awaitUntil { vm.state.value.moveProgress == null }
        idleMain()
        assertThat(vm.namePrompt.value).isNull()
    }

    @Test
    fun releasingOneMessageDoesNotAsk() {
        val (vm, _) = start()
        vm.releaseOnce(1L)
        awaitUntil { events.isNotEmpty() }
        idleMain()
        assertThat(vm.namePrompt.value).isNull()
    }

    @Test
    fun alwaysAllowMakesOneSenderNameRuleAndUndoRemovesIt() {
        val (vm, repo) = start()
        vm.notSpam(setOf(NAME))
        awaitUntil { vm.namePrompt.value != null }
        awaitUntil { events.isNotEmpty() }

        vm.allowPromptedNames()
        assertThat(vm.namePrompt.value).isNull()
        awaitUntil { events.size == 2 }
        val rule = allowRules(repo).single()
        assertThat(rule.type).isEqualTo(RuleType.SENDER_ID)
        assertThat(rule.pattern).isEqualTo(NAME)
        val allowed = events.last() as BlockedEvent.Restored
        assertThat(allowed.count).isEqualTo(0)
        assertThat(allowed.allowed).isEqualTo(1)

        vm.undoRestore(allowed.token)
        awaitUntil { allowRules(repo).isEmpty() }
    }

    @Test
    fun notNowChangesNothingAndTheNextNotSpamAsksAgain() {
        val (vm, repo) = start()
        vm.notSpam(setOf(NAME))
        awaitUntil { vm.namePrompt.value != null }
        awaitUntil { events.isNotEmpty() }
        vm.dismissNamePrompt()
        assertThat(vm.namePrompt.value).isNull()
        assertThat(allowRules(repo)).isEmpty()

        vm.undoRestore((events.single() as BlockedEvent.Restored).token)
        awaitUntil { NAME in vm.state.value.groups.map { it.address } }
        vm.notSpam(setOf(NAME))
        awaitUntil { vm.namePrompt.value != null }
        assertThat(vm.namePrompt.value!!.names).containsExactly(NAME)
    }

    @Test
    fun undoingTheMoveTakesTheQuestionBack() {
        val (vm, _) = start()
        vm.notSpam(setOf(NAME))
        awaitUntil { vm.namePrompt.value != null }
        awaitUntil { events.isNotEmpty() }
        vm.undoRestore((events.single() as BlockedEvent.Restored).token)
        awaitUntil { vm.namePrompt.value == null }
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
        const val NAME = "BANK-X"
        const val OTHER_NAME = "b.pasargad"
        const val NUMBER = "+15550001234"
    }
}
