package com.glooboo.umessage.ui.spam

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.spam.BlockedMessage
import com.glooboo.umessage.spam.DecisionReason
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

/** Design review 2 on the Blocked tab: L4 double restores, BR-10 restored list, BR-49. */
@RunWith(AndroidJUnit4::class)
class BlockedViewModelReview2Test {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(Job() + Dispatchers.Main)
    private val events: MutableList<BlockedEvent> = Collections.synchronizedList(mutableListOf())
    private val now = System.currentTimeMillis()
    private val repo = FakeSpamRepository(
        initialBlocked = listOf(message(1, LONE), message(2, PAIR), message(3, PAIR)),
        initialRules = emptyList(),
    )
    private val log = RestoredLog(prefs = null)
    private lateinit var vm: BlockedViewModel

    private fun message(id: Long, address: String) = BlockedMessage(
        id, address, "Your code is 4829", 1, false, now - id * 1000, DecisionReason.UNKNOWN_SENDER,
    )

    @Before
    fun setUp() {
        vm = BlockedViewModel(app, repo, log)
        scope.launch { vm.state.collect { } }
        scope.launch { vm.eventFlow.collect { events += it } }
        awaitUntil { !vm.state.value.loading }
    }

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun aDoubleTapOnReleaseOnceQueuesOneRestore() {
        vm.releaseOnce(1)
        vm.releaseOnce(1)
        awaitUntil { events.isNotEmpty() }
        idleMain()
        assertThat(events.filterIsInstance<BlockedEvent.Restored>()).hasSize(1)
    }

    @Test
    fun notSpamDoesNotQueueAMessageAlreadyBeingReleased() {
        vm.releaseOnce(2)
        awaitUntil { events.isNotEmpty() }
        vm.notSpam(setOf(PAIR))
        awaitUntil { events.size == 2 }
        // Only message 3 is left for Not spam; message 2 was already on its way to the inbox.
        assertThat((events.last() as BlockedEvent.Restored).count).isEqualTo(1)
    }

    @Test
    fun releasingASendersLastMessageKeepsAnUndoOpenUntilCommitted() {
        // Owner notes r3: the sender thread stays open while an Undo is pending, then closes.
        val token = run {
            vm.releaseOnce(1)
            awaitUntil { events.isNotEmpty() }
            (events.single() as BlockedEvent.Restored).token
        }
        assertThat(vm.state.value.groups.map { it.address }).doesNotContain(LONE)
        assertThat(vm.state.value.hasPendingUndo).isTrue()
        vm.commitRestore(token)
        awaitUntil { !vm.state.value.hasPendingUndo }
        assertThat(blocked().none { it.address == LONE }).isTrue()
    }

    @Test
    fun restoredMessagesAreListedWithoutTheirText() {
        vm.releaseOnce(1)
        awaitUntil { events.isNotEmpty() }
        vm.commitRestore((events.single() as BlockedEvent.Restored).token)
        awaitUntil { vm.state.value.restored.isNotEmpty() }
        val entry = vm.state.value.restored.single()
        assertThat(entry.address).isEqualTo(LONE)
        assertThat(entry.receivedAt).isEqualTo(now - 1000)
    }

    /** Release once is immediate too, and its Undo takes the entry back off the restored list. */
    @Test
    fun undoOfReleaseOnceBringsTheMessageBackAndUnlistsIt() {
        vm.releaseOnce(1)
        awaitUntil { events.isNotEmpty() }
        awaitUntil { vm.state.value.restored.size == 1 }
        assertThat(blocked().none { it.id == 1L }).isTrue()

        vm.undoRestore((events.single() as BlockedEvent.Restored).token)
        awaitUntil { LONE in vm.state.value.groups.map { it.address } }
        assertThat(blocked().single { it.id == 1L }.body).isEqualTo("Your code is 4829")
        assertThat(vm.state.value.restored).isEmpty()
    }

    @Test
    fun notSpamStoresTheAllowRuleWithTheRestoreAndNamesTheSender() {
        vm.notSpam(setOf(PAIR))
        awaitUntil { events.isNotEmpty() }
        val restored = events.single() as BlockedEvent.Restored
        assertThat(restored.senders).containsExactly(PAIR)
        assertThat(runBlocking { repo.rules.first() }.single().note).isEqualTo(RuleNotes.ALLOWED_BY_YOU)
        vm.commitRestore(restored.token)
        awaitUntil { !vm.state.value.hasPendingUndo }
        // The rule stays, and no second snackbar follows.
        assertThat(runBlocking { repo.rules.first() }).hasSize(1)
        assertThat(events).hasSize(1)
    }

    private fun blocked() = runBlocking { repo.blocked.first() }

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
        const val LONE = "+447121111111"
        const val PAIR = "+447122222222"
    }
}
