package com.glooboo.umessage.ui.conversations

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.extensions.config
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
import com.glooboo.umessage.spam.SpamRepository
import com.glooboo.umessage.ui.spam.SenderBlocking
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
        vm.stopObservingNotes()
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

    /** MC-37: Report spam blocks the sender with a uMessage rule and archives the chat; Undo takes both back. */
    @Test
    fun reportSpamBlocksTheSenderAndArchivesTheChat() {
        vm.submit(listOf(dad, mom), cached = false, firstRun = false)
        awaitUntil { rowIds().size == 2 }
        vm.startSelect(dad.threadId)
        awaitUntil { vm.state.value.selection.canReportSpam }

        vm.reportSpamSelected()
        awaitUntil { events.any { it is ConversationsEvent.ReportedSpam } }
        val event = events.single { it is ConversationsEvent.ReportedSpam } as ConversationsEvent.ReportedSpam
        assertThat(event.archived).isTrue()
        assertThat(rowIds()).containsExactly(mom.threadId)
        val repository = SpamRepositoryProvider.get(app)
        assertThat(SenderBlocking.isBlocked(rules(repository), dad.phoneNumber)).isTrue()

        vm.undoReportSpam(event.token)
        awaitUntil { !SenderBlocking.isBlocked(rules(repository), dad.phoneNumber) }
    }

    /** MC-23: Block keeps the chat in the list, adds a uMessage rule and offers Undo, which removes the rule. */
    @Test
    fun blockKeepsTheChatAndCanBeUndone() {
        vm.submit(listOf(dad, mom), cached = false, firstRun = false)
        awaitUntil { rowIds().size == 2 }
        vm.startSelect(mom.threadId)
        awaitUntil { vm.state.value.selection.count == 1 }

        vm.blockSelected()
        awaitUntil { events.any { it is ConversationsEvent.Blocked } }
        val event = events.single { it is ConversationsEvent.Blocked } as ConversationsEvent.Blocked
        assertThat(rowIds()).containsExactly(dad.threadId, mom.threadId)
        val repository = SpamRepositoryProvider.get(app)
        assertThat(SenderBlocking.isBlocked(rules(repository), mom.phoneNumber)).isTrue()

        vm.undoReportSpam(event.token)
        awaitUntil { !SenderBlocking.isBlocked(rules(repository), mom.phoneNumber) }
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

    private fun rowIds() = vm.state.value.rows.map { it.threadId }

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
        val dad = conversation(1, "Dad", date = 200)
        val mom = conversation(2, "Mom", date = 100)

        fun conversation(id: Long, title: String, date: Int) = Conversation(
            threadId = id, snippet = "hi", date = date, read = true, title = title, photoUri = "",
            isGroupConversation = false, phoneNumber = "555010$id",
        )
    }
}
