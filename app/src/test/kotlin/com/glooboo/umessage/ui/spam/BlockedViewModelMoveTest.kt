package com.glooboo.umessage.ui.spam

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.spam.BlockedMessage
import com.glooboo.umessage.spam.DecisionReason
import com.glooboo.umessage.spam.RestoreReceipt
import com.glooboo.umessage.spam.SpamRepository
import com.glooboo.umessage.ui.conversations.MarkReadProgress
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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

/** A restore that waits for the test to let it go, and can be made to fail for one message. */
private class GatedRepository(
    private val inner: FakeSpamRepository,
    private val failId: Long? = null,
) : SpamRepository by inner {
    /** Starts closed: every [release] lets one restore through. */
    val gate = Semaphore(permits = GATE_SIZE, acquiredPermits = GATE_SIZE)

    override suspend fun restore(blockedId: Long): RestoreReceipt {
        gate.acquire()
        check(blockedId != failId) { "restore failed" }
        return inner.restore(blockedId)
    }

    private companion object {
        const val GATE_SIZE = 100
    }
}

/** "Not spam" of a big batch: the rows leave at once, progress is shown, and a failed message comes back. */
@RunWith(AndroidJUnit4::class)
class BlockedViewModelMoveTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(Job() + Dispatchers.Main)
    private val events: MutableList<BlockedEvent> = Collections.synchronizedList(mutableListOf())
    private val now = System.currentTimeMillis()
    private val fake = FakeSpamRepository(
        initialBlocked = (1L..TOTAL).map { message(it) },
        initialRules = emptyList(),
    )

    private fun message(id: Long) =
        BlockedMessage(id, NUMBER, "text $id", 1, false, now - id * 1000, DecisionReason.UNKNOWN_SENDER)

    private fun start(failId: Long? = null): Pair<BlockedViewModel, GatedRepository> {
        val repo = GatedRepository(fake, failId)
        val vm = BlockedViewModel(app, repo)
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

        // A slow step is published at once (the throttle waits 100 ms at most).
        Thread.sleep(PUBLISH_WAIT_MS)
        repo.gate.release()
        awaitUntil { vm.state.value.moveProgress?.done == 1 }
        assertThat(vm.state.value.groups).isEmpty()

        repeat(TOTAL.toInt() - 1) { repo.gate.release() }
        awaitUntil { events.any { it is BlockedEvent.Restored } }
        assertThat(vm.state.value.moveProgress).isNull()
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
        const val PUBLISH_WAIT_MS = 150L
        const val AWAIT_MS = 5_000L
    }
}
