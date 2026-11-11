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
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * spamui-11 (choosing several messages in the sender thread) and spamui-15 (the restored list goes with the blocked
 * messages). Only fake data and a fake repository: nothing real is read, sent or stored.
 */
@RunWith(AndroidJUnit4::class)
class BlockedRound2S1Test {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(Job() + Dispatchers.Main)

    @After
    fun tearDown() = scope.cancel()

    private fun msg(id: Long, address: String = "AD-SHOP", at: Long = id, body: String = "text $id") =
        BlockedMessage(id, address, body, 1, false, at, DecisionReason.UNKNOWN_SENDER)

    private fun entry(restoredAt: Long, address: String = "AD-SHOP") =
        RestoredEntry(address, restoredAt - 10, restoredAt)

    // region spamui-11: selection helpers

    @Test
    fun aTapTogglesAMessageInAndOutOfTheSelection() {
        val one = toggleMessageSelection(emptySet(), 5)
        assertThat(one).containsExactly(5L)
        assertThat(toggleMessageSelection(one, 6)).containsExactly(5L, 6L)
        assertThat(toggleMessageSelection(one, 5)).isEmpty()
    }

    @Test
    fun aMessageThatLeftDropsOutOfTheSelection() {
        val messages = listOf(msg(1), msg(2))
        assertThat(presentSelection(messages, setOf(1L, 9L))).containsExactly(1L)
        assertThat(presentSelection(messages, emptySet())).isEmpty()
        assertThat(presentSelection(emptyList(), setOf(1L))).isEmpty()
    }

    @Test
    fun onlyMessagesWithContentAreMovedToTheInbox() {
        val empty = BlockedMessage(3, "AD-SHOP", "", 1, true, 3, DecisionReason.UNKNOWN_SENDER)
        val messages = listOf(msg(1), msg(2), empty)
        val chosen = setOf(1L, 3L)
        val releasable = releasableSelection(messages, chosen)
        assertThat(releasable).contains(1L)
        assertThat(releasable).doesNotContain(2L)
        assertThat(releasable.all { id -> messages.first { it.id == id }.canRestoreContent }).isTrue()
    }

    // endregion

    // region spamui-11: several messages through the view model

    @Test
    fun severalChosenMessagesGoToTheInboxTogetherWithOneUndo() {
        val repo = FakeSpamRepository(initialBlocked = listOf(msg(1), msg(2), msg(3)), initialRules = emptyList())
        val vm = BlockedViewModel(app, repo, RestoredLog(prefs = null), ignoreFilter = true)
        val events = mutableListOf<BlockedEvent>()
        scope.launch { vm.state.collect { } }
        scope.launch { vm.eventFlow.collect { events += it } }
        awaitUntil { !vm.state.value.loading }

        vm.releaseMessages(setOf(1L, 3L))

        awaitUntil { events.any { it is BlockedEvent.Restored } }
        val restored = events.filterIsInstance<BlockedEvent.Restored>().single()
        assertThat(restored.count).isEqualTo(2)
        assertThat(vm.state.value.groups.single().messages.map { it.id }).containsExactly(2L)

        vm.undoRestore(restored.token)
        awaitUntil { vm.state.value.groups.singleOrNull()?.messages?.size == 3 }
        assertThat(vm.state.value.restored).isEmpty()
    }

    @Test
    fun severalChosenMessagesAreDeletedTogetherAndUndoBringsThemBack() {
        val repo = FakeSpamRepository(initialBlocked = listOf(msg(1), msg(2), msg(3)), initialRules = emptyList())
        val vm = BlockedViewModel(app, repo, RestoredLog(prefs = null), ignoreFilter = true)
        val events = mutableListOf<BlockedEvent>()
        scope.launch { vm.state.collect { } }
        scope.launch { vm.eventFlow.collect { events += it } }
        awaitUntil { !vm.state.value.loading }

        vm.deleteMessages(setOf(1L, 2L))

        awaitUntil { events.any { it is BlockedEvent.Deleted } }
        val deleted = events.filterIsInstance<BlockedEvent.Deleted>().single()
        assertThat(deleted.count).isEqualTo(2)
        assertThat(vm.state.value.groups.single().messages.map { it.id }).containsExactly(3L)
        vm.undoDelete(deleted.token)
        awaitUntil { vm.state.value.groups.single().messages.size == 3 }
    }

    // endregion

    // region spamui-15: the restored list

    @Test
    fun entriesOlderThanTheLimitAreDroppedOnLoad() {
        val now = 100L * DAY
        val fresh = entry(now - 2 * DAY)
        val old = entry(now - RestoredLog.KEEP_MS - 1)
        val prefs = app.getSharedPreferences("round2_s1_test", android.content.Context.MODE_PRIVATE)
        prefs.edit().putString("entries", RestoredLogCodec.encode(listOf(fresh, old))).commit()

        val log = RestoredLog(prefs, now = { now })

        assertThat(log.entries.value).containsExactly(fresh)
    }

    @Test
    fun theLogDropsEntriesOlderThanThePurgeDays() {
        val now = 100L * DAY
        val recent = entry(now - 2 * DAY, "A")
        val older = entry(now - 10 * DAY, "B")
        val log = RestoredLog(prefs = null, now = { now })
        log.record(listOf(recent, older))

        log.dropOlderThan(days = 7)

        assertThat(log.entries.value).containsExactly(recent)
        assertThat(RestoredLogCodec.dropOlderThan(listOf(recent, older), 30, now))
            .containsExactly(recent, older).inOrder()
    }

    @Test
    fun clearEmptiesTheLog() {
        val now = 100L * DAY
        val log = RestoredLog(prefs = null, now = { now })
        log.record(listOf(entry(now - DAY)))

        log.clear()

        assertThat(log.entries.value).isEmpty()
    }

    @Test
    fun deleteAllClearsTheRestoredListOnceTheDeletionIsFinal() {
        val log = RestoredLog(prefs = null)
        log.record(listOf(RestoredEntry("AD-SHOP", 1L, System.currentTimeMillis())))
        val repo = FakeSpamRepository(initialBlocked = listOf(msg(1), msg(2, "OTHER")), initialRules = emptyList())
        val vm = BlockedViewModel(app, repo, log)
        val events = mutableListOf<BlockedEvent>()
        scope.launch { vm.state.collect { } }
        scope.launch { vm.eventFlow.collect { events += it } }
        awaitUntil { !vm.state.value.loading }

        vm.deleteAll()
        awaitUntil { events.any { it is BlockedEvent.Deleted } }
        // Not final yet: the Undo window is open, and Undo keeps the list.
        assertThat(log.entries.value).hasSize(1)

        vm.commitDelete(events.filterIsInstance<BlockedEvent.Deleted>().single().token)
        awaitUntil { log.entries.value.isEmpty() }
        assertThat(vm.state.value.groups).isEmpty()
    }

    @Test
    fun undoOfDeleteAllKeepsTheRestoredList() {
        val log = RestoredLog(prefs = null)
        log.record(listOf(RestoredEntry("AD-SHOP", 1L, System.currentTimeMillis())))
        val repo = FakeSpamRepository(initialBlocked = listOf(msg(1)), initialRules = emptyList())
        val vm = BlockedViewModel(app, repo, log)
        val events = mutableListOf<BlockedEvent>()
        scope.launch { vm.state.collect { } }
        scope.launch { vm.eventFlow.collect { events += it } }
        awaitUntil { !vm.state.value.loading }

        vm.deleteAll()
        awaitUntil { events.any { it is BlockedEvent.Deleted } }
        val token = events.filterIsInstance<BlockedEvent.Deleted>().single().token
        vm.undoDelete(token)
        vm.commitDelete(token)
        idleMain()

        assertThat(log.entries.value).hasSize(1)
        awaitUntil { vm.state.value.groups.size == 1 }
    }

    @Test
    fun deletingOneSenderLeavesTheRestoredListAlone() {
        val log = RestoredLog(prefs = null)
        log.record(listOf(RestoredEntry("AD-SHOP", 1L, System.currentTimeMillis())))
        val repo = FakeSpamRepository(initialBlocked = listOf(msg(1), msg(2, "OTHER")), initialRules = emptyList())
        val vm = BlockedViewModel(app, repo, log)
        val events = mutableListOf<BlockedEvent>()
        scope.launch { vm.state.collect { } }
        scope.launch { vm.eventFlow.collect { events += it } }
        awaitUntil { !vm.state.value.loading }

        vm.delete(setOf("OTHER"))
        awaitUntil { events.any { it is BlockedEvent.Deleted } }
        vm.commitDelete(events.filterIsInstance<BlockedEvent.Deleted>().single().token)
        awaitUntil { vm.state.value.groups.size == 1 }

        assertThat(log.entries.value).hasSize(1)
    }

    // endregion

    private fun idleMain() = shadowOf(Looper.getMainLooper()).idle()

    private fun awaitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (true) {
            idleMain()
            if (condition()) return
            check(System.currentTimeMillis() < deadline) { "condition not met" }
            Thread.sleep(10)
        }
    }

    private companion object {
        const val DAY = 24L * 60 * 60 * 1000
    }
}
