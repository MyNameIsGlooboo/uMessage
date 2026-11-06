package com.glooboo.umessage.ui.spam

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import java.util.Collections

/** Undo on the Blocked screen must survive the screen being recreated (rotation, fold). */
@RunWith(AndroidJUnit4::class)
class BlockedViewModelUndoTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(Job() + Dispatchers.Main)
    private val events: MutableList<BlockedEvent> = Collections.synchronizedList(mutableListOf())
    private lateinit var vm: BlockedViewModel

    @Before
    fun setUp() {
        vm = BlockedViewModel(app, FakeSpamRepository())
        // The state is shared WhileSubscribed, as the screen does.
        scope.launch { vm.state.collect { } }
        awaitUntil { !vm.state.value.loading }
    }

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun pendingDeleteOffersUndoAgainAfterRecreation() {
        val sender = vm.state.value.groups.first().address
        val collector = scope.launch { vm.eventFlow.collect { events += it } }
        vm.delete(setOf(sender))
        awaitUntil { events.any { it is BlockedEvent.Deleted } }
        val token = (events.single() as BlockedEvent.Deleted).token
        assertThat(vm.state.value.groups.map { it.address }).doesNotContain(sender)

        // Rotation: the old screen's collector and snackbar are cancelled unanswered, a new screen starts.
        collector.cancel()
        vm.replayPending()
        scope.launch { vm.eventFlow.collect { events += it } }
        awaitUntil { events.size == 2 }
        assertThat((events[1] as BlockedEvent.Deleted).token).isEqualTo(token)

        vm.undoDelete(token)
        awaitUntil { sender in vm.state.value.groups.map { it.address } }
    }

    @Test
    fun replayFoldsInEventsTheOldScreenNeverShowed() {
        val sender = vm.state.value.groups.first().address
        // Nobody collecting: the event stays queued, as when rotation happens right after the tap.
        vm.notSpam(setOf(sender))
        awaitUntil { vm.state.value.hasPendingUndo }
        vm.replayPending()

        scope.launch { vm.eventFlow.collect { events += it } }
        idleMain()
        assertThat(events).hasSize(1)
        assertThat(events.single()).isInstanceOf(BlockedEvent.Restored::class.java)
    }
    @Test
    fun replayWithNothingPendingShowsNothing() {
        vm.replayPending()
        scope.launch { vm.eventFlow.collect { events += it } }
        idleMain()
        assertThat(events).isEmpty()
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
}
