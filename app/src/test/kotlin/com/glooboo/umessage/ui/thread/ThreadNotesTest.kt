package com.glooboo.umessage.ui.thread

import android.provider.Telephony
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.R
import com.glooboo.umessage.helpers.MessageNoteKey
import com.glooboo.umessage.models.Message
import com.glooboo.umessage.models.MessageNote
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/** The notes of the open chat: one observed map, the edit state, the selection row and the save and delete calls. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class ThreadNotesTest {

    /** Notes by chat, kept in memory; every change shows in the observed map at once. */
    private class FakeStore : NoteStore {
        val all = MutableStateFlow<Map<String, MessageNote>>(emptyMap())
        var prepared = 0

        override fun observeThread(threadId: Long): Flow<Map<String, MessageNote>> =
            all.map { map -> map.filterValues { it.threadId == threadId } }

        override suspend fun prepare() {
            prepared++
        }

        override suspend fun save(message: Message, text: String) {
            val key = MessageNoteKey.of(message)
            all.value = all.value + (key to MessageNote(key, message.threadId, message.date, text, 1, 1))
        }

        override suspend fun delete(message: Message) {
            all.value = all.value - MessageNoteKey.of(message)
        }
    }

    private fun message(
        id: Long,
        body: String = "Paid 12.50 at the shop",
        thread: Long = 7,
        scheduled: Boolean = false,
    ) = Message(
        id = id, body = body, type = Telephony.Sms.MESSAGE_TYPE_INBOX, status = 0, participants = ArrayList(),
        date = 1_700_000_000 + id.toInt(), read = true, threadId = thread, isMMS = false, attachment = null,
        senderPhoneNumber = "+15550001234", senderName = "", senderPhotoUri = "", subscriptionId = 0,
        isScheduled = scheduled,
    )

    private fun note(message: Message, text: String): MessageNote {
        val key = MessageNoteKey.of(message)
        return MessageNote(key, message.threadId, message.date, text, 1, 1)
    }

    private val job = Job()
    private val scope = CoroutineScope(UnconfinedTestDispatcher() + job)

    @After
    fun tearDown() = job.cancel()

    private fun attached(store: FakeStore, thread: Long = 7): ThreadNotes =
        ThreadNotes().also {
            it.follow(thread)
            it.attach(store, scope)
        }

    @Test
    fun theStateHoldsTheNotesOfTheOpenChatOnly() = runBlocking<Unit> {
        val store = FakeStore()
        val mine = message(1)
        val other = message(2, thread = 8)
        store.all.value = mapOf(
            MessageNoteKey.of(mine) to note(mine, "rent"),
            MessageNoteKey.of(other) to note(other, "fuel"),
        )

        val notes = attached(store)

        assertThat(store.prepared).isEqualTo(1)
        assertThat(notes.noteOf(mine)?.text).isEqualTo("rent")
        assertThat(notes.noteOf(other)).isNull()
        assertThat(notes.byKey).hasSize(1)
    }

    @Test
    fun aNoteSavedLaterAppearsAndAChangeOfChatSwitchesTheMap() = runBlocking<Unit> {
        val store = FakeStore()
        val first = message(1)
        val second = message(2, thread = 8)
        val notes = attached(store)
        assertThat(notes.byKey).isEmpty()

        store.save(first, "rent")
        store.save(second, "fuel")
        assertThat(notes.noteOf(first)?.text).isEqualTo("rent")

        notes.follow(8)
        assertThat(notes.noteOf(first)).isNull()
        assertThat(notes.noteOf(second)?.text).isEqualTo("fuel")
    }

    @Test
    fun theViewModelTellsTheNotesWhichChatIsOpen() = runBlocking<Unit> {
        val store = FakeStore()
        val vm = ThreadViewModel(SavedStateHandle())
        vm.notes.attach(store, scope)
        val first = message(1)
        store.save(first, "rent")
        assertThat(vm.notes.noteOf(first)).isNull()

        vm.threadId = 7

        assertThat(vm.threadId).isEqualTo(7)
        assertThat(vm.notes.noteOf(first)?.text).isEqualTo("rent")
    }

    @Test
    fun savingStoresTheNoteAndBlankTextDeletesIt() = runBlocking<Unit> {
        val store = FakeStore()
        val notes = attached(store)
        val m = message(1)

        notes.startEdit(m)
        assertThat(notes.editing).isEqualTo(m)
        notes.save("  rent for May  ")
        assertThat(notes.editing).isNull()
        assertThat(notes.noteOf(m)?.text).isEqualTo("  rent for May  ")

        notes.startEdit(m)
        notes.save("   ")
        assertThat(notes.noteOf(m)).isNull()
    }

    @Test
    fun deleteRemovesTheNoteAndCancelKeepsIt() = runBlocking<Unit> {
        val store = FakeStore()
        val notes = attached(store)
        val m = message(1)
        store.save(m, "rent")

        notes.startEdit(m)
        notes.dismiss()
        assertThat(notes.editing).isNull()
        assertThat(notes.noteOf(m)).isNotNull()

        notes.startEdit(m)
        notes.delete()
        assertThat(notes.noteOf(m)).isNull()
    }

    @Test
    fun aScheduledMessageCannotBeEdited() = runBlocking<Unit> {
        val notes = attached(FakeStore())

        notes.startEdit(message(1, scheduled = true))

        assertThat(notes.editing).isNull()
    }

    @Test
    fun theSelectionRowIsOfferedForOneMessageOnly() {
        val one = message(1)
        val two = message(2)
        val scheduled = message(3, scheduled = true)
        val withNote = mapOf(MessageNoteKey.of(one) to note(one, "rent"))

        assertThat(noteMenuLabel(listOf(one), emptyMap())).isEqualTo(R.string.add_note)
        assertThat(noteMenuLabel(listOf(one), withNote)).isEqualTo(R.string.edit_note)
        assertThat(noteMenuLabel(listOf(two), withNote)).isEqualTo(R.string.add_note)
        assertThat(noteMenuLabel(listOf(one, two), withNote)).isNull()
        assertThat(noteMenuLabel(listOf(scheduled), emptyMap())).isNull()
        assertThat(noteMenuLabel(emptyList(), emptyMap())).isNull()
    }

    @Test
    fun theMenuListsTheNoteActionOnlyWhenItHasALabel() {
        fun entries(noteLabel: Int?) = selectionEntries(
            hasText = true, single = true, canSave = false, readAloudOk = false, readAloudLabel = 0,
            isRecycleBin = false, noteLabel = noteLabel,
        )

        assertThat(entries(null).main.map { it.action }).doesNotContain(SelectionAction.Note)
        val add = entries(R.string.add_note).main.single { it.action == SelectionAction.Note }
        assertThat(add.label).isEqualTo(R.string.add_note)
        val edit = entries(R.string.edit_note).main.single { it.action == SelectionAction.Note }
        assertThat(edit.label).isEqualTo(R.string.edit_note)
    }
}
