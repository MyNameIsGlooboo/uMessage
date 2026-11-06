package com.glooboo.umessage.ui.spam

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.R
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

    private val done: MutableList<NotSpamDone> = Collections.synchronizedList(mutableListOf())

    private fun allowRules() = rules().filter { it.kind == ListKind.ALLOW }

    private fun snack(event: BlockedEvent.Restored) = restoredSnackText(app.resources, event)

    /** Not spam is one step: no question, the messages go to the inbox and the sender is allowed. */
    @Test
    fun notSpamRestoresAndAllowsTheNumberInOneStep() {
        vm.notSpam(setOf(NUMBER))
        awaitUntil { events.isNotEmpty() }
        val restored = events.single() as BlockedEvent.Restored
        assertThat(restored.count).isEqualTo(2)
        assertThat(restored.allowed).isEqualTo(1)
        assertThat(restored.senders).containsExactly(NUMBER)
        assertThat(blocked().none { it.address == NUMBER }).isTrue()
        assertThat(allowRules().single().pattern).isEqualTo(NUMBER)
        assertThat(allowRules().single().type).isEqualTo(RuleType.NUMBER)
        assertThat(snack(restored)).isEqualTo(app.getString(R.string.nsf_snack_moved_allowed, NUMBER))

        vm.commitRestore(restored.token)
        awaitUntil { !vm.state.value.hasPendingUndo }
        assertThat(allowRules()).hasSize(1)
        assertThat(events).hasSize(1)
    }

    /** A name anyone can send under is never allowed: the messages move and no rule is made. */
    @Test
    fun aSpoofableSenderOnlyMoves() {
        vm.notSpam(setOf(SENDER_ID))
        awaitUntil { events.isNotEmpty() }
        val restored = events.single() as BlockedEvent.Restored
        assertThat(restored.count).isEqualTo(1)
        assertThat(restored.allowed).isEqualTo(0)
        assertThat(restored.senders).isEmpty()
        assertThat(snack(restored)).isEqualTo(app.getString(R.string.nsf_snack_moved))
        assertThat(allowRules()).isEmpty()
        assertThat(blocked().none { it.address == SENDER_ID }).isTrue()

        vm.undoRestore(restored.token)
        awaitUntil { SENDER_ID in vm.state.value.groups.map { it.address } }
        assertThat(allowRules()).isEmpty()
    }

    @Test
    fun aMixedSelectionAllowsOnlyTheNumber() {
        vm.notSpam(setOf(NUMBER, SENDER_ID))
        awaitUntil { events.isNotEmpty() }
        val restored = events.single() as BlockedEvent.Restored
        assertThat(restored.count).isEqualTo(3)
        assertThat(restored.senders).containsExactly(NUMBER)
        assertThat(allowRules().map { it.pattern }).containsExactly(NUMBER)
    }

    @Test
    fun oneUndoTakesBackTheMessagesAndTheRule() {
        vm.notSpam(setOf(NUMBER))
        awaitUntil { events.isNotEmpty() }
        vm.undoRestore((events.single() as BlockedEvent.Restored).token)
        awaitUntil { NUMBER in vm.state.value.groups.map { it.address } }
        assertThat(blocked()).hasSize(3)
        assertThat(allowRules()).isEmpty()
        // The block rule that was there before is untouched.
        assertThat(rules().map { it.id }).contains(7L)
    }

    @Test
    fun aDoubleTapDoesNotRestoreTwice() {
        assertThat(vm.notSpam(setOf(NUMBER))).isTrue()
        assertThat(vm.notSpam(setOf(NUMBER))).isFalse()
        awaitUntil { events.isNotEmpty() }
        idleMain()
        assertThat(events.filterIsInstance<BlockedEvent.Restored>()).hasSize(1)
        assertThat((events.single() as BlockedEvent.Restored).count).isEqualTo(2)
        assertThat(allowRules()).hasSize(1)
    }

    /** From a blocked chat: no snackbar here, the screen is told what reached the inbox and opens the chat. */
    @Test
    fun fromABlockedChatTheScreenIsToldAndNoSnackbarShows() {
        scope.launch { vm.notSpamDone.collect { done += it } }
        assertThat(vm.notSpam(setOf(NUMBER), openChat = true)).isTrue()
        awaitUntil { done.isNotEmpty() }
        assertThat(done.single().toInbox).isEqualTo(2)
        assertThat(done.single().allowed).containsExactly(NUMBER)
        assertThat(allowRules()).hasSize(1)
        assertThat(blocked().none { it.address == NUMBER }).isTrue()
        awaitUntil { !vm.state.value.hasPendingUndo }
        assertThat(events).isEmpty()
        // A second tap while the first runs does nothing.
        assertThat(vm.notSpam(setOf(NUMBER), openChat = true)).isFalse()
    }

    @Test
    fun aSecondTapFromABlockedChatBeforeItFinishesIsIgnored() {
        assertThat(vm.notSpam(setOf(NUMBER), openChat = true)).isTrue()
        assertThat(vm.notSpam(setOf(NUMBER), openChat = true)).isFalse()
        awaitUntil { !vm.state.value.hasPendingUndo }
        assertThat(allowRules()).hasSize(1)
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
