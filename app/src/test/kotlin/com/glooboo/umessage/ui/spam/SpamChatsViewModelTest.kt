package com.glooboo.umessage.ui.spam

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SnackbarVisuals
import com.glooboo.umessage.R
import com.glooboo.umessage.extensions.ChatMark
import com.glooboo.umessage.extensions.DeleteResult
import com.glooboo.umessage.spam.BlockReceipt
import com.glooboo.umessage.spam.SpamRepository
import com.glooboo.umessage.models.Conversation
import com.glooboo.umessage.spam.DecisionReason
import com.glooboo.umessage.spam.ListKind
import com.glooboo.umessage.spam.RuleType
import com.glooboo.umessage.spam.SpamRule
import com.glooboo.umessage.spam.SpamThread
import com.glooboo.umessage.spam.engine.SenderVerdict
import com.glooboo.umessage.spam.engine.SpamCause
import com.glooboo.umessage.testing.ChatNotices
import com.glooboo.umessage.testing.resetSharedMessagesDatabase
import com.glooboo.umessage.ui.components.isolateLtr
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

    private fun archivedChat(id: Long, number: String, cause: SpamCause): SpamChat =
        chat(id, number, cause).let { it.copy(conversation = it.conversation.copy(isArchived = true)) }

    private val chats = listOf(
        chat(1, UNKNOWN, SpamCause.UNKNOWN, unread = 2),
        chat(2, BLOCKED, SpamCause.BLOCKED),
        chat(3, NAME, SpamCause.UNKNOWN),
    )

    private val deleted: MutableList<Set<Long>> = Collections.synchronizedList(mutableListOf())

    /** J24: the marks each delete was given, and what the store answers for a chat (default: [deleteResult]). */
    private val deletedMarks: MutableList<Map<Long, ChatMark>> = Collections.synchronizedList(mutableListOf())
    private val deleteResults = HashMap<Long, DeleteResult>()
    private var deleteResult = DeleteResult.DELETED
    private var markedAll = 0
    private val received = HashMap<Long, Pair<String, Int>>()
    private val made = mutableListOf<SpamChatsViewModel>()

    /** J24: what the store holds of each chat now, and which chats were read (the confirm-time read comes first). */
    private val marks = HashMap<Long, ChatMark>()
    private val unreadable = HashSet<Long>()
    private val markReads: MutableList<Long> = Collections.synchronizedList(mutableListOf())

    private fun newModel(source: List<SpamChat>) = SpamChatsViewModel(
        app,
        repository = { repository },
        source = { source },
        place = SenderPlace { verdicts[it] },
        isDefault = { isDefault },
        io = Dispatchers.IO,
        requestSort = { sorted += it },
        newestReceived = { received[it] },
        deleter = { given, _ ->
            deleted.add(given.keys.toSet())
            deletedMarks.add(given)
            given.keys.associateWith { deleteResults[it] ?: deleteResult }
        },
        markAll = { markedAll },
        marker = { id ->
            markReads.add(id)
            if (id in unreadable) null else marks[id] ?: ChatMark(10L, 0L, inSpam = true)
        },
    ).also { made += it }

    @Before
    fun setUp() {
        vm = newModel(chats)
        scope.launch { vm.events.collect { events += it } }
        vm.load()
        awaitUntil { rowIds() == listOf(1L, 2L, 3L) }
    }

    @After
    fun tearDown() {
        scope.cancel()
        made.forEach { EventBus.getDefault().unregister(it) }
        resetSharedMessagesDatabase()
    }

    /** Shows [event]'s snackbar as the route does, then cancels it unanswered (the tab left, a rotation). */
    private fun showThenCancel(event: SpamChatsEvent) {
        val job = scope.launch { showSpamEvent(event, SnackbarHostState(), vm, app.resources, onViewRule = {}) }
        awaitUntil { job.isActive }
        job.cancel()
        awaitUntil { job.isCompleted }
    }

    private fun rowIds() = vm.state.value.rows.map { it.threadId }

    private fun rules() = runBlocking { repository.rules.first() }

    private inline fun <reified T : SpamChatsEvent> awaitEvent(): T {
        awaitUntil { events.any { it is T } }
        return events.filterIsInstance<T>().last()
    }

    private fun awaitUntil(timeoutMs: Long = TIMEOUT_MS, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
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
    fun movedToMessagesSaysArchivedOnlyWhenEveryChatGoesThere() {
        val model = newModel(
            listOf(chat(1, UNKNOWN, SpamCause.UNKNOWN), archivedChat(2, BLOCKED, SpamCause.BLOCKED)),
        )
        val seen = Collections.synchronizedList(mutableListOf<SpamChatsEvent>())
        scope.launch { model.events.collect { seen += it } }
        model.load()
        awaitUntil { model.state.value.rows.size == 2 }

        model.notSpam(setOf(1L, 2L))

        awaitUntil { seen.any { it is SpamChatsEvent.MovedToMessages } }
        // Only one of the two is archived: the snackbar must not say that all of them stay in Archived.
        assertThat(seen.filterIsInstance<SpamChatsEvent.MovedToMessages>().single().archived).isFalse()
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

    // ---- Fix round (C-1 to C-16) -------------------------------------------------------------------------------

    @Test
    fun aDeleteUndoSurvivesTheRouteGoingAwayAndIsOfferedAgain() {
        vm.delete(setOf(1L))
        val event = awaitEvent<SpamChatsEvent.Deleted>()
        // D-1: a rotation, a tab switch or the Spam lock closing cancels the snackbar: nothing is deleted.
        showThenCancel(event)
        assertThat(deleted).isEmpty()
        assertThat(rowIds()).doesNotContain(1L)

        // The route starts again: the same delete's Undo is offered again.
        vm.onRouteStarted()
        awaitUntil { events.count { it == event } == 2 }
        vm.undoDelete(event.token)
        awaitUntil { 1L in rowIds() }
        assertThat(deleted).isEmpty()
    }

    @Test
    fun aDeleteRunsOnceWhateverClosesItsWindowTwice() {
        vm.delete(setOf(1L))
        val event = awaitEvent<SpamChatsEvent.Deleted>()
        // D-2: onStop flushes, then the snackbar's own end commits too.
        vm.flushPendingDeletes()
        vm.commitDelete(event.token)
        vm.flushPendingDeletes()
        awaitUntil { deleted.isNotEmpty() }
        ShadowLooper.idleMainLooper()
        assertThat(deleted).hasSize(1)
        // An Undo that comes too late shows nothing that is being deleted.
        vm.undoDelete(event.token)
        assertThat(deleted).hasSize(1)
    }

    @Test
    fun deleteOffIsReadOnLoadNotInADraw() {
        isDefault = false
        vm.load()
        awaitUntil { vm.state.value.deleteOff == R.string.pt_delete_not_default }
    }

    @Test
    fun notSpamUnblockAndBlockCancelTheQuietSpamNoticeOfTheirChats(): Unit = runBlocking {
        listOf(1L, 2L, 3L).forEach { ChatNotices.post(app, it) }
        vm.notSpam(setOf(1L))
        awaitEvent<SpamChatsEvent.MovedToMessages>()
        assertThat(ChatNotices.isShown(app, 1L)).isFalse()

        repository.addRule(SpamRule(kind = ListKind.BLOCK, type = RuleType.NUMBER, pattern = BLOCKED))
        verdicts[BLOCKED] = SenderVerdict.Messages(DecisionReason.CONTACT)
        vm.unblock(setOf(2L))
        awaitEvent<SpamChatsEvent.Unblocked>()
        assertThat(ChatNotices.isShown(app, 2L)).isFalse()

        vm.block(setOf(3L))
        awaitEvent<SpamChatsEvent.Blocked>()
        assertThat(ChatNotices.isShown(app, 3L)).isFalse()
    }

    @Test
    fun noActionLeavesAChatHiddenWhenItsSnackbarIsCancelled(): Unit = runBlocking {
        repository.addRule(SpamRule(kind = ListKind.BLOCK, type = RuleType.NUMBER, pattern = BLOCKED))
        verdicts[BLOCKED] = SenderVerdict.Messages(DecisionReason.CONTACT)
        vm.notSpam(setOf(1L))
        showThenCancel(awaitEvent<SpamChatsEvent.MovedToMessages>())
        vm.unblock(setOf(2L))
        showThenCancel(awaitEvent<SpamChatsEvent.Unblocked>())
        // The chats did not really move (the source is fixed): each shows again, in this list.
        awaitUntil { rowIds() == listOf(1L, 2L, 3L) }
    }

    @Test
    fun theRouteStartingClosesEveryOpenWindow() {
        vm.notSpam(setOf(1L))
        awaitEvent<SpamChatsEvent.MovedToMessages>()
        assertThat(rowIds()).doesNotContain(1L)

        vm.onRouteStarted()
        awaitUntil { 1L in rowIds() }
    }

    @Test
    fun aDeleteThatDidNotHappenSaysWhyAndTheChatShowsAgain() {
        deleteResult = DeleteResult.NOT_DEFAULT
        vm.delete(setOf(1L))
        val event = awaitEvent<SpamChatsEvent.Deleted>()
        vm.commitDelete(event.token)

        val message = awaitEvent<SpamChatsEvent.Message>()
        assertThat(message.res).isEqualTo(R.string.pt_delete_not_default)
        awaitUntil { 1L in rowIds() }
    }

    // ---- J24: a delete never takes a text that came after it was confirmed ----------------------------------------

    @Test
    fun theMarkIsTakenBeforeTheChatHidesAndTheUndoIsOffered() {
        vm.delete(setOf(1L))
        awaitEvent<SpamChatsEvent.Deleted>()
        // The event is sent only after the read: nothing that arrives later is part of this delete.
        assertThat(markReads).contains(1L)
    }

    @Test
    fun aTextThatArrivesDuringAPendingDeleteSurvivesAndTheDeleteStopsAtTheMark() {
        vm.delete(setOf(1L, 2L))
        val event = awaitEvent<SpamChatsEvent.Deleted>()
        // A new text lands in chat 1: its id is above the one seen at the confirm.
        marks[1L] = ChatMark(11L, 0L, inSpam = true)
        deleteResults[1L] = DeleteResult.NEWER_TEXT
        vm.commitDelete(event.token)

        awaitUntil { deleted.isNotEmpty() }
        // Each chat is given with the mark of the confirm, never the newer one.
        assertThat(deletedMarks.single().getValue(1L)).isEqualTo(ChatMark(10L, 0L, inSpam = true))
        assertThat(awaitEvent<SpamChatsEvent.KeptNewText>().count).isEqualTo(1)
        awaitUntil { 1L in rowIds() }
    }

    @Test
    fun aChatThatLeftSpamDuringAPendingDeleteIsNotDeletedAndSaysSo() {
        vm.delete(setOf(1L, 2L))
        val event = awaitEvent<SpamChatsEvent.Deleted>()
        marks[1L] = ChatMark(11L, 0L, inSpam = false)
        vm.commitDelete(event.token)

        awaitUntil { deleted.isNotEmpty() }
        assertThat(deleted.single()).containsExactly(2L)
        assertThat(awaitEvent<SpamChatsEvent.KeptOther>().count).isEqualTo(1)
        awaitUntil { 1L in rowIds() }
    }

    @Test
    fun aChatWhoseMarkCouldNotBeReadIsKept() {
        unreadable += 1L
        vm.delete(setOf(1L))
        val event = awaitEvent<SpamChatsEvent.Deleted>()
        vm.commitDelete(event.token)

        assertThat(awaitEvent<SpamChatsEvent.KeptOther>().count).isEqualTo(1)
        assertThat(deleted).isEmpty()
        awaitUntil { 1L in rowIds() }
    }

    @Test
    fun aTabSwitchThenTheActivityStopNeverDeletesTheTextThatCameInBetween() {
        vm.delete(setOf(1L))
        val event = awaitEvent<SpamChatsEvent.Deleted>()
        // The user switches tab: the snackbar is cancelled, the delete stays pending.
        showThenCancel(event)
        // A verification code arrives: the chat moves to Messages. Its notification is tapped: MainActivity stops.
        marks[1L] = ChatMark(12L, 3L, inSpam = false)
        SpamChatsViewModel.flushOpen()

        assertThat(awaitEvent<SpamChatsEvent.KeptOther>().count).isEqualTo(1)
        assertThat(deleted).isEmpty()
        awaitUntil { 1L in rowIds() }
    }

    @Test
    fun aTabSwitchThenTheStopOfAChatStillInSpamDeletesOnlyUpToTheConfirm() {
        vm.delete(setOf(1L))
        val event = awaitEvent<SpamChatsEvent.Deleted>()
        showThenCancel(event)
        marks[1L] = ChatMark(12L, 3L, inSpam = true)
        deleteResults[1L] = DeleteResult.NEWER_TEXT
        SpamChatsViewModel.flushOpen()

        awaitUntil { deleted.isNotEmpty() }
        assertThat(deletedMarks.single().getValue(1L)).isEqualTo(ChatMark(10L, 0L, inSpam = true))
        assertThat(awaitEvent<SpamChatsEvent.KeptNewText>().count).isEqualTo(1)
    }

    @Test
    fun aModelThatGoesAwayDeletesOnlyUpToTheConfirmAndSkipsAChatThatLeftSpam() {
        vm.delete(setOf(1L, 2L))
        awaitEvent<SpamChatsEvent.Deleted>()
        marks[2L] = ChatMark(10L, 0L, inSpam = false)
        // onCleared is protected: the view model store calls it the way the system does.
        val onCleared = SpamChatsViewModel::class.java.getDeclaredMethod("onCleared")
        onCleared.isAccessible = true
        onCleared.invoke(vm)

        awaitUntil { deleted.isNotEmpty() }
        assertThat(deleted.single()).containsExactly(1L)
        assertThat(deletedMarks.single().getValue(1L)).isEqualTo(ChatMark(10L, 0L, inSpam = true))
    }

    @Test
    fun theUndoGoesAwayOnceTheDeleteHasRun() {
        vm.delete(setOf(1L))
        val event = awaitEvent<SpamChatsEvent.Deleted>()
        val snackbar = SnackbarHostState()
        val job = scope.launch { showSpamEvent(event, snackbar, vm, app.resources, onViewRule = {}) }
        awaitUntil { snackbar.currentSnackbarData != null }

        // Leaving the app ran the delete: its Undo must not stay on screen.
        vm.flushPendingDeletes()
        awaitUntil { snackbar.currentSnackbarData == null && job.isCompleted }
        awaitUntil { deleted.isNotEmpty() }
    }

    @Test
    fun aDeleteThatAlreadyRanOffersNoUndoAtAll() {
        vm.delete(setOf(1L))
        val event = awaitEvent<SpamChatsEvent.Deleted>()
        vm.flushPendingDeletes()
        val snackbar = SnackbarHostState()
        val job = scope.launch { showSpamEvent(event, snackbar, vm, app.resources, onViewRule = {}) }

        awaitUntil { job.isCompleted }
        assertThat(snackbar.currentSnackbarData).isNull()
    }

    @Test
    fun aSnackbarIsDismissedOnlyByItsOwnVisuals() {
        val snackbar = SnackbarHostState()
        val mine: SnackbarVisuals = OwnedSnackbar("mine", "Undo", SnackbarDuration.Indefinite)
        val other: SnackbarVisuals = OwnedSnackbar("other", "Undo", SnackbarDuration.Indefinite)
        val job = scope.launch { snackbar.showSnackbar(other) }
        awaitUntil { snackbar.currentSnackbarData != null }

        snackbar.dismissIfShown(mine)
        ShadowLooper.idleMainLooper()
        assertThat(snackbar.currentSnackbarData?.visuals).isSameInstanceAs(other)
        snackbar.dismissIfShown(other)
        awaitUntil { job.isCompleted }
        assertThat(snackbar.currentSnackbarData).isNull()
    }

    @Test
    fun theMainActivityFlushCommitsAWaitingDeleteOfEveryOpenModel() {
        vm.delete(setOf(3L))
        awaitEvent<SpamChatsEvent.Deleted>()
        // D-7: what MainActivity.onStop calls; it reaches the model that exists and makes none.
        SpamChatsViewModel.flushOpen()
        awaitUntil { deleted.isNotEmpty() }
        assertThat(deleted.single()).containsExactly(3L)
    }

    @Test
    fun markAllReadSaysHowManyChatsItRead() {
        markedAll = 2
        vm.markAllRead()
        assertThat(awaitEvent<SpamChatsEvent.MarkedAllRead>().count).isEqualTo(2)
    }

    @Test
    fun unblockOfAPrefixBlockedSenderNamesTheRuleAndChangesNothing(): Unit = runBlocking {
        val exact = repository.addRule(SpamRule(kind = ListKind.BLOCK, type = RuleType.NUMBER, pattern = BLOCKED))
        val prefix = repository.addRule(SpamRule(kind = ListKind.BLOCK, type = RuleType.PREFIX, pattern = "+1555"))
        verdicts[BLOCKED] = SenderVerdict.Spam(SpamCause.BLOCKED, DecisionReason.BLOCK_RULE, prefix)

        vm.unblock(setOf(2L))
        val message = awaitEvent<SpamChatsEvent.Message>()

        assertThat(message.res).isEqualTo(R.string.pt_unblock_refused_prefix)
        // M-2: the prefix keeps its order in a right-to-left sentence.
        assertThat(message.arg).isEqualTo(isolateLtr("+1555"))
        assertThat(message.arg).isNotEqualTo("+1555")
        assertThat(message.viewRule).isTrue()
        assertThat(events.none { it is SpamChatsEvent.Windowed }).isTrue()
        // The exact rule came back: nothing changed, so there is no Undo that does nothing.
        assertThat(rules().map { it.pattern }).containsExactly("+1555", BLOCKED)
        assertThat(exact).isGreaterThan(0L)
    }

    @Test
    fun viewRuleOnTheRefusedUnblockMessageOpensTheRules() {
        val snackbar = SnackbarHostState()
        var opened = 0
        val event = SpamChatsEvent.Message(R.string.pt_unblock_refused_prefix, "+1555", viewRule = true)
        val job = scope.launch { showSpamEvent(event, snackbar, vm, app.resources, onViewRule = { opened++ }) }
        awaitUntil { snackbar.currentSnackbarData != null }
        val bar = snackbar.currentSnackbarData!!
        assertThat(bar.visuals.message).isEqualTo(app.getString(R.string.pt_unblock_refused_prefix, "+1555"))
        assertThat(bar.visuals.actionLabel).isEqualTo(app.getString(R.string.e_view_rule))
        bar.performAction()
        awaitUntil { job.isCompleted }
        assertThat(opened).isEqualTo(1)
    }

    @Test
    fun viewRuleClosesAnUndoSnackbarThatIsStillOpen() {
        // L-7: the refused unblock's reason shows at once, not after the open Undo window runs out.
        val snackbar = SnackbarHostState()
        var undo: SnackbarResult? = null
        val open = scope.launch {
            undo = snackbar.showSnackbar("Undo window", actionLabel = "Undo", duration = SnackbarDuration.Indefinite)
        }
        awaitUntil { snackbar.currentSnackbarData != null }
        val event = SpamChatsEvent.Message(R.string.pt_unblock_refused_prefix, "+1555", viewRule = true)
        val job = scope.launch { showSpamEvent(event, snackbar, vm, app.resources, onViewRule = {}) }
        awaitUntil { open.isCompleted }
        assertThat(undo).isEqualTo(SnackbarResult.Dismissed)
        awaitUntil { snackbar.currentSnackbarData?.visuals?.actionLabel == app.getString(R.string.e_view_rule) }
        job.cancel()
    }

    @Test
    fun aPlainMessageHasNoAction() {
        val snackbar = SnackbarHostState()
        val event = SpamChatsEvent.Message(R.string.thread_unblock_failed)
        val job = scope.launch {
            showSpamEvent(event, snackbar, vm, app.resources, onViewRule = { error("no action") })
        }
        awaitUntil { snackbar.currentSnackbarData != null }
        assertThat(snackbar.currentSnackbarData!!.visuals.actionLabel).isNull()
        job.cancel()
    }

    @Test
    fun unblockWhoseVerdictCannotBeReadSaysSoNeutrally(): Unit = runBlocking {
        repository.addRule(SpamRule(kind = ListKind.BLOCK, type = RuleType.NUMBER, pattern = BLOCKED))
        verdicts[BLOCKED] = null

        vm.unblock(setOf(2L))
        assertThat(awaitEvent<SpamChatsEvent.UnblockedUnsure>().count).isEqualTo(1)
        assertThat(events.none { it is SpamChatsEvent.StillOnSystemList }).isTrue()
    }

    @Test
    fun unblockOnAndroidsListOnlyChangesNothingAndHasNoUndo() {
        verdicts[BLOCKED] = SenderVerdict.Spam(SpamCause.BLOCKED, DecisionReason.BLOCK_RULE)

        vm.unblock(setOf(2L))
        assertThat(awaitEvent<SpamChatsEvent.Message>().res).isEqualTo(R.string.thread_unblock_done_system)
        assertThat(events.none { it is SpamChatsEvent.Windowed }).isTrue()
    }

    @Test
    fun aFailedUnblockInTheMiddlePutsBackWhatWasRemoved(): Unit = runBlocking {
        val other = "+15550001252"
        val twoBlocked = listOf(chat(2, BLOCKED, SpamCause.BLOCKED), chat(4, other, SpamCause.BLOCKED))
        val failing = object : SpamRepository by repository {
            override suspend fun removeRule(id: Long) {
                check(rules().first { it.id == id }.pattern != other) { "disk full" }
                repository.removeRule(id)
            }
        }
        repository.addRule(SpamRule(kind = ListKind.BLOCK, type = RuleType.NUMBER, pattern = BLOCKED))
        repository.addRule(SpamRule(kind = ListKind.BLOCK, type = RuleType.NUMBER, pattern = other))
        val model = SpamChatsViewModel(
            app, { failing }, { twoBlocked }, SenderPlace { verdicts[it] }, { true }, Dispatchers.IO, {},
        ).also { made += it }
        val seen = Collections.synchronizedList(mutableListOf<SpamChatsEvent>())
        scope.launch { model.events.collect { seen += it } }
        model.load()
        awaitUntil { model.state.value.rows.size == 2 }

        model.unblock(setOf(2L, 4L))
        awaitUntil { seen.any { it == SpamChatsEvent.Failed } }
        assertThat(rules().map { it.pattern }).containsExactly(BLOCKED, other)
    }

    @Test
    fun notSpamFromTheStillUnknownSnackbarWorksBeforeTheSortRan(): Unit = runBlocking {
        repository.addRule(SpamRule(kind = ListKind.BLOCK, type = RuleType.NUMBER, pattern = BLOCKED))
        verdicts[BLOCKED] = SenderVerdict.Spam(SpamCause.UNKNOWN, DecisionReason.UNKNOWN_SENDER)
        vm.unblock(setOf(2L))
        val still = awaitEvent<SpamChatsEvent.StillUnknown>()

        // C-15: the saved cause is still BLOCKED; "Not spam" from the snackbar must act anyway.
        vm.notSpam(still.ids, anyCause = true)
        awaitEvent<SpamChatsEvent.MovedToMessages>()
        assertThat(rules().map { it.kind to it.pattern }).containsExactly(ListKind.ALLOW to BLOCKED)
    }

    @Test
    fun copyCodeReadsTheNewestReceivedTextAndAndroidsListGetsItsLine() {
        received[1L] = "Your code: 739204" to (now / 1000L).toInt()
        verdicts[BLOCKED] = SenderVerdict.Spam(SpamCause.BLOCKED, DecisionReason.BLOCK_RULE)
        vm.load()
        awaitUntil { vm.state.value.rows.first { it.threadId == 1L }.otpCode == "739204" }
        val blockedRow = vm.state.value.rows.first { it.threadId == 2L }
        assertThat(blockedRow.causeLine).isEqualTo(app.getString(R.string.e_note_system_list))
        // The snippet alone (it could be the user's own text) never offers a code.
        assertThat(vm.state.value.rows.first { it.threadId == 3L }.otpCode).isNull()
    }

    @Test
    fun blockThatBlocksNothingSaysItFailed() {
        val nothing = object : SpamRepository by repository {
            override suspend fun blockAddress(address: String, note: String): BlockReceipt? = null
        }
        val model = SpamChatsViewModel(
            app, { nothing }, { chats }, SenderPlace { verdicts[it] }, { true }, Dispatchers.IO, {},
        ).also { made += it }
        val seen = Collections.synchronizedList(mutableListOf<SpamChatsEvent>())
        scope.launch { model.events.collect { seen += it } }
        model.load()
        awaitUntil { model.state.value.rows.size == 3 }

        model.block(setOf(1L))
        awaitUntil { seen.any { it == SpamChatsEvent.Failed } }
        assertThat(seen.none { it is SpamChatsEvent.Blocked }).isTrue()
    }

    @Test
    fun fiveThousandChatsBuildEachRowOnceAndASwipeBuildsNone() {
        val many = (1L..5_000L).map { chat(it, "+1555${it.toString().padStart(7, '0')}", SpamCause.UNKNOWN) }
        val model = newModel(many)
        model.load()
        awaitUntil(LONG_TIMEOUT_MS) { model.state.value.rows.size == 5_000 }
        assertThat(model.rowBuilds).isEqualTo(5_000)

        // C-7, D-7: a swipe hides by filtering the built rows; a read again takes every row from the cache.
        model.notSpam(setOf(42L))
        awaitUntil { model.state.value.rows.size == 4_999 }
        model.load()
        awaitUntil { model.state.value.rows.size == 4_999 && !model.state.value.loading }
        ShadowLooper.idleMainLooper()
        assertThat(model.rowBuilds).isEqualTo(5_000)
    }

    @Test
    fun aLanguageChangeBuildsTheRowsAgainInTheNewLanguage() {
        val before = vm.state.value.rows.first { it.threadId == 1L }.causeLine
        val old = app.resources.configuration.locales[0]
        try {
            val config = android.content.res.Configuration(app.resources.configuration)
            config.setLocale(java.util.Locale.forLanguageTag("fa"))
            @Suppress("DEPRECATION")
            app.resources.updateConfiguration(config, app.resources.displayMetrics)
            vm.load()
            awaitUntil { vm.state.value.rows.firstOrNull { it.threadId == 1L }?.causeLine != before }
            assertThat(vm.state.value.rows.first { it.threadId == 1L }.causeLine)
                .isEqualTo(app.getString(R.string.spam_reason_unknown_sender))
        } finally {
            val config = android.content.res.Configuration(app.resources.configuration)
            config.setLocale(old)
            @Suppress("DEPRECATION")
            app.resources.updateConfiguration(config, app.resources.displayMetrics)
        }
    }

    @Test
    fun theTimeBucketMovesEachMinuteForAYoungChatAndEachDayForAnOldOne() {
        val noon = 1_700_000_000_000L
        val young = (noon / 1000L).toInt() - 600
        assertThat(spamTimeBucket(young, noon + 30_000L, 0L)).isEqualTo(spamTimeBucket(young, noon, 0L))
        assertThat(spamTimeBucket(young, noon + 60_000L, 0L)).isNotEqualTo(spamTimeBucket(young, noon, 0L))
        val old = (noon / 1000L).toInt() - 5 * 3_600
        assertThat(spamTimeBucket(old, noon + 60_000L, 0L)).isEqualTo(spamTimeBucket(old, noon, 0L))
        assertThat(spamTimeBucket(old, noon + 24 * 3_600_000L, 0L)).isNotEqualTo(spamTimeBucket(old, noon, 0L))
        // A young chat turning an hour old does not share a bucket with the minute it was in.
        assertThat(spamTimeBucket(young, noon + 3_600_000L, 0L)).isNotEqualTo(spamTimeBucket(young, noon, 0L))
    }

    private companion object {
        const val LONG_TIMEOUT_MS = 20_000L
        const val UNKNOWN = "+15550001250"
        const val BLOCKED = "+15550001251"
        const val NAME = "SHOPDEAL"
        const val TIMEOUT_MS = 5_000L
    }
}
