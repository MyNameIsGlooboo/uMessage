package com.glooboo.umessage.ui.conversations

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Looper
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.extensions.ChatMark
import com.glooboo.umessage.extensions.DeleteResult
import com.glooboo.umessage.extensions.config
import com.glooboo.umessage.extensions.draftsDB
import com.glooboo.umessage.models.Draft
import com.glooboo.umessage.testing.offMainThread
import com.glooboo.umessage.helpers.HiddenChats
import com.glooboo.umessage.helpers.HiddenChatsSession
import com.glooboo.umessage.helpers.MessageCategory
import com.glooboo.umessage.helpers.MuteDuration
import com.glooboo.umessage.helpers.MutePrefs
import com.glooboo.umessage.helpers.W3cPrefs
import com.glooboo.umessage.models.Conversation
import com.glooboo.umessage.models.MessageNote
import com.glooboo.umessage.helpers.messageNotesDB
import com.glooboo.umessage.testing.resetSharedMessagesDatabase
import com.glooboo.umessage.extensions.conversationsDB
import com.glooboo.umessage.extensions.forgetThread
import com.glooboo.umessage.spam.SortResult
import com.glooboo.umessage.spam.SpamChanges
import com.glooboo.umessage.spam.SpamRepository
import com.glooboo.umessage.spam.SpamSorting
import com.glooboo.umessage.spam.SpamThread
import com.glooboo.umessage.spam.data.SpamPlacement
import com.glooboo.umessage.spam.engine.SpamCause
import com.glooboo.umessage.ui.spam.SenderBlocking
import com.glooboo.umessage.ui.spam.SpamChat
import com.glooboo.umessage.ui.spam.SpamChatsSource
import com.glooboo.umessage.ui.spam.SpamRepositoryProvider
import com.google.common.truth.Truth.assertThat
import org.fossify.commons.extensions.baseConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import java.util.Collections

/** Archive and delete-with-undo in the main list. The telephony provider is faked so archiving succeeds. */
@RunWith(AndroidJUnit4::class)
class ConversationsViewModelTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(Job() + Dispatchers.Main)
    private val events: MutableList<ConversationsEvent> = Collections.synchronizedList(mutableListOf())
    private lateinit var vm: ConversationsViewModel

    @Before
    fun setUp() {
        Robolectric.buildContentProvider(AcceptingThreadsProvider::class.java).create("mms-sms")
        vm = ConversationsViewModel(app)
        scope.launch { vm.events.collect { events += it } }
    }

    @After
    fun tearDown() {
        scope.cancel()
        extra.forEach {
            it.stopObservingNotes()
            it.viewModelScope.cancel()
        }
        vm.stopObservingNotes()
        // The model is never cleared here: its Spam count would otherwise read the next test's database.
        vm.viewModelScope.cancel()
        // The list model watches the notes table, so it opens the shared messages database.
        resetSharedMessagesDatabase()
    }

    @Test
    fun unarchivedChatComesBackToTheList() {
        vm.submit(listOf(dad, mom), cached = false, firstRun = false)
        awaitUntil { rowIds() == listOf(dad.threadId, mom.threadId) }

        vm.archiveOne(dad.threadId)
        awaitUntil { events.any { it is ConversationsEvent.Archived } }
        assertThat(rowIds()).containsExactly(mom.threadId)

        // Unarchived from the Archived tab: the host's next list has Dad again.
        vm.submit(listOf(dad, mom), cached = false, firstRun = false)
        awaitUntil { dad.threadId in rowIds() }
        assertThat(rowIds()).containsExactly(dad.threadId, mom.threadId)
    }

    /** B06: a hidden chat leaves the list until it is unlocked, and the list says that something is hidden. */
    @Test
    fun aHiddenChatLeavesTheListUntilItIsUnlocked() {
        app.baseConfig.isAppPasswordProtectionOn = true
        HiddenChatsSession.reset()
        HiddenChats.get(app).hide(dad.threadId)

        vm.submit(listOf(dad, mom), cached = false, firstRun = false)
        awaitUntil { vm.state.value.hiddenChats == 1 }
        assertThat(rowIds()).containsExactly(mom.threadId)
        assertThat(vm.state.value.hiddenUnlocked).isFalse()

        HiddenChatsSession.unlock()
        vm.refresh()
        awaitUntil { vm.state.value.hiddenUnlocked }
        assertThat(rowIds()).containsExactly(dad.threadId, mom.threadId)
        HiddenChatsSession.reset()
    }

    /** N05: a muted chat shows the muted bell in the list. */
    @Test
    fun aMutedChatShowsTheMutedMark() {
        MutePrefs.get(app).mute(dad.threadId, MuteDuration.Always)

        vm.submit(listOf(dad, mom), cached = false, firstRun = false)
        awaitUntil { rowIds().size == 2 }

        assertThat(vm.state.value.rows.single { it.threadId == dad.threadId }.muted).isTrue()
        assertThat(vm.state.value.rows.single { it.threadId == mom.threadId }.muted).isFalse()
    }

    @Test
    fun pendingDeleteOffersUndoAgainAfterTheScreenIsRecreated() {
        vm.submit(listOf(dad, mom), cached = false, firstRun = false)
        awaitUntil { rowIds().size == 2 }

        vm.deleteOne(dad.threadId)
        vm.confirmDelete()
        awaitUntil { events.any { it is ConversationsEvent.Deleted } }
        val token = (events.single { it is ConversationsEvent.Deleted } as ConversationsEvent.Deleted).token
        // The event is sent before the background rebuild has dropped the chat from the rows: wait for the rows too.
        awaitUntil { rowIds() == listOf(mom.threadId) }

        // Rotation cancelled the snackbar without an answer; the new screen asks the view model to replay.
        vm.replayPendingUndo()
        awaitUntil { events.count { it is ConversationsEvent.Deleted } == 2 }
        assertThat(events.filterIsInstance<ConversationsEvent.Deleted>().map { it.token }).containsExactly(token, token)
        awaitUntil { rowIds() == listOf(mom.threadId) }

        vm.undoDelete(token)
        awaitUntil { rowIds().size == 2 }
    }

    /** main-1: Delete only asks; the chat stays until the user confirms, and Cancel changes nothing. */
    @Test
    fun deleteWaitsForConfirmation() {
        vm.submit(listOf(dad, mom), cached = false, firstRun = false)
        awaitUntil { rowIds().size == 2 }

        vm.deleteOne(dad.threadId)
        awaitUntil { vm.state.value.confirmDeleteCount == 1 }
        assertThat(rowIds()).containsExactly(dad.threadId, mom.threadId)
        assertThat(events.filterIsInstance<ConversationsEvent.Deleted>()).isEmpty()

        vm.cancelDelete()
        awaitUntil { vm.state.value.confirmDeleteCount == 0 }
        assertThat(rowIds().size).isEqualTo(2)

        vm.deleteOne(dad.threadId)
        awaitUntil { vm.state.value.confirmDeleteCount == 1 }
        vm.confirmDelete()
        awaitUntil { rowIds() == listOf(mom.threadId) && vm.state.value.confirmDeleteCount == 0 }
    }

    /** MC-37, provider truth (4.1): Report spam blocks the sender, so the chat leaves Messages; Undo takes it back. */
    @Test
    fun reportSpamBlocksTheSenderAndMovesTheChat() {
        vm.submit(listOf(dad, mom), cached = false, firstRun = false)
        awaitUntil { rowIds().size == 2 }
        vm.startSelect(dad.threadId)
        awaitUntil { vm.state.value.selection.canReportSpam }

        vm.reportSpamSelected()
        awaitUntil { events.any { it is ConversationsEvent.ReportedSpam } }
        val event = events.single { it is ConversationsEvent.ReportedSpam } as ConversationsEvent.ReportedSpam
        // The list is rebuilt off the main thread, so the event can come first.
        awaitUntil { rowIds() == listOf(mom.threadId) }
        val repository = SpamRepositoryProvider.get(app)
        assertThat(SenderBlocking.isBlocked(rules(repository), dad.phoneNumber)).isTrue()

        vm.undoReportSpam(event.token)
        awaitUntil { !SenderBlocking.isBlocked(rules(repository), dad.phoneNumber) }
        awaitUntil { rowIds() == listOf(dad.threadId, mom.threadId) }
    }

    /** MC-23, provider truth (4.1): Block adds a uMessage rule and moves the chat; Undo removes the rule again. */
    @Test
    fun blockMovesTheChatAndCanBeUndone() {
        vm.submit(listOf(dad, mom), cached = false, firstRun = false)
        awaitUntil { rowIds().size == 2 }
        vm.startSelect(mom.threadId)
        awaitUntil { vm.state.value.selection.count == 1 }

        vm.blockSelected()
        awaitUntil { events.any { it is ConversationsEvent.Blocked } }
        val event = events.single { it is ConversationsEvent.Blocked } as ConversationsEvent.Blocked
        awaitUntil { rowIds() == listOf(dad.threadId) }
        val repository = SpamRepositoryProvider.get(app)
        assertThat(SenderBlocking.isBlocked(rules(repository), mom.phoneNumber)).isTrue()

        vm.undoReportSpam(event.token)
        awaitUntil { !SenderBlocking.isBlocked(rules(repository), mom.phoneNumber) }
        awaitUntil { rowIds() == listOf(dad.threadId, mom.threadId) }
    }

    @Test
    fun reportSpamIsNotOfferedForGroupChats() {
        val group = dad.copy(threadId = 3, isGroupConversation = true)
        vm.submit(listOf(group, mom), cached = false, firstRun = false)
        awaitUntil { rowIds().size == 2 }
        vm.startSelect(group.threadId)
        awaitUntil { vm.state.value.selection.count == 1 }
        assertThat(vm.state.value.selection.canReportSpam).isFalse()
    }

    @Test
    fun blockIsOfferedAndAppliedToSingleSendersOnly() {
        val group = dad.copy(threadId = 3, isGroupConversation = true)
        vm.submit(listOf(group, mom), cached = false, firstRun = false)
        awaitUntil { rowIds().size == 2 }
        vm.startSelect(group.threadId)
        awaitUntil { vm.state.value.selection.count == 1 }
        assertThat(vm.state.value.selection.canBlock).isFalse()
        vm.startSelect(mom.threadId)
        awaitUntil { vm.state.value.selection.count == 2 }
        assertThat(vm.state.value.selection.canBlock).isTrue()

        vm.blockSelected()
        awaitUntil { events.any { it is ConversationsEvent.Blocked } }
        val event = events.single { it is ConversationsEvent.Blocked } as ConversationsEvent.Blocked
        assertThat(event.count).isEqualTo(1)
        val repository = SpamRepositoryProvider.get(app)
        vm.undoReportSpam(event.token)
        awaitUntil { !SenderBlocking.isBlocked(rules(repository), mom.phoneNumber) }
    }

    private fun rules(repository: SpamRepository) = runBlocking { repository.rules.first() }

    @Test
    fun replayWithNothingPendingShowsNothing() {
        vm.replayPendingUndo()
        idleMain()
        assertThat(events).isEmpty()
    }

    @Test
    fun unreadFirstPutsUnreadChatsAboveNewerReadOnesAndPinnedStaysOnTop() {
        val pinned = conversation(3, "Pinned", date = 10)
        val unreadOld = conversation(4, "Unread", date = 50).apply { read = false }
        app.config.addPinnedConversations(listOf(pinned))
        W3cPrefs.get(app).unreadFirst = true
        vm.submit(listOf(dad, mom, unreadOld, pinned), cached = false, firstRun = false)
        awaitUntil { rowIds().size == 4 }
        assertThat(rowIds()).containsExactly(3L, 4L, 1L, 2L).inOrder()
    }

    @Test
    fun withUnreadFirstOffTheOrderIsByDate() {
        val unreadOld = conversation(4, "Unread", date = 50).apply { read = false }
        vm.submit(listOf(dad, mom, unreadOld), cached = false, firstRun = false)
        awaitUntil { rowIds().size == 3 }
        assertThat(rowIds()).containsExactly(1L, 2L, 4L).inOrder()
    }

    @Test
    fun aBankLineIsATransactionButAContactWithTheSameWordsIsNot() {
        val bank = Conversation(
            threadId = 5, snippet = "Deposit 500,000 rials. Balance: 1,200,000 rials", date = 300, read = true,
            title = "BankX", photoUri = "", isGroupConversation = false, phoneNumber = "BankX",
        )
        val friend = bank.copy(threadId = 6, title = "Ali", phoneNumber = "5550106")
        vm.submit(listOf(bank, friend), cached = false, firstRun = false)
        awaitUntil { rowIds().size == 2 }
        val categories = vm.state.value.rows.associate { it.threadId to it.category }
        assertThat(categories[5L]).isEqualTo(MessageCategory.Transactions)
        assertThat(categories[6L]).isEqualTo(MessageCategory.Personal)
    }

    /** A chat with a private note on one of its messages shows the note mark; the others do not. */
    @Test
    fun aChatWithANoteShowsTheNoteMark() {
        vm.submit(listOf(dad, mom), cached = false, firstRun = false)
        awaitUntil { rowIds().size == 2 }
        assertThat(vm.state.value.rows.none { it.hasNotes }).isTrue()

        val note = MessageNote("v1|s|r|100|0001|1", mom.threadId, 100, "rent", createdAt = 1, updatedAt = 1)
        runBlocking { app.messageNotesDB.upsert(note) }
        awaitUntil { vm.state.value.rows.any { it.hasNotes } }
        assertThat(vm.state.value.rows.single { it.threadId == mom.threadId }.hasNotes).isTrue()
        assertThat(vm.state.value.rows.single { it.threadId == dad.threadId }.hasNotes).isFalse()

        runBlocking { app.messageNotesDB.delete(note.key) }
        awaitUntil { vm.state.value.rows.none { it.hasNotes } }
    }

    /** J2: a draft saved in the chat reaches the list from the drafts table itself, not from a read on every resume. */
    @Test
    fun aSavedDraftShowsWithoutAResume() {
        vm.submit(listOf(dad, mom), cached = false, firstRun = false)
        awaitUntil { rowIds().size == 2 }
        assertThat(vm.state.value.rows.none { it.hasDraft }).isTrue()

        offMainThread { app.draftsDB.insertOrUpdate(Draft(threadId = mom.threadId, body = "See you at six", date = 1)) }

        awaitUntil { vm.state.value.rows.single { it.threadId == mom.threadId }.hasDraft }
        assertThat(vm.state.value.rows.single { it.threadId == dad.threadId }.hasDraft).isFalse()
    }

    /** J19: a spam text received while the screen is open shows the Spam tab's number, without a restart. */
    @Test
    fun aReceivedSpamTextShowsTheTabNumberWithTheScreenOpen() {
        vm.submit(listOf(dad, mom), cached = false, firstRun = false)
        offMainThread { seedSpamChat(SPAM_ID) }
        awaitUntil { rowIds().size == 2 }
        assertThat(vm.state.value.newBlockedCount).isEqualTo(0)

        offMainThread { SpamPlacement.raiseUnread(app, SPAM_ID) }

        awaitUntil { vm.state.value.newBlockedCount == 1 }
    }

    /** J19: opening the Spam tab saves the seen time, and the number goes to 0 and stays there. */
    @Test
    fun theTabNumberGoesToZeroWhenTheTabIsSeen() {
        vm.submit(listOf(dad, mom), cached = false, firstRun = false)
        offMainThread { seedSpamChat(SPAM_ID) }
        offMainThread { SpamPlacement.raiseUnread(app, SPAM_ID) }
        awaitUntil { vm.state.value.newBlockedCount == 1 }

        vm.markBlockedSeen()

        awaitUntil { vm.state.value.newBlockedCount == 0 }
        idleMain()
        assertThat(vm.state.value.newBlockedCount).isEqualTo(0)
    }

    /** J19: "Mark as read" in the Spam tab clears the count, and the Messages tab number follows. */
    @Test
    fun theTabNumberGoesToZeroWhenTheSpamChatIsMarkedRead() {
        vm.submit(listOf(dad, mom), cached = false, firstRun = false)
        offMainThread { seedSpamChat(SPAM_ID) }
        offMainThread { SpamPlacement.raiseUnread(app, SPAM_ID) }
        awaitUntil { vm.state.value.newBlockedCount == 1 }

        offMainThread { SpamChatsSource.markRead(app, listOf(spamChat())) }

        awaitUntil { vm.state.value.newBlockedCount == 0 }
    }

    @Test
    fun markReadAndMarkAllReadSignalASpamChange() {
        offMainThread { seedSpamChat(SPAM_ID) }
        offMainThread { SpamPlacement.raiseUnread(app, SPAM_ID) }
        val before = SpamChanges.changes.value

        offMainThread { SpamChatsSource.markRead(app, listOf(spamChat())) }
        val afterOne = SpamChanges.changes.value
        offMainThread { SpamChatsSource.markAllRead(app, listOf(spamChat())) }

        assertThat(afterOne).isGreaterThan(before)
        assertThat(SpamChanges.changes.value).isGreaterThan(afterOne)
    }

    @Test
    fun forgettingASpamChatSignalsASpamChange() {
        offMainThread { seedSpamChat(SPAM_ID) }
        val before = SpamChanges.changes.value

        offMainThread { app.forgetThread(SPAM_ID) }

        assertThat(SpamChanges.changes.value).isGreaterThan(before)
        offMainThread { assertThat(app.conversationsDB.getSpamThread(SPAM_ID)).isNull() }
    }

    private fun spamChat() = SpamChat(
        app.conversationsDB.getConversationWithThreadId(SPAM_ID)!!, app.conversationsDB.getSpamThread(SPAM_ID)!!,
    )

    /** J19: "Not spam" takes the chat out of the Spam table; a published sort makes the number go down. */
    @Test
    fun theTabNumberDropsAfterNotSpam() {
        vm.submit(listOf(dad, mom), cached = false, firstRun = false)
        offMainThread { seedSpamChat(SPAM_ID) }
        offMainThread { SpamPlacement.raiseUnread(app, SPAM_ID) }
        awaitUntil { vm.state.value.newBlockedCount == 1 }

        offMainThread {
            app.conversationsDB.deleteSpamThread(SPAM_ID)
            SpamSorting.published(app, SortResult.Applied(added = 0, removed = 1, changed = 0))
        }

        awaitUntil { vm.state.value.newBlockedCount == 0 }
    }

    private fun seedSpamChat(id: Long) {
        val number = "+1555000123$id"
        app.conversationsDB.insertOrUpdate(
            Conversation(
                threadId = id, snippet = "hi", date = 1_000, read = true, title = number, photoUri = "",
                isGroupConversation = false, phoneNumber = number,
            ),
        )
        app.conversationsDB.insertSpamThread(SpamThread(id, number, SpamCause.UNKNOWN, unread = 0, spamSince = 1L))
    }

    private fun rowIds() = vm.state.value.rows.map { it.threadId }

    // ---- J24: a delete never takes a text that came after it was confirmed ----------------------------------------

    private val deletedMarks: MutableList<Map<Long, ChatMark>> = Collections.synchronizedList(mutableListOf())
    private val deleteResults = HashMap<Long, DeleteResult>()
    private val unreadable = HashSet<Long>()
    private val readMarks: MutableList<Long> = Collections.synchronizedList(mutableListOf())

    /**
     * A list model whose store reads and deletes are faked: every chat is at [confirmedMark] until the test says
     * else.
     */
    private fun markedModel(): ConversationsViewModel = ConversationsViewModel(
        app,
        marker = { id ->
            readMarks += id
            if (id in unreadable) null else confirmedMark
        },
        deleter = { given, _ ->
            deletedMarks += given
            given.keys.associateWith { deleteResults[it] ?: DeleteResult.DELETED }
        },
    ).also { model ->
        scope.launch { model.events.collect { events += it } }
        model.submit(listOf(dad, mom), cached = false, firstRun = false)
        awaitUntil { model.state.value.rows.size == 2 }
        extra += model
    }

    private val extra = mutableListOf<ConversationsViewModel>()

    private fun confirmDeleteOf(model: ConversationsViewModel, id: Long): Long {
        events.clear()
        model.deleteOne(id)
        awaitUntil { model.state.value.confirmDeleteCount == 1 }
        model.confirmDelete()
        awaitUntil { events.any { it is ConversationsEvent.Deleted } }
        return (events.single { it is ConversationsEvent.Deleted } as ConversationsEvent.Deleted).token
    }

    @Test
    fun theMarkIsTakenBeforeTheChatHidesAndTheUndoIsOffered() {
        val model = markedModel()
        confirmDeleteOf(model, dad.threadId)
        assertThat(readMarks).contains(dad.threadId)
    }

    @Test
    fun aTextThatCameInDuringAPendingDeleteSurvivesAndTheDeleteStopsAtTheMark() {
        val model = markedModel()
        deleteResults[dad.threadId] = DeleteResult.NEWER_TEXT
        val token = confirmDeleteOf(model, dad.threadId)

        model.commitDelete(token)

        awaitUntil { events.any { it is ConversationsEvent.DeleteKept } }
        assertThat(deletedMarks.single()).containsExactly(dad.threadId, confirmedMark)
        val kept = events.filterIsInstance<ConversationsEvent.DeleteKept>().single()
        assertThat(kept.newText).isEqualTo(1)
        assertThat(kept.other).isEqualTo(0)
        // The chat shows again.
        awaitUntil { dad.threadId in model.state.value.rows.map { it.threadId } }
    }

    @Test
    fun aChatWhoseMarkCouldNotBeReadIsKeptAndNothingIsDeleted() {
        val model = markedModel()
        unreadable += dad.threadId
        val token = confirmDeleteOf(model, dad.threadId)

        model.commitDelete(token)

        awaitUntil { events.any { it is ConversationsEvent.DeleteKept } }
        assertThat(deletedMarks).isEmpty()
        assertThat(events.filterIsInstance<ConversationsEvent.DeleteKept>().single().other).isEqualTo(1)
        awaitUntil { dad.threadId in model.state.value.rows.map { it.threadId } }
    }

    @Test
    fun aModelThatGoesAwayStillDeletesOnlyUpToTheConfirm() {
        val model = markedModel()
        confirmDeleteOf(model, dad.threadId)
        // onCleared is protected: call it the way the system does when the model goes away.
        val onCleared = ConversationsViewModel::class.java.getDeclaredMethod("onCleared")
        onCleared.isAccessible = true
        onCleared.invoke(model)

        awaitUntil { deletedMarks.isNotEmpty() }
        assertThat(deletedMarks.single()).containsExactly(dad.threadId, confirmedMark)
    }

    @Test
    fun aFlushClosesTheWindowAndTellsTheScreenToDropItsUndo() {
        val model = markedModel()
        val closed: MutableList<Long> = Collections.synchronizedList(mutableListOf())
        scope.launch { model.deleteClosed.collect { closed += it } }
        val token = confirmDeleteOf(model, dad.threadId)
        assertThat(model.isDeletePending(token)).isTrue()

        model.flushPendingDeletes()

        awaitUntil { token in closed }
        assertThat(model.isDeletePending(token)).isFalse()
        awaitUntil { deletedMarks.isNotEmpty() }
    }

    private fun idleMain() = shadowOf(Looper.getMainLooper()).idle()

    private fun awaitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (true) {
            idleMain()
            if (condition()) return
            check(System.currentTimeMillis() < deadline) { "condition not met; rows=${rowIds()} events=$events" }
            Thread.sleep(10)
        }
    }

    /** Stands in for the telephony provider: every update succeeds, queries return nothing. */
    class AcceptingThreadsProvider : ContentProvider() {
        override fun onCreate() = true
        override fun query(
            uri: Uri,
            p: Array<out String>?,
            s: String?,
            a: Array<out String>?,
            o: String?,
        ): Cursor? = null
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 1
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
        const val SPAM_ID = 9L
        val confirmedMark = ChatMark(10L, 2L, inSpam = false)
        val dad = conversation(1, "Dad", date = 200)
        val mom = conversation(2, "Mom", date = 100)

        fun conversation(id: Long, title: String, date: Int) = Conversation(
            threadId = id, snippet = "hi", date = date, read = true, title = title, photoUri = "",
            isGroupConversation = false, phoneNumber = "555010$id",
        )
    }
}
