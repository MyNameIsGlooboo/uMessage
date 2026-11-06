package com.glooboo.umessage.ui.thread

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.glooboo.umessage.R
import com.glooboo.umessage.helpers.MessageNoteKey
import com.glooboo.umessage.helpers.MessageNotes
import com.glooboo.umessage.models.Message
import com.glooboo.umessage.models.MessageNote
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/** What the thread screen needs from the notes storage; [MessageNotes] is the real one, tests bring a fake. */
interface NoteStore {
    /** The notes of one chat by note key, kept up to date. */
    fun observeThread(threadId: Long): Flow<Map<String, MessageNote>>

    /** Binds notes restored from a backup to their chats; called once before observing. */
    suspend fun prepare()

    suspend fun save(message: Message, text: String)

    suspend fun delete(message: Message)
}

/** The [NoteStore] over the app's [MessageNotes]. */
class MessageNoteStore(private val notes: MessageNotes) : NoteStore {
    override fun observeThread(threadId: Long): Flow<Map<String, MessageNote>> = notes.observeThread(threadId)

    override suspend fun prepare() {
        notes.relink()
    }

    override suspend fun save(message: Message, text: String) {
        notes.setNote(message, text)
    }

    override suspend fun delete(message: Message) {
        notes.deleteNote(message)
    }
}

/** A scheduled message has no stable identity yet, so it takes no note. Only one message can be noted at a time. */
fun canNote(selected: List<Message>): Boolean = selected.size == 1 && !selected.first().isScheduled

/**
 * The label of the selection menu's note row: "Add note" or "Edit note", or null when the row is not offered
 * (no message, several messages, a scheduled one).
 */
fun noteMenuLabel(selected: List<Message>, notes: Map<String, MessageNote>): Int? = when {
    !canNote(selected) -> null
    notes.isNotEmpty() && notes.containsKey(MessageNoteKey.of(selected.first())) -> R.string.edit_note
    else -> R.string.add_note
}

/**
 * The private notes of the open chat: one map for the whole screen (observed once, not one flow per bubble) and
 * the message whose note is being edited. The activity calls [attach] once; [follow] says which chat is open.
 */
class ThreadNotes {
    /** The notes of the open chat by note key (see [MessageNoteKey]). */
    var byKey by mutableStateOf<Map<String, MessageNote>>(emptyMap())
        private set

    /** The message whose note the edit dialog shows; null while it is closed. */
    var editing by mutableStateOf<Message?>(null)
        private set

    private val threadId = MutableStateFlow(0L)
    private var store: NoteStore? = null
    private var scope: CoroutineScope? = null
    private var job: Job? = null

    /** The chat on screen; 0 while it is not known. A change switches the observed notes. */
    fun follow(id: Long) {
        threadId.value = id
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun attach(store: NoteStore, scope: CoroutineScope) {
        this.store = store
        this.scope = scope
        job?.cancel()
        job = scope.launch {
            runCatching { store.prepare() }
            threadId
                .flatMapLatest { id ->
                    if (id == 0L) flowOf(emptyMap<String, MessageNote>()) else store.observeThread(id)
                }
                .catch { }
                .collect { byKey = it }
        }
    }

    /** The note of [message], or null. Cheap when the chat has no notes: no key is built then. */
    fun noteOf(message: Message): MessageNote? =
        if (byKey.isEmpty()) null else byKey[MessageNoteKey.of(message)]

    fun startEdit(message: Message) {
        if (canNote(listOf(message))) editing = message
    }

    fun dismiss() {
        editing = null
    }

    /** Saves [text] as the note of the message being edited; blank text deletes the note. */
    fun save(text: String) {
        val message = editing ?: return
        editing = null
        val target = store ?: return
        scope?.launch {
            if (text.isBlank()) target.delete(message) else target.save(message, text)
        }
    }

    /** Deletes the note of the message being edited. */
    fun delete() {
        val message = editing ?: return
        editing = null
        val target = store ?: return
        scope?.launch { target.delete(message) }
    }
}
