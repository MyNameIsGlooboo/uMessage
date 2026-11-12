package com.glooboo.umessage.ui.thread

import android.net.Uri
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.glooboo.umessage.models.Attachment
import com.glooboo.umessage.messaging.RepeatRule
import com.glooboo.umessage.models.AttachmentSelection
import com.glooboo.umessage.models.Message
import com.glooboo.umessage.models.MessageKey
import com.glooboo.umessage.models.SIMCard
import com.glooboo.umessage.models.ThreadItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.fossify.commons.models.SimpleContact

/** Which entries of the top bar overflow menu apply to the current conversation. */
data class ThreadMenuState(
    val delete: Boolean = false,
    val restore: Boolean = false,
    val archive: Boolean = false,
    val unarchive: Boolean = false,
    val rename: Boolean = false,
    val details: Boolean = false,
    val block: Boolean = false,
    val unblock: Boolean = false,
    val dial: Boolean = false,
    val managePeople: Boolean = false,
    val markUnread: Boolean = false,
    val addToContact: Boolean = false,
    val copyNumber: Boolean = false,
    val reportSpam: Boolean = false,
    /** TH-11: "Always allow this sender" (an allow rule), offered while no allow rule names the single sender. */
    val allowSender: Boolean = false,
    /** TH-11: "Why was this decided?" opens the decision log; offered when a rule decides for the single sender. */
    val whyDecided: Boolean = false,
    /** TH-20: "Search in conversation". */
    val search: Boolean = false,
    /** TH-11, TH-20: "Always forward from this sender" opens the forward rule editor with the sender filled in. */
    val alwaysForward: Boolean = false,
    /** I38: "Share conversation as text". */
    val shareAsText: Boolean = false,
    /** N01: "Export conversation" as a PDF or a text file. */
    val exportConversation: Boolean = false,
    /** I48: "Add to home screen", offered where the launcher can pin a shortcut. */
    val addToHomeScreen: Boolean = false,
    /** I29: "Labels" opens the label picker for this conversation. */
    val labels: Boolean = false,
    /** N05: "Mute" asks how long, "Unmute" ends the mute. */
    val mute: Boolean = false,
    val unmute: Boolean = false,
    /** B06: "Hide this chat" behind the app lock, and "Unhide". */
    val hide: Boolean = false,
    val unhide: Boolean = false,
)

/** TH-12: how a uMessage rule treats the conversation's single sender, shown as a chip in the header. */
enum class SenderStatus { None, Allowed, Blocked }

/** A participant shown as a chip in the "add people" panel. */
data class PersonChip(val rawId: Int, val name: String, val removable: Boolean)

sealed interface ScrollCommand {
    data object ToBottom : ScrollCommand
    /** [flash]: also tint the bubble for a moment (a jump from Starred, search or notes, not the in-chat search). */
    data class ToMessage(val message: MessageKey, val flash: Boolean = false) : ScrollCommand

    /** I26: show the "Unread messages" line near the top when the unread ones fill more than the screen. */
    data object ToUnread : ScrollCommand
}

/**
 * Everything the thread screen draws, as Compose snapshot state. The activity owns the SMS/MMS plumbing
 * (loading, sending, scheduling) and pushes results in here; the UI only reads this and calls back through
 * [ThreadActions]. Surviving in a [ViewModel] keeps the typed text and picked attachments across recreation.
 */
class ThreadViewModel(private val saved: SavedStateHandle = SavedStateHandle(emptyMap())) : ViewModel() {
    // region Scheduling and saving: kept in [saved] so they survive rotation and the process being killed
    /**
     * When the message in the composer is to be sent, or null when Send sends it now. The activity used to keep
     * this itself, so after a rotation the banner still showed a time but Send sent immediately.
     */
    var scheduledMillis: Long?
        get() = saved[KEY_SCHEDULED_MILLIS]
        set(value) {
            saved[KEY_SCHEDULED_MILLIS] = value
        }

    /** The scheduled message being edited (its id is reused when it is saved again), or null for a new one. */
    var scheduledMessageId: Long?
        get() = saved[KEY_SCHEDULED_MESSAGE_ID]
        set(value) {
            saved[KEY_SCHEDULED_MESSAGE_ID] = value
        }

    /** How the scheduled message in the composer repeats: a RepeatRule name, null for never. */
    var scheduledRepeat: String?
        get() = saved[KEY_SCHEDULED_REPEAT]
        set(value) {
            saved[KEY_SCHEDULED_REPEAT] = value
        }

    /** Where the camera writes the photo being taken; the camera often outlives this process on low-memory phones. */
    var capturedImageUri: Uri?
        get() = saved[KEY_CAPTURED_IMAGE_URI]
        set(value) {
            saved[KEY_CAPTURED_IMAGE_URI] = value
        }

    /**
     * I26: the message the "Unread messages" line sits above, taken once when the thread opens (before the messages
     * are marked read) and kept until the user sends. Null: no line.
     */
    @Volatile
    var firstUnreadId: Long? = null

    /** True once [firstUnreadId] was decided for this opening, with or without a result. */
    @Volatile
    var unreadResolved = false

    /** True once the list was asked to scroll to the unread line, so a refresh does not scroll again. */
    var unreadScrolled = false

    val isScheduling: Boolean get() = scheduledMillis != null

    init {
        // A new view model with a saved edit id means the process was killed: the picked attachments (not saved) are
        // gone, and saving under the old id would drop the message's attachments and delete their copies. The edit
        // becomes a new scheduled message instead; the original stays as it was.
        if (scheduledMessageId != null) scheduledMessageId = null
    }

    fun clearSchedule() {
        scheduledMillis = null
        scheduledMessageId = null
        scheduledRepeat = null
        scheduledLabel = null
    }

    /** MMS attachments waiting for the user to pick where to save them (the picker can outlive the activity). */
    var attachmentsToSave: List<Attachment>?
        get() {
            val uris = saved.get<ArrayList<String>>(KEY_SAVE_URIS) ?: return null
            val types = saved.get<ArrayList<String>>(KEY_SAVE_TYPES).orEmpty()
            val names = saved.get<ArrayList<String>>(KEY_SAVE_NAMES).orEmpty()
            return uris.mapIndexed { i, uri ->
                Attachment(null, -1L, uri, types.getOrElse(i) { "" }, 0, 0, names.getOrElse(i) { "" })
            }
        }
        set(value) {
            saved[KEY_SAVE_URIS] = value?.mapTo(ArrayList()) { it.uriString }
            saved[KEY_SAVE_TYPES] = value?.mapTo(ArrayList()) { it.mimetype }
            saved[KEY_SAVE_NAMES] = value?.mapTo(ArrayList()) { it.filename }
        }
    // endregion


    // region Conversation
    var items by mutableStateOf<List<ThreadItem>>(emptyList())
        private set
    var isGroup by mutableStateOf(false)

    /** Q12: the sent group message whose delivery details are open, or null. */
    var deliveryDetailsFor by mutableStateOf<MessageKey?>(null)
    var loaded by mutableStateOf(false)

    /** I49: the message being read aloud (its [Message.getStableId]), or null. */
    var readingAloudId by mutableStateOf<Long?>(null)

    /** I30: the iPhone reactions on each message, by [Message.getStableId]. */
    var reactions by mutableStateOf<Map<Long, List<TapbackReaction>>>(emptyMap())
    var title by mutableStateOf("")

    /** Contact photo for the top bar avatar; empty shows initials, the person icon or the group icon instead. */
    var avatarPhotoUri by mutableStateOf("")
    var subtitle by mutableStateOf<String?>(null)
    var isRecycleBin by mutableStateOf(false)
    var menu by mutableStateOf(ThreadMenuState())

    /** The conversation on screen, so the title can show its labels; 0 until it is known. */
    var threadId: Long
        get() = threadIdState
        set(value) {
            threadIdState = value
            notes.follow(value)
        }
    private var threadIdState by mutableLongStateOf(0L)

    /** I29: the thread whose label picker is open; null while it is closed. */
    var labelsFor by mutableStateOf<Long?>(null)
    var senderStatus by mutableStateOf(SenderStatus.None)

    /**
     * MC-13: for a group (two or more different numbers), whether a message goes as one group MMS (true) or as
     * separate texts (false); null for one person, or while the user has not chosen yet (Send asks first).
     */
    var groupSendMode by mutableStateOf<Boolean?>(null)

    /** Scheduled messages whose alarm is inexact: their bubble says the time is approximate (RM-27). */
    var approximateScheduledIds by mutableStateOf<Set<Long>>(emptySet())

    /** B04: how each repeating scheduled message repeats, by message id. */
    var scheduledRepeatRules by mutableStateOf<Map<Long, RepeatRule>>(emptyMap())

    /** Chat rows, newest first: the list is laid out reversed so it stays anchored to the newest message. */
    val rows: List<ThreadRow> by derivedStateOf { buildThreadRows(items, isGroup).asReversed() }
    // endregion

    // region Composer
    val text = TextFieldState()
    var attachments by mutableStateOf<List<AttachmentSelection>>(emptyList())
        private set
    var scheduledLabel by mutableStateOf<String?>(null)
    var isMms by mutableStateOf(false)
    /** How the typed text splits into SMS parts, or null before anything was typed. */
    var segments by mutableStateOf<SmsSegments?>(null)

    /** The "show character counter" setting: the counter is always shown while typing. */
    var showCounter by mutableStateOf(false)

    /** True once this screen has said that the long text goes as MMS; the note is shown once, not on every key. */
    var longMmsNoted = false

    var sendOnEnter by mutableStateOf(false)
    var replyDisabled by mutableStateOf(false)
    var sims by mutableStateOf<List<SIMCard>>(emptyList())
    var simIndex by mutableIntStateOf(0)
    var showAttachSheet by mutableStateOf(false)

    /** Bumped when the media permission prompt of the recent photos row was answered. */
    var photoAccessVersion by mutableIntStateOf(0)

    /** The saved texts sheet (N06) is open. */
    var showSavedTexts by mutableStateOf(false)

    /** I40: a microphone button stands where Send is while the field is empty. */
    var voiceButton by mutableStateOf(false)

    /** The emoji panel takes the keyboard's place under the composer. */
    var showEmojiPanel by mutableStateOf(false)

    /**
     * True when the only participant is a saved contact. The link sheet then leaves out the line asking to check the
     * address first (TU-29); false until the activity has checked.
     */
    var linksTrusted by mutableStateOf(false)

    /** A tapped link shown in the link sheet (TH-05). Not saved: after a rotation the user simply taps again. */
    var pendingLink by mutableStateOf<String?>(null)

    /** Bumped by the activity to ask the text field for focus (new conversations open the keyboard). */
    var focusInput by mutableIntStateOf(0)

    /**
     * Send is on for real text or at least one attachment, and never while a photo is still being shrunk: sending
     * then went out with the full-size original (TU-05). Text of only spaces or line breaks does not count (TU-04).
     */
    val canSend: Boolean by derivedStateOf {
        attachments.none { it.isPending } && (text.text.isNotBlank() || attachments.isNotEmpty())
    }
    // endregion

    // region Add people
    var showPeople by mutableStateOf(false)
    var people by mutableStateOf<List<PersonChip>>(emptyList())
    var contacts by mutableStateOf<List<SimpleContact>>(emptyList())
    val peopleQuery = TextFieldState()
    // endregion

    // region Search in conversation (TH-20)
    var searchOpen by mutableStateOf(false)
        private set
    var searchQuery by mutableStateOf("")
        private set

    /** Which of [searchMatches] is shown, 0 being the newest. */
    var searchIndex by mutableIntStateOf(0)
        private set

    /** Matches found in the database among messages that are not loaded yet; see [searchOlder]. */
    private var olderHits by mutableStateOf<List<SearchHit>>(emptyList())

    /** True while the database is searched for older messages: the search bar says so. */
    var searchingOlder by mutableStateOf(false)
        private set

    /**
     * Set by the activity: the matches for a query among all messages of the thread, read off the main thread (the
     * list holds only the newest page and the pages scrolled in). Null: only loaded messages are searched.
     */
    var olderSearch: ((threadId: Long, query: String) -> List<SearchHit>)? = null

    /** Set by the activity: loads history up to a message that is not loaded yet, then scrolls to it. */
    var loadAndScrollTo: ((MessageKey) -> Unit)? = null

    private var searchJob: Job? = null

    /** The messages whose text holds [searchQuery], loaded or not, newest first. */
    val searchMatches: List<MessageKey> by derivedStateOf {
        if (searchQuery.isBlank()) {
            emptyList()
        } else {
            val loaded = messages().filter { matchesSearch(it.body, searchQuery) }.map { SearchHit(it.key, it.date) }
            mergeSearchHits(loaded.asReversed(), olderHits)
        }
    }

    /** What the bubbles highlight: the query while the search bar is open, else nothing. */
    val activeSearchQuery: String get() = if (searchOpen) searchQuery else ""

    /** The match the list shows and outlines, or null. */
    val currentSearchMatch: MessageKey? get() = searchMatches.getOrNull(searchIndex)

    fun openSearch() {
        clearSelection()
        searchOpen = true
    }

    fun closeSearch() {
        searchJob?.cancel()
        searchingOlder = false
        olderHits = emptyList()
        searchOpen = false
        searchQuery = ""
        searchIndex = 0
    }

    fun updateSearch(query: String) {
        searchQuery = query
        searchIndex = 0
        searchOlder(query)
        currentSearchMatch?.let(::showSearchMatch)
    }

    /** Looks for [query] in the messages that are not loaded yet, after a short pause in typing. */
    @Suppress("TooGenericExceptionCaught", "SwallowedException") // A failed search just shows no older hits.
    private fun searchOlder(query: String) {
        searchJob?.cancel()
        olderHits = emptyList()
        val search = olderSearch
        val nothingToSearch = isRecycleBin || threadId == 0L
        if (query.isBlank() || search == null || nothingToSearch) {
            searchingOlder = false
            return
        }
        searchingOlder = true
        val thread = threadId
        searchJob = viewModelScope.launch {
            delay(SEARCH_PAUSE_MS)
            val hits = try {
                withContext(Dispatchers.IO) { search(thread, query) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
            if (searchQuery != query) return@launch
            val before = currentSearchMatch
            olderHits = hits
            searchingOlder = false
            // Nothing was loaded to show: go to the newest match that was found only now.
            if (before == null) currentSearchMatch?.let(::showSearchMatch)
        }
    }

    /** Scrolls to [message], first loading the history up to it when it is not among the loaded messages. */
    private fun showSearchMatch(message: MessageKey) {
        if (messages().any { it.key == message }) scrollToMessage(message) else loadAndScrollTo?.invoke(message)
    }

    fun showOlderMatch() = moveSearch(olderMatch(searchIndex, searchMatches.size))

    fun showNewerMatch() = moveSearch(newerMatch(searchIndex, searchMatches.size))

    private fun moveSearch(index: Int) {
        searchIndex = index
        currentSearchMatch?.let(::showSearchMatch)
    }
    // endregion

    // region Selection
    var selectedIds by mutableStateOf<Set<Long>>(emptySet())
        private set

    val isSelecting: Boolean get() = selectedIds.isNotEmpty()

    val selectedMessages: List<Message> by derivedStateOf {
        if (selectedIds.isEmpty()) emptyList() else messages().filter { it.getStableId() in selectedIds }
    }
    // endregion

    /** I13: which messages are starred. */
    val stars = ThreadStars()

    /** The private notes of the open chat, and the note being edited. */
    val notes = ThreadNotes()

    /** I04: the "Not in your contacts" card. */
    val unknownCard = UnknownCardState()

    /** Snackbars shown by the thread screen (for example after "Report spam"). */
    val snackbar = SnackbarHostState()

    private val _scroll = MutableSharedFlow<ScrollCommand>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val scroll: SharedFlow<ScrollCommand> = _scroll.asSharedFlow()

    private fun messages(): List<Message> = items.filterIsInstance<Message>()

    fun publishItems(newItems: List<ThreadItem>) {
        items = newItems
        if (selectedIds.isNotEmpty()) {
            val present = messages().mapTo(HashSet()) { it.getStableId() }
            val kept = selectedIds.filterTo(HashSet()) { it in present }
            if (kept != selectedIds) selectedIds = kept
        }
    }

    /** Puts [value] (an emoji from the panel) in place of the selection and moves the cursor after it. */
    fun insertAtCursor(value: String) {
        text.edit {
            val start = selection.min
            replace(start, selection.max, value)
            selection = TextRange(start + value.length)
        }
    }

    fun scrollToBottom() {
        _scroll.tryEmit(ScrollCommand.ToBottom)
    }

    fun scrollToUnread() {
        _scroll.tryEmit(ScrollCommand.ToUnread)
    }

    fun scrollToMessage(message: MessageKey, flash: Boolean = false) {
        _scroll.tryEmit(ScrollCommand.ToMessage(message, flash))
    }

    /** The message that was just jumped to; its bubble shows a short tint (see [ThreadFlash]). */
    var flashedMessage by mutableStateOf<MessageKey?>(null)
        private set

    fun flashMessage(message: MessageKey?) {
        flashedMessage = message
    }

    fun toggleSelect(message: Message) {
        val id = message.getStableId()
        selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id
    }

    fun selectAll() {
        selectedIds = messages().mapTo(HashSet()) { it.getStableId() }
    }

    fun clearSelection() {
        if (selectedIds.isNotEmpty()) selectedIds = emptySet()
    }

    // region Attachments
    fun hasAttachment(id: String) = attachments.any { it.id == id }

    fun addAttachment(attachment: AttachmentSelection) {
        attachments = attachments.filterNot { AttachmentSelection.areItemsTheSame(it, attachment) } + attachment
    }

    fun replaceAttachment(id: String, replacement: AttachmentSelection) {
        attachments = attachments.map { if (it.id == id) replacement else it }
    }

    fun removeAttachment(id: String) {
        attachments = attachments.filterNot { it.id == id }
    }

    fun clearAttachments() {
        attachments = emptyList()
    }
    // endregion

    private companion object {
        const val SEARCH_PAUSE_MS = 250L
        const val KEY_SCHEDULED_MILLIS = "scheduled_millis"
        const val KEY_SCHEDULED_MESSAGE_ID = "scheduled_message_id"
        const val KEY_SCHEDULED_REPEAT = "scheduled_repeat"
        const val KEY_CAPTURED_IMAGE_URI = "captured_image_uri"
        const val KEY_SAVE_URIS = "save_attachment_uris"
        const val KEY_SAVE_TYPES = "save_attachment_types"
        const val KEY_SAVE_NAMES = "save_attachment_names"
    }
}
