package com.glooboo.umessage.ui.spam

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.R
import com.glooboo.umessage.models.Conversation
import com.glooboo.umessage.spam.DecisionReason
import com.glooboo.umessage.spam.ListKind
import com.glooboo.umessage.spam.RuleType
import com.glooboo.umessage.spam.SpamRule
import com.glooboo.umessage.spam.SpamThread
import com.glooboo.umessage.spam.engine.SenderVerdict
import com.glooboo.umessage.spam.engine.SpamCause
import com.glooboo.umessage.testing.ProviderTruthFlagRule
import com.glooboo.umessage.testing.resetSharedMessagesDatabase
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.greenrobot.eventbus.EventBus
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.shadows.ShadowLooper
import java.util.Collections

/**
 * Provider truth, step C, the Spam tab (4.2: U19, U2, U4): cause lines and unread, "Not spam" (with the name question),
 * "Unblock" (back in Messages, or still unknown with "Not spam" offered), Block, the mixed choice, and Delete (off
 * while not the default app; with an Undo window). Every action asks for the chat to be sorted again. Numbers are made
 * up.
 */
@RunWith(AndroidJUnit4::class)
class SpamChatsViewModelTest {

    @get:Rule
    val flag = ProviderTruthFlagRule(enabled = true)

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val repository = FakeSpamRepository(initialBlocked = emptyList(), initialRules = emptyList())
    private val verdicts = HashMap<String, SenderVerdict?>()
    private var isDefault = true
    private val sorted: MutableList<Long> = Collections.synchronizedList(mutableListOf())
    private val events: MutableList<SpamChatsEvent> = Collections.synchronizedList(mutableListOf())
    private val scope = CoroutineScope(Job() + Dispatchers.Main)
    private lateinit var vm: SpamChatsViewModel

    private val now = System.currentTimeMillis()

    private fun chat(id: Long, number: String, cause: SpamCause, unread: Int = 0) = SpamChat(
        Conversation(
            threadId = id, snippet = "Offer $id", date = (now / 1000L).toInt() - id.toInt(), read = true,
            title = number, photoUri = "", isGroupConversation = false, phoneNumber = number,
        ),
        SpamThread(id, number, cause, unread = unread, spamSince = 1L),
    )

    private val chats = listOf(
        chat(1, UNKNOWN, SpamCause.UNKNOWN, unread = 2),
        chat(2, BLOCKED, SpamCause.BLOCKED),
        chat(3, NAME, SpamCause.UNKNOWN),
    )

    @Before
    fun setUp() {
        vm = SpamChatsViewModel(
            app,
            repository = { repository },
            source = { chats },
            place = SenderPlace { verdicts[it] },
            isDefault = { isDefault },
            io = Dispatchers.IO,
            requestSort = { sorted += it },
        )
        scope.launch { vm.events.collect { events += it } }
        vm.load()
        awaitUntil { rowIds() == listOf(1L, 2L, 3L) }
    }

    @After
    fun tearDown() {
        scope.cancel()
        EventBus.getDefault().unregister(vm)
        resetSharedMessagesDatabase()
    }

    private fun rowIds() = vm.state.value.rows.map { it.threadId }

    private fun rules() = runBlocking { repository.rules.first() }

    private inline fun <reified T : SpamChatsEvent> awaitEvent(): T {
        awaitUntil { events.any { it is T } }
        return events.filterIsInstance<T>().last()
    }

    private fun awaitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (true) {
            ShadowLooper.idleMainLooper()
            if (condition()) return
            check(System.currentTimeMillis() < deadline) { "not met; rows=${rowIds()} events=$events" }
            Thread.sleep(10)
        }
    }

    @Test
    fun eachRowShowsItsCauseLineAndTheUnreadOfBothSources() {
        val rows = vm.state.value.rows.associateBy { it.threadId }
        assertThat(rows.getValue(1).causeLine).isEqualTo(app.getString(R.string.spam_reason_unknown_sender))
        assertThat(rows.getValue(2).causeLine).isEqualTo(app.getString(R.string.e_note_blocked_by_you))
        assertThat(rows.getValue(1).unread).isTrue()
        assertThat(rows.getValue(1).unreadCount).isEqualTo(2)
        assertThat(rows.getValue(2).unread).isFalse()
    }

    @Test
    fun aMixedChoiceOffersOnlyDeleteAndMarkAsRead() {
        assertThat(vm.state.value.selection(setOf(1L, 2L))).isEqualTo(SpamSelection(canMarkRead = true))
        assertThat(vm.state.value.selection(setOf(1L, 3L)).canNotSpam).isTrue()
        assertThat(vm.state.value.selection(setOf(2L)).canUnblock).isTrue()
    }

    @Test
    fun notSpamOnANumberMovesTheChatAndUndoRemovesOnlyItsRule() {
        vm.notSpam(setOf(1L))
        val moved = awaitEvent<SpamChatsEvent.MovedToMessages>()

        assertThat(moved.count).isEqualTo(1)
        assertThat(rowIds()).doesNotContain(1L)
        assertThat(rules().map { it.kind to it.pattern }).containsExactly(ListKind.ALLOW to UNKNOWN)
        assertThat(sorted).contains(1L)

        vm.undoNotSpam(moved.token)
        awaitUntil { rules().isEmpty() && 1L in rowIds() }
    }

    @Test
    fun notSpamOnASenderNameAsksOnceFirst() {
        vm.notSpam(setOf(3L))
        awaitUntil { vm.state.value.nameConfirm != null }
        assertThat(vm.state.value.nameConfirm!!.names).containsExactly(NAME)
        assertThat(rules()).isEmpty()

        // Cancel leaves the chat in Spam.
        vm.answerNames(allow = false)
        assertThat(vm.state.value.nameConfirm).isNull()
        assertThat(rules()).isEmpty()

        vm.notSpam(setOf(3L))
        awaitUntil { vm.state.value.nameConfirm != null }
        vm.answerNames(allow = true)
        awaitEvent<SpamChatsEvent.MovedToMessages>()
        assertThat(rules().single().pattern).isEqualTo(NAME)
    }

    @Test
    fun unblockBringsTheChatBackWithUndo(): Unit = runBlocking {
        val rule = repository.addRule(SpamRule(kind = ListKind.BLOCK, type = RuleType.NUMBER, pattern = BLOCKED))
        verdicts[BLOCKED] = SenderVerdict.Messages(DecisionReason.CONTACT)

        vm.unblock(setOf(2L))
        val unblocked = awaitEvent<SpamChatsEvent.Unblocked>()

        assertThat(unblocked.count).isEqualTo(1)
        assertThat(rules()).isEmpty()
        assertThat(rowIds()).doesNotContain(2L)
        assertThat(sorted).contains(2L)

        vm.undoUnblock(unblocked.token)
        awaitUntil { rules().isNotEmpty() && 2L in rowIds() }
        assertThat(rules().single().pattern).isEqualTo(BLOCKED)
        assertThat(rule).isGreaterThan(0L)
    }

    @Test
    fun unblockOfASenderWhoIsStillUnknownSaysSoAndOffersNotSpam(): Unit = runBlocking {
        repository.addRule(SpamRule(kind = ListKind.BLOCK, type = RuleType.NUMBER, pattern = BLOCKED))
        verdicts[BLOCKED] = SenderVerdict.Spam(SpamCause.UNKNOWN, DecisionReason.UNKNOWN_SENDER)

        vm.unblock(setOf(2L))
        val still = awaitEvent<SpamChatsEvent.StillUnknown>()

        assertThat(still.ids).containsExactly(2L)
        assertThat(rules()).isEmpty()
        // The chat stays in Spam (now as an unknown sender, once sorted).
        assertThat(rowIds()).contains(2L)
    }

    @Test
    fun blockOnAnUnknownSenderAddsTheRuleWithUndo() {
        vm.block(setOf(1L))
        val blocked = awaitEvent<SpamChatsEvent.Blocked>()

        assertThat(rules().map { it.kind to it.pattern }).containsExactly(ListKind.BLOCK to UNKNOWN)
        assertThat(rowIds()).contains(1L)

        vm.undoBlock(blocked.token)
        awaitUntil { rules().isEmpty() }
    }

    @Test
    fun deleteIsOffWhileUMessageIsNotTheDefaultAppAndSaysWhy() {
        isDefault = false
        assertThat(vm.canDelete()).isFalse()

        vm.delete(setOf(1L))
        val message = awaitEvent<SpamChatsEvent.Message>()

        assertThat(message.res).isEqualTo(R.string.pt_delete_not_default)
        assertThat(events.none { it is SpamChatsEvent.Deleted }).isTrue()
        assertThat(rowIds()).contains(1L)
    }

    @Test
    fun deleteWaitsForItsUndoWindow() {
        vm.delete(setOf(1L, 2L))
        val deleted = awaitEvent<SpamChatsEvent.Deleted>()

        assertThat(deleted.count).isEqualTo(2)
        awaitUntil { rowIds() == listOf(3L) }

        vm.undoDelete(deleted.token)
        awaitUntil { rowIds() == listOf(1L, 2L, 3L) }
    }

    private companion object {
        const val UNKNOWN = "+15550001250"
        const val BLOCKED = "+15550001251"
        const val NAME = "SHOPDEAL"
        const val TIMEOUT_MS = 5_000L
    }
}
