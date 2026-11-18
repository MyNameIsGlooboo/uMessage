package com.glooboo.umessage.ui.spam

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.spam.BlockedMessage
import com.glooboo.umessage.spam.DecisionReason
import com.glooboo.umessage.spam.RestoreReceipt
import com.glooboo.umessage.spam.SpamRepository
import com.glooboo.umessage.ui.conversations.MarkReadProgress
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/** A restore that waits for the test to let it go, and can be made to fail for one message. */
private class GatedRepository(
    private val inner: FakeSpamRepository,
    private val failId: Long? = null,
    private val failAll: Boolean = false,
) : SpamRepository by inner {
    /** Starts closed: every [release] lets one restore through. */
    val gate = Semaphore(permits = GATE_SIZE, acquiredPermits = GATE_SIZE)

    /** The ids the loop asked to restore, in order, including one still waiting at the gate. */
    val restoreOrder: MutableList<Long> = Collections.synchronizedList(mutableListOf())
    val restoreCalls = AtomicInteger()

    override suspend fun restore(blockedId: Long): RestoreReceipt {
        restoreCalls.incrementAndGet()
        restoreOrder += blockedId
        gate.acquire()
        check(!failAll && blockedId != failId) { "restore failed" }
        return inner.restore(blockedId)
    }

    private companion object {
        const val GATE_SIZE = 100
    }
}

/**
 * "Not spam": the rows leave the list at once, the shared move state counts up whatever the batch size, the messages
 * move oldest first, and a failed message comes back.
 */
@RunWith(AndroidJUnit4::class)
class BlockedViewModelMoveTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(Job() + Dispatchers.Main)
    private val events: MutableList<BlockedEvent> = Collections.synchronizedList(mutableListOf())
    private val now = System.currentTimeMillis()
    private val moves = NotSpamMoves()
    private val fake = FakeSpamRepository(
        initialBlocked = (1L..TOTAL).map { message(it) },
        initialRules = emptyList(),
    )

    private fun message(id: Long) =
        BlockedMessage(id, NUMBER, "text $id", 1, false, now - id * 1000, DecisionReason.UNKNOWN_SENDER)

    private fun start(failId: Long? = null): Pair<BlockedViewModel, GatedRepository> {
        val repo = GatedRepository(fake, failId)
        val vm = BlockedViewModel(app, repo, moves = moves)
        scope.launch { vm.state.collect { } }
        scope.launch { vm.eventFlow.collect { events += it } }
        awaitUntil { !vm.state.value.loading }
        return vm to repo
    }

    @After
    fun tearDown() = scope.cancel()

    private fun blocked() = runBlocking { fake.blocked.first() }

    @Test
    fun allRowsLeaveAtOnceAndProgressCountsUp() {
        val (vm, repo) = start()
        assertThat(vm.notSpam(setOf(NUMBER))).isTrue()
        awaitUntil { vm.state.value.groups.isEmpty() }
        // Nothing was restored yet, but every row is already gone from the list.
        assertThat(blocked()).hasSize(TOTAL.toInt())
        awaitUntil { vm.state.value.moveProgress == MarkReadProgress(0, TOTAL.toInt()) }

        // Every step shows at once: the 300 ms rule lives in the composable, not in the state.
        repo.gate.release()
        awaitUntil { vm.state.value.moveProgress?.done == 1 }
        assertThat(vm.state.value.groups).isEmpty()

        repeat(TOTAL.toInt() - 1) { repo.gate.release() }
        awaitUntil { events.any { it is BlockedEvent.Restored } }
        awaitUntil { vm.state.value.moveProgress == null }
        assertThat(blocked()).isEmpty()
        assertThat((events.single() as BlockedEvent.Restored).count).isEqualTo(TOTAL.toInt())
    }

    @Test
    fun aSecondCallDuringTheMoveIsIgnored() {
        val (vm, repo) = start()
        assertThat(vm.notSpam(setOf(NUMBER))).isTrue()
        assertThat(vm.notSpam(setOf(NUMBER))).isFalse()
        assertThat(vm.notSpam(setOf(NUMBER), openChat = true)).isFalse()
        repeat(TOTAL.toInt()) { repo.gate.release() }
        awaitUntil { events.any { it is BlockedEvent.Restored } }
        idleMain()
        assertThat(events.filterIsInstance<BlockedEvent.Restored>()).hasSize(1)
    }

    @Test
    fun aMessageThatFailsToMoveShowsItsRowAgain() {
        val (vm, repo) = start(failId = FAILING_ID)
        vm.notSpam(setOf(NUMBER))
        awaitUntil { vm.state.value.groups.isEmpty() }
        repeat(TOTAL.toInt()) { repo.gate.release() }
        awaitUntil { events.any { it is BlockedEvent.Restored } }
        awaitUntil { vm.state.value.groups.singleOrNull()?.messages?.map { it.id } == listOf(FAILING_ID) }
        assertThat(vm.state.value.moveProgress).isNull()
        assertThat(events.filterIsInstance<BlockedEvent.Failed>()).isNotEmpty()
        assertThat(blocked().map { it.id }).containsExactly(FAILING_ID)
    }

    @Test
    fun aBlockedChatMoveHidesRowsAndBringsThemBackWhenEveryRestoreFails() {
        val repo = GatedRepository(fake, failAll = true)
        val vm = BlockedViewModel(app, repo, moves = moves)
        val done = Collections.synchronizedList(mutableListOf<NotSpamDone>())
        scope.launch { vm.state.collect { } }
        scope.launch { vm.eventFlow.collect { events += it } }
        scope.launch { vm.notSpamDone.collect { done += it } }
        awaitUntil { !vm.state.value.loading }
        assertThat(vm.notSpam(setOf(NUMBER), openChat = true)).isTrue()
        awaitUntil { vm.state.value.groups.isEmpty() }
        assertThat(blocked()).hasSize(TOTAL.toInt())
        repeat(TOTAL.toInt()) { repo.gate.release() }
        awaitUntil { done.isNotEmpty() }
        assertThat(done.single().toInbox).isEqualTo(0)
        awaitUntil { events.any { it is BlockedEvent.Failed } }
        awaitUntil { vm.state.value.groups.singleOrNull()?.messages?.size == TOTAL.toInt() }
        assertThat(events.filterIsInstance<BlockedEvent.Failed>()).isNotEmpty()
        awaitUntil { vm.state.value.moveProgress == null }
        // The chat's buttons work again (onRetry) because nothing reached the inbox.
        assertThat(done.single().allowed).isEmpty()
    }

    @Test
    fun theListHidesWhatAnotherModelMoves() {
        val (vm, _) = start()
        assertThat(vm.state.value.groups.single().messages).hasSize(TOTAL.toInt())
        moves.begin(blocked())
        awaitUntil { vm.state.value.groups.isEmpty() }
        assertThat(vm.state.value.moveProgress).isEqualTo(MarkReadProgress(0, TOTAL.toInt()))
        moves.end(blocked().map { it.id })
        awaitUntil { vm.state.value.groups.isNotEmpty() }
        assertThat(vm.state.value.moveProgress).isNull()
    }

    @Test
    fun theSenderPageKeepsWhatIsMovingAndListsItAsMovingGroups() {
        val thread = BlockedViewModel(app, GatedRepository(fake), ignoreFilter = true, moves = moves)
        scope.launch { thread.state.collect { } }
        awaitUntil { !thread.state.value.loading }
        moves.begin(blocked())
        awaitUntil { thread.state.value.movingGroups.isNotEmpty() }
        val state = thread.state.value
        assertThat(state.groups.single().messages).hasSize(TOTAL.toInt())
        assertThat(state.movingGroups.single().messages.map { it.id })
            .containsExactlyElementsIn(blocked().map { it.id })
        assertThat(state.moveStartedAt).isGreaterThan(0L)
    }

    @Test
    fun aSecondModelCannotQueueMessagesAnotherOneIsMoving() {
        // The sender page's model does not hide what moves, so only the shared state can stop it.
        val repo = GatedRepository(fake)
        val thread = BlockedViewModel(app, repo, ignoreFilter = true, moves = moves)
        scope.launch { thread.state.collect { } }
        awaitUntil { thread.state.value.groups.isNotEmpty() }
        moves.begin(blocked())
        assertThat(thread.notSpam(setOf(NUMBER))).isFalse()
        thread.releaseMessages(setOf(1L, 2L))
        idleMain()
        assertThat(repo.restoreCalls.get()).isEqualTo(0)
    }

    @Test
    fun theProgressFollowsTheSharedStateEvenForAThreeMessageBatch() {
        val (vm, _) = start()
        val three = blocked().take(THREE)
        moves.begin(three)
        awaitUntil { vm.state.value.moveProgress == MarkReadProgress(0, THREE) }
        moves.step()
        awaitUntil { vm.state.value.moveProgress == MarkReadProgress(1, THREE) }
        moves.end(three.map { it.id })
        awaitUntil { vm.state.value.moveProgress == null }
    }

    @Test
    fun aModelClearedMidMoveAnnouncesTheInboxCount() {
        val repo = GatedRepository(fake)
        val store = ViewModelStore()
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                BlockedViewModel(app, repo, moves = moves) as T
        }
        val vm = ViewModelProvider(store, factory)[BlockedViewModel::class.java]
        val heard = Collections.synchronizedList(mutableListOf<Int>())
        scope.launch(start = CoroutineStart.UNDISPATCHED) { moves.orphanDone.collect { heard += it } }
        scope.launch { vm.state.collect { } }
        awaitUntil { !vm.state.value.loading }
        assertThat(vm.notSpam(setOf(NUMBER))).isTrue()
        awaitUntil { moves.state.value.running }
        store.clear()
        repeat(TOTAL.toInt()) { repo.gate.release() }
        awaitUntil { heard.isNotEmpty() }
        assertThat(heard.single()).isEqualTo(TOTAL.toInt())
        awaitUntil { !moves.state.value.running }
    }

    @Test
    fun messagesMoveOldestFirstAndTiesGoByIdAndUndoBringsAllBack() {
        val messages = listOf(5L, 3L, 4L).map { id ->
            BlockedMessage(id, NUMBER, "text $id", 1, false, SAME_SECOND, DecisionReason.UNKNOWN_SENDER)
        } + BlockedMessage(OLD_ID, NUMBER, "old", 1, false, SAME_SECOND - OLD_BY_MS, DecisionReason.UNKNOWN_SENDER)
        val repo = GatedRepository(FakeSpamRepository(initialBlocked = messages, initialRules = emptyList()))
        val vm = BlockedViewModel(app, repo, moves = moves)
        scope.launch { vm.state.collect { } }
        scope.launch { vm.eventFlow.collect { events += it } }
        awaitUntil { !vm.state.value.loading }
        assertThat(vm.notSpam(setOf(NUMBER))).isTrue()
        repeat(messages.size) { repo.gate.release() }
        awaitUntil { events.any { it is BlockedEvent.Restored } }
        assertThat(repo.restoreOrder).containsExactly(OLD_ID, 3L, 4L, 5L).inOrder()

        vm.undoRestore((events.single { it is BlockedEvent.Restored } as BlockedEvent.Restored).token)
        awaitUntil { vm.state.value.groups.singleOrNull()?.messages?.size == messages.size }
    }

    private fun idleMain() = shadowOf(Looper.getMainLooper()).idle()

    private fun awaitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + AWAIT_MS
        while (true) {
            idleMain()
            if (condition()) return
            check(System.currentTimeMillis() < deadline) { "condition not met; events=$events" }
            Thread.sleep(10)
        }
    }

    private companion object {
        const val NUMBER = "+15550001234"
        const val TOTAL = 12L
        const val FAILING_ID = 5L
        const val THREE = 3
        const val OLD_BY_MS = 5_000L
        const val OLD_ID = 9L
        const val SAME_SECOND = 1_700_000_000_000L
        const val AWAIT_MS = 5_000L
    }
}
