package com.glooboo.umessage.activities

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.Telephony
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.activity.viewModels
import androidx.annotation.StringRes
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material3.SnackbarDuration
import androidx.compose.runtime.snapshotFlow
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.fossify.commons.extensions.appLockManager
import org.fossify.commons.extensions.getMyContactsCursor
import org.fossify.commons.extensions.hideKeyboard
import org.fossify.commons.extensions.normalizeString
import org.fossify.commons.extensions.notificationManager
import org.fossify.commons.extensions.showErrorToast
import org.fossify.commons.extensions.toast
import org.fossify.commons.helpers.AppLockManager
import org.fossify.commons.helpers.MyContactsContentProvider
import org.fossify.commons.helpers.PERMISSION_READ_PHONE_STATE
import org.fossify.commons.helpers.ensureBackgroundThread
import com.glooboo.umessage.ui.components.ChoiceItem
import com.glooboo.umessage.ui.components.showChoiceDialog
import org.fossify.commons.models.SimpleContact
import com.glooboo.umessage.R
import com.glooboo.umessage.dialogs.GroupMessageSendDialog
import com.glooboo.umessage.dialogs.InvalidNumberDialog
import com.glooboo.umessage.extensions.config
import com.glooboo.umessage.extensions.conversationsDB
import com.glooboo.umessage.extensions.deleteSmsDraft
import com.glooboo.umessage.extensions.getContactFromAddress
import com.glooboo.umessage.extensions.getSmsDraft
import com.glooboo.umessage.extensions.getThreadId
import com.glooboo.umessage.extensions.getThreadParticipants
import com.glooboo.umessage.extensions.isSavedContactNumber
import com.glooboo.umessage.extensions.restoreMessageFromRecycleBin
import com.glooboo.umessage.extensions.updateLastConversationMessage
import com.glooboo.umessage.extensions.getThreadTitle
import com.glooboo.umessage.extensions.launchConversationDetails
import com.glooboo.umessage.extensions.launchViewIntent
import com.glooboo.umessage.extensions.markMessageRead
import com.glooboo.umessage.extensions.markThreadMessagesRead
import com.glooboo.umessage.extensions.messagesDB
import com.glooboo.umessage.extensions.removeDiacriticsIfNeeded
import com.glooboo.umessage.extensions.saveSmsDraft
import com.glooboo.umessage.extensions.shouldUnarchive
import com.glooboo.umessage.extensions.startContactDetailsIntent
import com.glooboo.umessage.extensions.subscriptionManagerCompat
import com.glooboo.umessage.extensions.toArrayList
import com.glooboo.umessage.extensions.toSortedMessages
import com.glooboo.umessage.extensions.updateConversationArchivedStatus
import com.glooboo.umessage.extensions.updateScheduledMessagesThreadId
import com.glooboo.umessage.helpers.AudioPlayerManager
import com.glooboo.umessage.helpers.EXTRA_VCARD_URI
import com.glooboo.umessage.helpers.IS_LAUNCHED_FROM_SHORTCUT
import com.glooboo.umessage.helpers.IS_RECYCLE_BIN
import com.glooboo.umessage.helpers.SEARCHED_MESSAGE_ID
import com.glooboo.umessage.helpers.messageNotes
import com.glooboo.umessage.helpers.SEARCHED_MESSAGE_IS_MMS
import com.glooboo.umessage.helpers.THREAD_ATTACHMENT_URI
import com.glooboo.umessage.helpers.THREAD_ATTACHMENT_URIS
import com.glooboo.umessage.helpers.THREAD_ID
import com.glooboo.umessage.helpers.THREAD_NUMBER
import com.glooboo.umessage.helpers.w3cPrefs
import com.glooboo.umessage.helpers.THREAD_TEXT
import com.glooboo.umessage.helpers.THREAD_TITLE
import com.glooboo.umessage.helpers.ChatArrival
import com.glooboo.umessage.helpers.ChatSounds
import com.glooboo.umessage.helpers.LocationSharer
import com.glooboo.umessage.helpers.cancelFailedSendNotification
import com.glooboo.umessage.helpers.composerPrefs
import com.glooboo.umessage.helpers.generateRandomId
import com.glooboo.umessage.helpers.refreshConversations
import com.glooboo.umessage.helpers.refreshMessages
import com.glooboo.umessage.messaging.ScheduledSendClaims
import com.glooboo.umessage.messaging.MmsRetryResult
import com.glooboo.umessage.messaging.SendOutcome
import com.glooboo.umessage.messaging.downloadMms
import com.glooboo.umessage.messaging.SmsFailureReason
import com.glooboo.umessage.messaging.undownloadedMms
import com.glooboo.umessage.messaging.isLongMmsMessage
import com.glooboo.umessage.messaging.scheduledAttachmentTracker
import com.glooboo.umessage.messaging.withUnsentOnly
import com.glooboo.umessage.models.Attachment
import com.glooboo.umessage.models.Conversation
import com.glooboo.umessage.models.Events
import com.glooboo.umessage.models.Message
import com.glooboo.umessage.models.MessageKey
import com.glooboo.umessage.models.ThreadItem
import com.glooboo.umessage.ui.thread.IncomingShareGuard
import com.glooboo.umessage.ui.thread.MessageNoteStore
import com.glooboo.umessage.ui.thread.AttachKind
import com.glooboo.umessage.ui.thread.MediaSizes
import com.glooboo.umessage.ui.thread.SearchHit
import com.glooboo.umessage.ui.thread.ThreadLookupCache
import com.glooboo.umessage.ui.thread.matchesSearch
import com.glooboo.umessage.ui.thread.messagesToCache
import com.glooboo.umessage.ui.thread.ThreadExportController
import com.glooboo.umessage.ui.thread.DraftText
import com.glooboo.umessage.ui.thread.SendGuard
import com.glooboo.umessage.ui.thread.SendSteps
import com.glooboo.umessage.ui.thread.adoptScheduledMessages
import com.glooboo.umessage.ui.thread.refreshRepeatRules
import com.glooboo.umessage.ui.thread.simHeaderLabels
import com.glooboo.umessage.ui.thread.SmsSegments
import com.glooboo.umessage.ui.thread.ThreadActions
import com.glooboo.umessage.ui.thread.ThreadLaunchHints
import com.glooboo.umessage.ui.thread.ThreadMenuAction
import com.glooboo.umessage.ui.thread.SenderStatus
import com.glooboo.umessage.ui.log.DecisionLogActivity
import com.glooboo.umessage.spam.ForwardingAvailability
import com.glooboo.umessage.spam.data.BlockedNotifier
import com.glooboo.umessage.ui.spam.ForwardingActivity
import com.glooboo.umessage.ui.thread.UnknownCardController
import com.glooboo.umessage.ui.thread.ContextThreadMessageSource
import com.glooboo.umessage.ui.thread.ThreadMessagesRepository
import com.glooboo.umessage.ui.thread.draftToSave
import com.glooboo.umessage.ui.thread.mergeRefreshedMessages
import com.glooboo.umessage.ui.thread.saveFileName
import com.glooboo.umessage.ui.thread.ThreadScreen
import com.glooboo.umessage.ui.thread.ThreadViewModel
import com.glooboo.umessage.ui.thread.removeThreadMessages
import com.glooboo.umessage.ui.thread.ThreadScheduleUi
import com.glooboo.umessage.ui.thread.ThreadSimController
import com.glooboo.umessage.ui.thread.ThreadConversationActions
import com.glooboo.umessage.ui.thread.ThreadPeopleController
import com.glooboo.umessage.ui.thread.ThreadSelectionActions
import com.glooboo.umessage.ui.thread.ThreadSenderActions
import com.glooboo.umessage.ui.thread.ThreadAttachmentController
import com.glooboo.umessage.ui.thread.MessageSignature
import com.glooboo.umessage.ui.thread.ThreadLocationController
import com.glooboo.umessage.ui.thread.isNumericShortCode
import com.glooboo.umessage.ui.thread.RecentPhotosAccess
import com.glooboo.umessage.ui.thread.ContextMessageSender
import com.glooboo.umessage.ui.thread.OutgoingMessage
import com.glooboo.umessage.ui.thread.ResendTracker
import com.glooboo.umessage.messaging.RepeatRule
import com.glooboo.umessage.ui.thread.ThreadScheduleController
import com.glooboo.umessage.ui.thread.ThreadSendController
import com.glooboo.umessage.ui.thread.buildScheduledMessage
import com.glooboo.umessage.ui.thread.isSchedulableTime
import com.glooboo.umessage.ui.thread.ThreadMenuInput
import com.glooboo.umessage.ui.thread.ThreadPrivacyActions
import com.glooboo.umessage.ui.lock.HiddenChatGate
import com.glooboo.umessage.ui.lock.LockedLeave
import com.glooboo.umessage.ui.lock.SpamChatGate
import com.glooboo.umessage.helpers.ProviderTruthFlag
import com.glooboo.umessage.ui.thread.ChatLineAction
import com.glooboo.umessage.ui.thread.ChatLineController
import com.glooboo.umessage.ui.lock.LockScope
import com.glooboo.umessage.ui.thread.ThreadRecipients
import com.glooboo.umessage.ui.thread.TapbackReaction
import com.glooboo.umessage.ui.thread.buildThreadItems
import com.glooboo.umessage.ui.thread.firstUnreadMessageId
import com.glooboo.umessage.ui.thread.buildThreadMenu
import com.glooboo.umessage.ui.thread.sendsAsMms
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import java.io.File

/**
 * The chat screen's host. The layout is Compose ([ThreadScreen]) over [ThreadViewModel]; the work is done by the
 * classes in ui/thread: [ThreadMessagesRepository] (loading, paging, refreshing), [ThreadSendController] and
 * [ThreadScheduleController] (sending and scheduling to every [ThreadRecipients] address),
 * [ThreadAttachmentController], [ThreadSimController], [ThreadScheduleUi], [ThreadSenderActions],
 * [ThreadConversationActions], [ThreadSelectionActions] and [ThreadPeopleController]. This class owns the lifecycle,
 * the intent, the activity result launchers and the order in which a conversation is loaded.
 */
open class ThreadActivity : ComposeSimpleActivity() {
    private val vm: ThreadViewModel by viewModels()

    @Volatile
    private var threadId = 0L
    private val refreshLock = Any()

    /** B06: a hidden chat asks for the app lock before it shows. */
    private val hiddenGate: HiddenChatGate =
        HiddenChatGate(this, { spamGate.blocking }, onPassed = { spamGate.check() }) { threadId }

    /** U1 (provider truth): a chat in Spam asks for the app lock while the lock covers Spam. */
    private val spamGate: SpamChatGate = SpamChatGate(
        this, { threadId }, othersCover = { hiddenGate.blocking }, onRevealed = { markReadIfSeen() },
    )

    /** Provider truth (4.3): the one line about the sender. */
    private val chatLine by lazy { ChatLineController(this, vm.chatLine, lifecycleScope) }
    @Volatile
    private var isActivityVisible = false

    @Volatile
    private var hasWindowFocusNow = false
    private var refreshedSinceSent = false
    private var threadItems: List<ThreadItem> = emptyList()
    private var bus: EventBus? = null
    private var conversation: Conversation? = null
    private var recipients = ThreadRecipients()
    private var privateContacts = ArrayList<SimpleContact>()
    /** The loaded messages: loading, paging, refreshing and every single change go through it. */
    private val repository by lazy { ThreadMessagesRepository(ContextThreadMessageSource(applicationContext)) }
    private val messages: List<Message> get() = repository.current
    private val sim by lazy { ThreadSimController(this, vm) }
    private val scheduleUi by lazy {
        ThreadScheduleUi(
            activity = this,
            vm = vm,
            cancel = ::cancelScheduledMessageAndRefresh,
            sendNow = ::sendScheduledMessageNow,
            loadIntoComposer = { message ->
                clearCurrentMessage()
                vm.text.setTextAndPlaceCursorAtEnd(message.body)
                message.attachment?.attachments?.forEach { attachmentController.add(it.getUri()) }
            },
        )
    }
    private var isJumpingToMessage = false

    /** Contact, stranger and SIM lookups that stay the same from one refresh to the next (thread-19). */
    private val lookups = ThreadLookupCache()
    private val contactsObserver = object : android.database.ContentObserver(android.os.Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            lookups.invalidateContacts()
        }
    }
    private var isRecycleBin = false
    private var isLaunchedFromShortcut = false

    /** The reactions found by the last [getThreadItems] (I30). */
    @Volatile
    private var latestReactions: Map<Long, List<TapbackReaction>> = emptyMap()

    /** True once the provider's messages are loaded: only they know what is really unread (I26). */
    @Volatile
    private var providerLoaded = false

    /**
     * True once the user really had this chat in front of them (see [isSeenByUser]). A quick open and Back can end
     * before the provider load finishes; the load then still marks the chat read for this reason.
     */
    @Volatile
    private var shownToUser = false

    /** The stored draft is put back once each time a chat is opened; see [DraftText.restore]. */
    private var draftRestored = false

    /**
     * K2-6, J5-2: when a picker or camera of the chat's own was opened ([launchForResult]), or [NO_TIME]. A pause
     * soon after is that trip, and the sheets stay; a permission prompt that never opened does not excuse a later exit.
     */
    private var ownScreenAskedMs = NO_TIME

    /** K2-6: the message field had the focus when the chat was left under the app lock; it gets it back after. */
    private var refocusComposer = false

    private var isGroupMessageConfirmationVisible = false
    /** One send from the composer at a time: a second tap while it runs is ignored (MS5). */
    private val composerSend = SendGuard()

    /** The failed message "Retry" put into the composer, resent in its place by the next Send. */
    private val resend = ResendTracker()

    private val sendController by lazy {
        ThreadSendController(ContextMessageSender(this)) { error ->
            if (error is Exception) {
                showErrorToast(error)
            } else {
                showErrorToast(error.localizedMessage ?: getString(org.fossify.commons.R.string.unknown_error_occurred))
            }
        }
    }
    private val scheduleController by lazy { ThreadScheduleController(applicationContext) }

    private val attachmentController by lazy { ThreadAttachmentController(this, vm) { updateMessageType() } }

    /** True once the composer's text listener runs; [loadConversation] runs again for a new intent. */
    private var composerListening = false

    // Activity Result API launchers. They are registered up front so results are delivered even when the process was
    // killed while the other app (camera, picker) was open.
    private val takePicture = registerForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        val uri = vm.capturedImageUri
        vm.capturedImageUri = null
        if (saved && uri != null) {
            resend.clear()
            attachmentController.add(uri)
        }
    }

    /** The system Photo Picker: no storage permission needed, several items at once. */
    private val pickMedia = registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        if (uris.isNotEmpty()) resend.clear()
        uris.forEach { attachmentController.add(it) }
    }

    private val pickDocument = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            resend.clear()
            attachmentController.add(uri)
        }
    }

    private val pickContact = registerForActivityResult(ActivityResultContracts.PickContact()) { uri ->
        if (uri != null) {
            resend.clear()
            attachmentController.addContact(uri)
        }
    }

    /** Video and audio recorders return the recording's uri in the result data. */
    private val recordMedia = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data
        if (result.resultCode == Activity.RESULT_OK && uri != null) {
            resend.clear()
            attachmentController.add(uri)
        }
    }

    /** I52: the system prompt for the location; the user may allow the approximate position only. */
    private val locationPermission: ActivityResultLauncher<Array<String>> = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        location.onPermissionResult()
    }

    /** I69: the media prompt of the recent photos row; the sheet reads the answer from the permission itself. */
    private val photoPermission: ActivityResultLauncher<Array<String>> = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        vm.photoAccessVersion++
    }

    /** I65: sounds for sent and received messages, when switched on. */
    private val chatSounds by lazy { ChatSounds.create(this) }

    private val location: ThreadLocationController = ThreadLocationController(
        activity = this,
        requestPermission = { launchForResult(locationPermission, LocationSharer.LOCATION_PERMISSIONS) },
        insert = { insertLocationLink(it) },
    )

    /** "Save" on an MMS attachment: a document (one attachment) or a folder (several). */
    private val saveAttachmentsTarget = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        val destination = data?.data
        if (result.resultCode == Activity.RESULT_OK && destination != null) {
            val toSave = vm.attachmentsToSave
            vm.attachmentsToSave = null
            attachmentController.save(toSave, destination)
        } else {
            vm.attachmentsToSave = null
        }
    }

    private val actions = ThreadActions(
        onBack = { finish() },
        onMenuAction = { handleMenuAction(it) },
        onSelectionAction = { selectionActions.handle(it) },
        onBubbleClick = { message -> if (message.isScheduled) scheduleUi.showOptions(message) },
        onSenderClick = { openSenderContact(it) },
        onRetry = { message, text -> retryFailedMessage(message, text) },
        onLoadOlder = { if (!isJumpingToMessage) loadMoreMessages() },
        onSend = { sendMessage() },
        onSendLongPress = { if (!vm.isScheduling) scheduleUi.pickTime() },
        onAttach = { handleAttach(it) },
        onEditSchedule = { scheduleUi.pickTime(vm.scheduledMillis) },
        onDiscardSchedule = { scheduleUi.discard() },
        onSelectSim = { selectSim(it) },
        onRemoveAttachment = { attachmentController.remove(it) },
        onOpenFile = { uri, mimeType, filename -> launchViewIntent(uri, mimeType, filename) },
        onOpenVCard = { uri ->
            startActivity(Intent(this, VCardViewerActivity::class.java).putExtra(EXTRA_VCARD_URI, uri))
        },
        onReplyInfo = {
            InvalidNumberDialog(activity = this, text = getString(R.string.invalid_short_code_desc))
        },
        onPickContact = { people.pickContact(it) },
        onAddTypedNumber = { people.addNumber(it) },
        onRemovePerson = { people.remove(it) },
        onConfirmPeople = { people.confirm() },
        onAddPhoto = {
            resend.clear()
            attachmentController.add(it)
        },
        onRequestPhotos = {
            launchForResult(photoPermission, RecentPhotosAccess.permissions(Build.VERSION.SDK_INT))
        },
        onDownloadMms = { id -> ensureBackgroundThread { downloadMmsFromChat(id) } },
        onChatLine = { onChatLine(it) },
    )

    /** Provider truth (4.3): the chat line's buttons; Block there needs no confirm, it has Undo. */
    private fun onChatLine(action: ChatLineAction) {
        val address = recipients.reportableAddress ?: return
        val moves = senderActions.spamMoves
        when (action) {
            ChatLineAction.BLOCK -> moves.block(listOf(address))
            ChatLineAction.KEEP -> moves.keep(address)
            ChatLineAction.UNBLOCK -> moves.unblock(address)
            ChatLineAction.NOT_SPAM -> moves.notSpam(address)
            ChatLineAction.ADD_CONTACT -> handleMenuAction(ThreadMenuAction.AddToContact)
        }
    }

    /** Q40: "Tap to download". A message that is gone from the provider (downloaded meanwhile) just refreshes. */
    private fun downloadMmsFromChat(id: Long) {
        if (downloadMms(id) == MmsRetryResult.NotPending) refreshMessages()
    }

    /**
     * singleTop: a notification or shortcut for a conversation reuses this screen. The same conversation keeps its
     * draft and attachments and only applies what the intent adds; another conversation is loaded in place (the
     * current draft was already saved in onPause).
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.extras == null) return
        val newThreadId = intent.getLongExtra(THREAD_ID, 0L)
        setIntent(intent)
        if (newThreadId != 0L && newThreadId == threadId) {
            // Shared text into the chat that is open: added after what is typed, never dropped (B9).
            intent.getStringExtra(THREAD_TEXT)?.let { shared ->
                vm.text.setTextAndPlaceCursorAtEnd(DraftText.appendShared(vm.text.text.toString(), shared))
            }
            addAttachmentsFromIntent()
            takeSearchedMessage()?.let { jumpToMessage(it) }
            takeOpenSearch()
            applyLaunchHints()
            return
        }

        // M-UI5: "Done" in the people panel brings the composer's attachments along (its text comes as THREAD_TEXT).
        val keptAttachments = if (intent.getBooleanExtra(ThreadPeopleController.EXTRA_KEEP_ATTACHMENTS, false)) {
            vm.attachments
        } else {
            emptyList()
        }
        intent.removeExtra(ThreadPeopleController.EXTRA_KEEP_ATTACHMENTS)
        resetForNewConversation()
        keptAttachments.forEach(vm::addAttachment)
        draftRestored = false
        readIntentExtras()
        // onResume does not run again for a chat that is already in front: the hidden-chat cover is decided here.
        hiddenGate.check()
        spamGate.check()
        loadConversation(isFreshLaunch = true)
        markReadIfSeen()
    }

    private fun readIntentExtras() {
        threadId = intent.getLongExtra(THREAD_ID, 0L)
        intent.getStringExtra(THREAD_TITLE)?.let { vm.title = it }
        isRecycleBin = intent.getBooleanExtra(IS_RECYCLE_BIN, false)
        isLaunchedFromShortcut = intent.getBooleanExtra(IS_LAUNCHED_FROM_SHORTCUT, false)
        vm.isRecycleBin = isRecycleBin
        takeOpenSearch()
    }

    /** [EXTRA_OPEN_SEARCH] (Conversation details' "Search in conversation"): opens the search bar, once. */
    private fun takeOpenSearch() {
        if (intent.getBooleanExtra(EXTRA_OPEN_SEARCH, false)) {
            intent.removeExtra(EXTRA_OPEN_SEARCH)
            if (!isRecycleBin) vm.openSearch()
        }
    }

    /**
     * The focus and notice extras (opened from a blocked chat after Not spam): once the first load is
     * done, the message field takes focus and the keyboard comes up, and the note shows. Taken off the intent, so a
     * rotation does not do it again.
     */
    private fun applyLaunchHints() {
        val hints = ThreadLaunchHints.take(intent)
        hints.notice?.let { note ->
            lifecycleScope.launch {
                vm.snackbar.showSnackbar(note, withDismissAction = true, duration = SnackbarDuration.Short)
            }
        }
        if (hints.focusComposer && !isRecycleBin && !isSpecialNumber()) {
            window.setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
            )
            vm.focusInput++
        }
    }

    /** Clears everything that belongs to the previous conversation before another one is loaded in place. */
    private fun resetForNewConversation() {
        AudioPlayerManager.release()
        // M-UI3: first, so a load still running for the old chat stops before it writes into the fields below.
        // J5-4: the old chat's conversation and people are cleared before the new generation can be read.
        repository.reset {
            conversation = null
            recipients = ThreadRecipients()
        }
        privateContacts = ArrayList()
        lookups.clear()
        threadItems = emptyList()
        refreshedSinceSent = false
        isJumpingToMessage = false
        isGroupMessageConfirmationVisible = false
        // J5-5: the old chat's sheets and dialogs go too; a delete question confirmed later would act on this chat.
        vm.closeWindows()
        LockedLeave.closeHostedDialogs(this)
        resend.clear()
        vm.clearSchedule()
        vm.clearSelection()
        vm.clearAttachments()
        vm.text.clearText()
        vm.publishItems(emptyList())
        vm.reactions = emptyMap()
        latestReactions = emptyMap()
        vm.firstUnreadId = null
        vm.unreadResolved = false
        vm.unreadScrolled = false
        providerLoaded = false
        shownToUser = false
        vm.loaded = false
        vm.title = ""
        vm.subtitle = null
        vm.avatarPhotoUri = ""
        vm.showPeople = false
        vm.showAttachSheet = false
        vm.showEmojiPanel = false
        // K2-1: a bank or code chat turned the input off; the new chat's participants decide again.
        vm.replyDisabled = false
        vm.closeSearch()
        vm.people = emptyList()
        refreshMenuItems()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Same window theme as every other Compose screen (the manifest entry has none).
        setTheme(R.style.Theme_UMessage_Screen)
        super.onCreate(savedInstanceState)

        val extras = intent.extras
        if (extras == null) {
            toast(org.fossify.commons.R.string.unknown_error_occurred)
            finish()
            return
        }

        readIntentExtras()

        setUMessageContent { ThreadScreen(vm, actions) }
        // D4 B2: a suggested reply confirmed in a Spam chat is sent at once.
        vm.chatLine.sendNow = { sendMessage() }
        // After the process was killed the time survives in saved state but the label does not.
        vm.scheduledMillis?.let { scheduleUi.show(it) }

        bus = EventBus.getDefault()
        bus!!.register(this)

        // Search in conversation also looks at the messages that are not loaded yet.
        vm.olderSearch = { thread, query ->
            messagesDB.searchThreadMessages(thread, query)
                .filter { matchesSearch(it.body, query) }
                .map { SearchHit(it.key, it.date) }
        }
        vm.loadAndScrollTo = ::jumpToMessage
        runCatching {
            contentResolver.registerContentObserver(
                android.provider.ContactsContract.Contacts.CONTENT_URI, true, contactsObserver
            )
        }

        refreshMenuItems()
        senderActions.observeRules()
        vm.stars.attach(this, lifecycleScope)
        vm.notes.attach(MessageNoteStore(messageNotes), lifecycleScope)
        loadConversation(isFreshLaunch = savedInstanceState == null)
    }

    override fun onResume() {
        closeWindowsIfLocked()
        super.onResume()
        ownScreenAskedMs = NO_TIME
        hiddenGate.check()
        spamGate.check()
        // J5-9: a hidden or Spam chat locked again (a picker trip does not count for them) shows nothing that a trip
        // of the chat's own kept open; closed in the same pass as the cover, so no frame shows it.
        if (hiddenGate.blocking || spamGate.blocking) closeWindows()
        // A contact may have been added meanwhile: the chat line is worked out again.
        chatLine.follow(recipients.reportableAddress.takeIf { recipients.size == 1 && !isRecycleBin }, force = true)

        isActivityVisible = true
        // The SIM cards may have changed while the chat was in the background.
        lookups.invalidateSims()

        scheduleUi.onResume()
        restoreComposerFocus()

        // M-UI3: what this read finds belongs to the chat open now; another chat loaded meanwhile drops it.
        val generation = repository.generation
        val thread = threadId
        ensureBackgroundThread {
            val newConv = conversationsDB.getConversationWithThreadId(thread)
            if (newConv != null && repository.ifCurrent(generation) { conversation = newConv }) {
                runOnUiThread {
                    if (repository.isStale(generation)) return@runOnUiThread
                    setupThreadTitle()
                    // "Conversation details" and the other entries that need the saved conversation.
                    refreshMenuItems()
                }
            }

            val smsDraft = getSmsDraft(thread)
            if (smsDraft.isNotEmpty()) {
                runOnUiThread {
                    // Once per open, and merged with what is already in the composer (B8): a shared text, or text
                    // typed before this read finished, is kept; coming back from the camera never rewinds it.
                    if (!draftRestored && !repository.isStale(generation)) {
                        draftRestored = true
                        vm.text.setTextAndPlaceCursorAtEnd(DraftText.restore(vm.text.text.toString(), smsDraft))
                    }
                }
            }
        }
        markReadIfSeen()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        hasWindowFocusNow = hasFocus
        if (hasFocus) markReadIfSeen()
    }

    /**
     * TU-11: messages count as read only while the user can actually see this chat: the screen is in front, its
     * window has focus (not under the lock screen, the notification shade or a dialog) and the app lock is not up.
     * Texts that arrive while the chat sits open in the background stay unread until the user comes back to it.
     */
    private fun isSeenByUser(): Boolean =
        isActivityVisible && hasWindowFocusNow && !hiddenGate.blocking && !spamGate.blocking &&
            !spamGate.deciding && !LockScope.isLockedNow(this)

    private fun markReadIfSeen() {
        if (!isSeenByUser()) return
        shownToUser = true
        markThreadReadInBackground(threadId)
    }

    private fun markThreadReadInBackground(thread: Long) {
        notificationManager.cancel(thread.hashCode())
        // Provider truth (4.6): a Spam chat that shows has its quiet notice go too.
        BlockedNotifier.cancelChat(this, thread)
        // The chat is open: its "Message not sent" notification has done its job, the failed row has Retry (MS3).
        cancelFailedSendNotification(thread)
        ensureBackgroundThread {
            // I26: the unread messages are noted first; the build that does it marks them read itself.
            if (!providerLoaded && !isRecycleBin) return@ensureBackgroundThread
            // A refused provider write must not skip the saved flags or the list refresh.
            runCatching { markThreadMessagesRead(thread) }
            // Provider truth (3.4): opening a Spam chat clears uMessage's own unread count of it.
            if (ProviderTruthFlag.enabled) runCatching { conversationsDB.clearSpamUnread(thread) }
            repository.markAllRead(thread)
            bus?.post(Events.RefreshConversations(thread))
            // A notification posted while the chat was being read would otherwise stay.
            notificationManager.cancel(thread.hashCode())
            BlockedNotifier.cancelChat(this, thread)
        }
    }

    override fun onPause() {
        super.onPause()
        saveDraftMessage()
        // J2-4: this chat only; the list shows its saved rows and moves sent texts up itself on return.
        bus?.post(Events.RefreshConversations(threadId.takeIf { it > 0 }))
        isActivityVisible = false
        leaveUnderLock()
    }

    /**
     * K2-6: with the app lock on, the chat's dialogs and sheets close when it is left, as the lock's cover does not
     * hide their windows. Done on pause, while the screen still recomposes, so none is left to show on the way back.
     */
    private fun leaveUnderLock() {
        val asked = ownScreenAskedMs
        ownScreenAskedMs = NO_TIME
        val ownTrip = asked != NO_TIME && SystemClock.elapsedRealtime() - asked in 0..AppLockManager.OWN_TRIP_START_MS
        if (!LockedLeave.closesWindows(this, ownTrip)) return
        closeWindows()
    }

    /**
     * K2-6, J5-1: back under the lock, the windows are closed before anything draws. This catches what a trip of the
     * chat's own left open (a permission prompt, then the screen went off; a camera trip past the lock's limit).
     */
    private fun closeWindowsIfLocked() {
        if (LockScope.isLockedNow(this)) closeWindows()
    }

    /** Closes the chat's sheets, menus and dialogs; a focused message field is given its focus back after unlock. */
    private fun closeWindows() {
        // Hiding the content under the lock takes the focus from the field: it is given back after the unlock.
        if (vm.composerFocused) refocusComposer = true
        vm.closeWindows()
        // The group question has a dismiss callback of its own, which a closed host never calls.
        isGroupMessageConfirmationVisible = false
        LockedLeave.closeHostedDialogs(this)
    }

    /** K2-6: the field that had the focus before the app lock covered the chat takes it again once the chat shows. */
    private fun restoreComposerFocus() {
        if (!refocusComposer || hiddenGate.blocking || spamGate.blocking) return
        if (findViewById<View>(android.R.id.content)?.visibility != View.VISIBLE) return
        refocusComposer = false
        vm.focusInput++
    }

    override fun onStop() {
        super.onStop()
        AudioPlayerManager.release()
        if (selectionActionsDelegate.isInitialized()) selectionActions.release()
        saveDraftMessage()
    }

    override fun onDestroy() {
        super.onDestroy()
        chatSounds.release()
        bus?.unregister(this)
        vm.olderSearch = null
        vm.loadAndScrollTo = null
        runCatching { contentResolver.unregisterContentObserver(contactsObserver) }
    }

    private fun saveDraftMessage() {
        if (!vm.loaded && vm.text.text.isEmpty()) return
        val draftMessage = draftToSave(vm.text.text.toString())
        val draftThreadId = threadId
        ensureBackgroundThread {
            if (draftMessage != null) {
                saveSmsDraft(draftMessage, draftThreadId)
            } else {
                deleteSmsDraft(draftThreadId)
            }
        }
    }

    /** Whether the only participant's number is already a saved contact; checked off the main thread. */
    @Volatile
    private var firstNumberIsSaved = false

    /** I04: whether the only person is a stranger (not a contact, never written to); checked off the main thread. */
    @Volatile
    private var senderIsStranger = false
    private val unknownCard by lazy { UnknownCardController(this, vm) }

    private val selectionActionsDelegate = lazy {
        ThreadSelectionActions(
            activity = this,
            vm = vm,
            isRecycleBin = { isRecycleBin },
            saveAttachments = ::saveMMS,
            deleteMessages = ::deleteMessages,
        )
    }
    private val selectionActions by selectionActionsDelegate

    private val exportController by lazy {
        ThreadExportController(
            activity = this,
            threadId = { threadId },
            title = { vm.title },
            numbers = { recipients.addresses.distinct() },
            showMessage = { text -> lifecycleScope.launch { vm.snackbar.showSnackbar(text) } },
        )
    }

    private val conversationActions by lazy {
        ThreadConversationActions(
            activity = this,
            threadId = { threadId },
            isRecycleBin = { isRecycleBin },
            recipients = { recipients },
            conversation = { conversation },
            title = { vm.title },
            showMessage = { text -> lifecycleScope.launch { vm.snackbar.showSnackbar(text) } },
            onRenamed = { renamed ->
                conversation = renamed
                setupThreadTitle()
            },
        )
    }

    private val people by lazy {
        ThreadPeopleController(
            activity = this,
            vm = vm,
            recipients = { recipients },
            setRecipients = { recipients = it },
            privateContacts = { privateContacts },
            threadId = { threadId },
            onPeopleChanged = { updateMessageType() },
        )
    }

    /** N05, B06: Mute and Hide entries of the menu. */
    private val privacy by lazy {
        ThreadPrivacyActions(this, { threadId }, onChange = { refreshMenuItems() }, onHidden = { finish() })
    }

    /** Block, unblock and report spam; it also tells the menu whether the sender is already blocked. */
    private val senderActions by lazy {
        ThreadSenderActions(
            activity = this,
            scope = lifecycleScope,
            snackbar = vm.snackbar,
            recipients = { recipients },
            senderName = { vm.title },
            lastReceivedSubscriptionId = { messages.lastOrNull { it.isReceivedMessage() }?.subscriptionId ?: -1 },
            onStatusChanged = { refreshMenuItems() },
            threadId = { threadId },
        )
    }

    private fun refreshMenuItems() {
        vm.threadId = threadId
        senderActions.refreshIfPeopleChanged()
        chatLine.follow(recipients.reportableAddress.takeIf { recipients.size == 1 && !isRecycleBin })
        vm.linksTrusted = recipients.size == 1 && firstNumberIsSaved
        unknownCard.refresh(
            senderIsStranger, recipients, messages.lastOrNull { it.isReceivedMessage() }, threadId,
            hasRule = senderActions.allowedSender != null || senderActions.blockedSender != null,
        )
        vm.menu = buildThreadMenu(
            ThreadMenuInput(
                recipients = recipients,
                hasItems = threadItems.isNotEmpty(),
                hasConversation = conversation != null,
                isArchived = conversation?.isArchived,
                isRecycleBin = isRecycleBin,
                archiveAvailable = config.isArchiveAvailable,
                singleNumberIsSaved = firstNumberIsSaved,
                reportedSender = senderActions.reportedSender,
                blockedSender = senderActions.blockedSender,
                allowedSender = senderActions.allowedSender,
                pinShortcutSupported = ShortcutManagerCompat.isRequestPinShortcutSupported(this),
                forwardingAvailable = ForwardingAvailability.isAvailable(this),
                muted = privacy.isMuted(),
                hiddenChat = privacy.isHiddenChat(),
            )
        )
        vm.senderStatus = when {
            senderActions.blockedSender != null -> SenderStatus.Blocked
            senderActions.allowedSender != null -> SenderStatus.Allowed
            else -> SenderStatus.None
        }
    }

    private fun handleMenuAction(action: ThreadMenuAction) {
        if (recipients.isEmpty()) return
        when (action) {
            ThreadMenuAction.Block -> senderActions.askBlock()
            ThreadMenuAction.Unblock -> senderActions.unblock()
            ThreadMenuAction.ReportSpam -> senderActions.askReportSpam()
            ThreadMenuAction.AllowSender -> senderActions.allowSender()
            ThreadMenuAction.WhyDecided -> startActivity(Intent(this, DecisionLogActivity::class.java))
            ThreadMenuAction.Search -> vm.openSearch()
            ThreadMenuAction.AlwaysForward -> openForwardRuleForSender()
            ThreadMenuAction.Delete -> conversationActions.askDelete()
            ThreadMenuAction.Restore -> conversationActions.askRestoreAll()
            ThreadMenuAction.Archive -> conversationActions.setArchived(true)
            ThreadMenuAction.Unarchive -> conversationActions.setArchived(false)
            ThreadMenuAction.Rename -> conversationActions.rename()
            ThreadMenuAction.Details -> launchConversationDetails(threadId)
            ThreadMenuAction.AddToContact -> conversationActions.addNumberToContacts()
            ThreadMenuAction.CopyNumber -> conversationActions.copyNumber()
            ThreadMenuAction.Dial -> conversationActions.dial()
            ThreadMenuAction.ManagePeople -> people.toggle()
            ThreadMenuAction.MarkUnread -> conversationActions.markUnread()
            ThreadMenuAction.ShareAsText -> conversationActions.shareAsText()
            ThreadMenuAction.ExportConversation -> exportController.start()
            ThreadMenuAction.AddToHomeScreen -> conversationActions.addToHomeScreen()
            ThreadMenuAction.Labels -> vm.labelsFor = threadId
            ThreadMenuAction.Mute -> privacy.askMute()
            ThreadMenuAction.Unmute -> privacy.unmute()
            ThreadMenuAction.Hide -> privacy.hide()
            ThreadMenuAction.Unhide -> privacy.unhide()
        }
    }

    /**
     * TH-11, TH-20: "Always forward from this sender" opens the forwarding screen with the rule editor already
     * filled in for this sender; saving it goes through the editor's usual checks (and the app lock).
     */
    private fun openForwardRuleForSender() {
        if (!ForwardingAvailability.isAvailable(this)) return
        val address = recipients.reportableAddress ?: return
        startActivity(ForwardingActivity.intent(this, address))
    }

    private fun setupCachedMessages(callback: () -> Unit) {
        val generation = repository.generation
        val thread = threadId
        ensureBackgroundThread {
            repository.loadCached(thread, isRecycleBin, generation)
            // M-UI3: another chat was loaded in place meanwhile; this load is for the old one.
            if (repository.isStale(generation)) return@ensureBackgroundThread

            setupParticipants(generation)
            setupAdapter(generation)

            runOnUiThread {
                if (repository.isStale(generation)) return@runOnUiThread
                if (messages.isEmpty() && !isSpecialNumber()) {
                    window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
                    vm.focusInput++
                }

                setupThreadTitle()
                setupSIMSelector()
                updateMessageType()
                callback()
            }
        }
    }

    private fun setupThread(callback: () -> Unit) {
        if (conversation == null && isLaunchedFromShortcut) {
            if (isTaskRoot) {
                Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    startActivity(this)
                }
            }
            finish()
            return
        }
        val privateCursor = getMyContactsCursor(favoritesOnly = false, withPhoneNumbersOnly = true)
        // M-UI3: each step below stops once another chat was loaded in place (a notification opened it meanwhile).
        // Fields are written through ifCurrent, atomic against the reset of another chat.
        val generation = repository.generation
        val thread = threadId
        fun stale() = repository.isStale(generation)
        ensureBackgroundThread {
            val contactsRead = MyContactsContentProvider.getSimpleContacts(this, privateCursor)
            if (!repository.ifCurrent(generation) { privateContacts = contactsRead }) return@ensureBackgroundThread

            val cachedMessagesCode = messages.hashCode()
            val cachedBefore = messages
            if (!isRecycleBin) repository.loadNewest(thread, generation)
            if (!repository.ifCurrent(generation) { providerLoaded = true }) return@ensureBackgroundThread
            // The first mark-read attempt may have come before the messages were loaded. When the user already
            // saw the chat but left before this point, nobody would ask again: mark it now.
            if (shownToUser) markThreadReadInBackground(thread) else runOnUiThread { markReadIfSeen() }

            val providerParticipantsChanged = reconcileProviderParticipants(generation)
            val hasParticipantWithoutName = recipients.contacts.any { contact ->
                contact.phoneNumbers.map { it.normalizedNumber }.contains(contact.name)
            }

            try {
                if (canReuseLoadedThread(providerParticipantsChanged, cachedMessagesCode, hasParticipantWithoutName)) {
                    adoptWaitingScheduledMessages(thread, generation)
                    setupAdapter(generation)
                    runOnUiThread { if (!stale()) callback() }
                    return@ensureBackgroundThread
                }
            } catch (ignored: Exception) {
            }

            if (stale()) return@ensureBackgroundThread
            setupParticipants(generation)

            // check if no participant came from a privately stored contact in Simple Contacts
            if (privateContacts.isNotEmpty()) {
                val senderNumbersToReplace = HashMap<String, String>()
                recipients.contacts.filter { it.doesHavePhoneNumber(it.name) }.forEach { participant ->
                    privateContacts.firstOrNull { it.doesHavePhoneNumber(participant.phoneNumbers.first().normalizedNumber) }
                        ?.apply {
                            senderNumbersToReplace[participant.phoneNumbers.first().normalizedNumber] =
                                name
                            participant.name = name
                            participant.photoUri = photoUri
                        }
                }

                messages.forEach { message ->
                    if (senderNumbersToReplace.keys.contains(message.senderName)) {
                        message.senderName = senderNumbersToReplace[message.senderName]!!
                    }
                }
            }

            if (recipients.isEmpty()) {
                // K2-2: emptied by another chat loaded in place; its intent is not this chat's (and has no number when
                // a notification opened it), so the "invalid number" close below would shut the chat just opened.
                if (stale()) return@ensureBackgroundThread
                val name = intent.getStringExtra(THREAD_TITLE) ?: ""
                val numbers = getPhoneNumbersFromIntent()
                if (numbers.isEmpty()) {
                    // No messages, no participants known to the phone and no number in the intent: nothing to write to.
                    Log.w("ThreadActivity", "Chat opened without a conversation, participants or number")
                    // J5-4: decided on the main thread, where another chat's reset runs, so the check is exact.
                    runOnUiThread {
                        if (stale()) return@runOnUiThread
                        toast(R.string.new_conversation_invalid_number, length = Toast.LENGTH_LONG)
                        finish()
                    }
                    return@ensureBackgroundThread
                }
                val fromIntent = ThreadRecipients.fromNumbers(numbers, name)
                if (!repository.ifCurrent(generation) { recipients = fromIntent }) return@ensureBackgroundThread
            }

            if (stale()) return@ensureBackgroundThread
            adoptWaitingScheduledMessages(thread, generation)

            if (!isRecycleBin) {
                // Only what is new or changed since the cached copy: the whole page used to be rewritten on every open.
                messagesToCache(messages, cachedBefore).chunked(30).forEach { currentMessages ->
                    messagesDB.insertMessages(*currentMessages.toTypedArray())
                }
            }

            setupAdapter(generation)
            runOnUiThread {
                if (stale()) return@runOnUiThread
                setupThreadTitle()
                setupSIMSelector()
                callback()
            }
        }
    }

    /**
     * Brings in messages scheduled for these people while they had no conversation yet, which waited in a temporary
     * thread of their own: without this the real conversation said "No messages yet". Runs in the background, for
     * [thread] as loaded in [generation] (M-UI3, K2-2: not into another chat loaded in place during the lookup).
     */
    private fun adoptWaitingScheduledMessages(thread: Long, generation: Int) {
        if (isRecycleBin || recipients.isEmpty()) return
        val adopted = adoptScheduledMessages(thread, recipients.addresses)
        if (adopted.isEmpty()) return
        repository.addAll(adopted, generation)
        // J2-4: the moved rows are saved already (the temporary chats gone, this one kept).
        bus?.post(Events.RefreshConversations(thread))
    }

    private fun canReuseLoadedThread(
        providerParticipantsChanged: Boolean,
        cachedMessagesCode: Int,
        hasParticipantWithoutName: Boolean,
    ): Boolean {
        if (providerParticipantsChanged || recipients.isEmpty() || hasParticipantWithoutName) {
            return false
        }

        return messages.hashCode() == cachedMessagesCode
    }

    /**
     * Rebuilds the thread items from [messages] and hands them to the Compose list; not when another chat was loaded
     * in place since [generation] (M-UI3).
     */
    private fun setupAdapter(generation: Int = repository.generation) {
        val latestThreadItems = getThreadItems()
        // Saved picture sizes are read before the rows are shown, so photo bubbles start at their size (TU-25).
        if (Looper.myLooper() != Looper.getMainLooper()) MediaSizes.warm(this, messages)
        val number = recipients.singleNumber
        // Looked up once per opening and again only when the contacts change or the user writes to this person.
        firstNumberIsSaved = number != null && lookups.isSaved(number) { isSavedContactNumber(number) }
        val hasSent = messages.any { !it.isReceivedMessage() && !it.isScheduled }
        senderIsStranger = lookups.isStranger(number, firstNumberIsSaved, hasSent) {
            unknownCard.isStranger(number, firstNumberIsSaved, recipients.isGroup)
        }

        runOnUiThread {
            if (repository.isStale(generation)) return@runOnUiThread
            publishItems(latestThreadItems)
            refreshMenuItems()
            if (vm.firstUnreadId != null && !vm.unreadScrolled) {
                vm.unreadScrolled = true
                vm.scrollToUnread()
            }
        }
    }

    private fun publishItems(items: List<ThreadItem>) {
        threadItems = items
        vm.reactions = latestReactions
        vm.isGroup = recipients.isGroup
        vm.approximateScheduledIds = config.inexactScheduledMessages.mapNotNullTo(HashSet()) { it.toLongOrNull() }
        vm.refreshRepeatRules(this, items)
        vm.publishItems(items)
        vm.loaded = true
    }

    private fun retryFailedMessage(failedKey: MessageKey, text: String) {
        // Matched by id and type: SMS 57 and MMS 57 are different messages (MessageKey).
        // A held scheduled message (RM-25) is sent now as it is; on success its scheduled row, and so the failed
        // mark, is removed. It never goes through the composer, so the user's draft stays.
        messages.firstOrNull { it.key == failedKey && it.isHeldScheduled() }?.let {
            sendScheduledMessageNow(it)
            return
        }
        val failed = messages.firstOrNull { it.key == failedKey && it.type == Telephony.Sms.MESSAGE_TYPE_FAILED }
        if (failed != null) {
            // Tapping "Not sent. Tap to retry." sends the failed message again right away, as it was (text, pictures
            // and files, SIM), in the failed row's place. It used to only copy the text into the composer, so on
            // a tap nothing visibly happened, and it replaced whatever the user was typing. The draft stays untouched.
            sendNormalMessage(
                text = failed.body.ifEmpty { text },
                subscriptionId = failed.subscriptionId.takeIf { it >= 0 } ?: sim.subscriptionId,
                attachments = failed.attachment?.attachments.orEmpty(),
                resendId = failedKey,
                fromComposer = false,
            ) { handedOff -> if (!handedOff) toast(R.string.message_not_sent_short) }
            return
        }
        // The failed message is not loaded any more: fall back to putting its text into the composer.
        resend.prepare(failedKey, text)
        vm.text.setTextAndPlaceCursorAtEnd(text)
    }

    private fun openSenderContact(message: Message) {
        val number = message.getSender()?.phoneNumbers?.firstOrNull()?.normalizedNumber ?: return
        getContactFromAddress(number) {
            if (it != null) {
                startContactDetailsIntent(it)
            }
        }
    }

    private fun deleteMessages(
        messagesToRemove: List<Message>,
        toRecycleBin: Boolean,
        fromRecycleBin: Boolean,
    ) {
        repository.remove(messagesToRemove)
        val latestThreadItems = getThreadItems()
        val hasMessages = messages.isNotEmpty()

        runOnUiThread {
            messagesToRemove.forEach { message ->
                message.attachment?.attachments?.forEach { AudioPlayerManager.release(it.getUri()) }
            }
            if (!hasMessages) {
                finish()
            } else {
                publishItems(latestThreadItems)
                vm.clearSelection()
            }
        }

        // Only scheduled messages left: they move to a temporary thread until one is sent.
        val movedToTemporaryThread = removeThreadMessages(
            messagesToRemove, toRecycleBin, fromRecycleBin, threadId, messages.toSortedMessages(), conversation, scheduleController,
            onRefused = { res -> runOnUiThread { lifecycleScope.launch { vm.snackbar.showSnackbar(getString(res)) } } },
        )?.also { threadId = it } != null
        // Messages that went to the recycle bin can come straight back (scheduled ones were cancelled, not recycled).
        val recycled = messagesToRemove.filterNot { it.isScheduled }
        val undoable = toRecycleBin && !movedToTemporaryThread
        if (undoable && hasMessages && recycled.isNotEmpty()) offerUndoDelete(recycled)
    }

    /** A snackbar after messages went to the recycle bin: Undo puts them back. */
    private fun offerUndoDelete(recycled: List<Message>) {
        runOnUiThread {
            lifecycleScope.launch {
                val result = vm.snackbar.showSnackbar(
                    message = getString(R.string.thread_messages_deleted),
                    actionLabel = getString(R.string.thread_messages_deleted_undo),
                    duration = androidx.compose.material3.SnackbarDuration.Long,
                )
                if (result == androidx.compose.material3.SnackbarResult.ActionPerformed) undoDelete(recycled)
            }
        }
    }

    /** Takes [restored] out of the recycle bin again and shows them in the chat. */
    private fun undoDelete(restored: List<Message>) {
        val thread = threadId
        val generation = repository.generation
        ensureBackgroundThread {
            restored.forEach { restoreMessageFromRecycleBin(it.id, it.isMMS) }
            updateLastConversationMessage(thread)
            repository.addAll(restored, generation)
            val latestThreadItems = getThreadItems()
            runOnUiThread { if (!repository.isStale(generation)) publishItems(latestThreadItems) }
            refreshConversations()
        }
    }

    /** The message a search result opened this chat at, read once from the intent and then cleared. */
    private fun takeSearchedMessage(): MessageKey? {
        val id = intent.getLongExtra(SEARCHED_MESSAGE_ID, -1L)
        val isMMS = intent.getBooleanExtra(SEARCHED_MESSAGE_IS_MMS, false)
        intent.removeExtra(SEARCHED_MESSAGE_ID)
        intent.removeExtra(SEARCHED_MESSAGE_IS_MMS)
        return if (id == -1L) null else MessageKey(id, isMMS)
    }

    /** Scrolls to [target], paging in older history until it is loaded. Matched by id and type (MessageKey). */
    private fun jumpToMessage(target: MessageKey) {
        if (messages.any { it.key == target }) {
            vm.scrollToMessage(target, flash = true)
            return
        }

        if (repository.loadingOlder) {
            // Another page load is running: try again when it is done instead of dropping the jump. K2-2: only in the
            // same chat; another one loaded in place meanwhile would be paged through for a message it does not have.
            val forChat = repository.generation
            window?.decorView?.postDelayed({
                if (!isFinishing && !repository.isStale(forChat)) jumpToMessage(target)
            }, JUMP_RETRY_MS)
            return
        }
        // M-UI3: set here on the main thread, before another chat's reset can clear them, never after it.
        repository.loadingOlder = true
        isJumpingToMessage = true
        val generation = repository.generation
        val thread = threadId
        ensureBackgroundThread {
            var cutoff = repository.oldestDate() ?: Int.MAX_VALUE
            var found = false
            var loops = 0

            // not the best solution, but this will do for now.
            runCatching {
                while (!found && !repository.allFetched && !repository.isStale(generation)) {
                    if (repository.fetchOlder(thread, cutoff, generation).isEmpty() || loops >= 1000) break
                    val messageSnapshot = messages.toSortedMessages()
                    cutoff = messageSnapshot.first().date
                    found = messageSnapshot.any { it.key == target }
                    loops++
                }
            }

            val latestThreadItems = getThreadItems()
            runOnUiThread {
                // M-UI3: another chat is open now; its reset already cleared the paging flags.
                if (repository.isStale(generation)) return@runOnUiThread
                publishItems(latestThreadItems)
                repository.loadingOlder = false
                if (messages.any { it.key == target }) {
                    vm.scrollToMessage(target, flash = true)
                } else {
                    toast(org.fossify.commons.R.string.unknown_error_occurred)
                }
                isJumpingToMessage = false
            }
        }
    }

    private fun loadMoreMessages() {
        val cutoff = repository.oldestDate() ?: return
        if (repository.allFetched || repository.loadingOlder) return
        repository.loadingOlder = true
        val thread = threadId
        val generation = repository.generation
        ensureBackgroundThread {
            // A failure must not leave loadingOlder set: that stopped paging until the chat was reopened.
            val loaded = runCatching { repository.fetchOlder(thread, cutoff, generation) }.isSuccess
            val latestThreadItems = if (loaded) getThreadItems() else null
            runOnUiThread {
                // M-UI3: another chat is open now; its reset already cleared loadingOlder.
                if (repository.isStale(generation)) return@runOnUiThread
                latestThreadItems?.let { publishItems(it) }
                repository.loadingOlder = false
            }
        }
    }

    private fun loadConversation(isFreshLaunch: Boolean) {
        handlePermission(PERMISSION_READ_PHONE_STATE) { _ ->
            // Thread-17: a denied permission only costs the SIM features (names, the SIM picker); the chat opens.
            setupComposer(isFreshLaunch)
            setupConversation()
            setupCachedMessages {
                setupThread {
                    takeSearchedMessage()?.let { jumpToMessage(it) }
                    if (isFreshLaunch) applyLaunchHints()
                }
            }
        }
    }

    private fun setupConversation() {
        val generation = repository.generation
        val thread = threadId
        ensureBackgroundThread {
            val found = conversationsDB.getConversationWithThreadId(thread)
            // M-UI3: read for a chat that another one replaced meanwhile.
            if (!repository.ifCurrent(generation) { conversation = found }) return@ensureBackgroundThread
            // The menu may have been built before the conversation was read (no "Conversation details" then).
            runOnUiThread { refreshMenuItems() }
        }
    }

    private fun setupComposer(isFreshLaunch: Boolean) {
        vm.showCounter = config.showCharacterCounter
        vm.sendOnEnter = config.sendOnEnter
        vm.voiceButton = composerPrefs.voiceButton

        if (!composerListening) {
            composerListening = true
            lifecycleScope.launch {
                snapshotFlow { vm.text.text.toString() }.collect { onMessageTextChanged(it) }
            }
        }

        // The view model keeps text and attachments across recreation, so the intent only seeds a fresh launch.
        if (isFreshLaunch) {
            intent.getStringExtra(THREAD_TEXT)?.let { vm.text.setTextAndPlaceCursorAtEnd(it) }
            addAttachmentsFromIntent()
        }
    }

    private fun addAttachmentsFromIntent() {
        if (intent.extras?.containsKey(THREAD_ATTACHMENT_URI) == true) {
            val uri = intent.getStringExtra(THREAD_ATTACHMENT_URI)?.toUri()
            if (uri != null && IncomingShareGuard.isAllowed(uri, packageName)) {
                attachmentController.add(uri, shared = true)
            }
        } else if (intent.extras?.containsKey(THREAD_ATTACHMENT_URIS) == true) {
            @Suppress("DEPRECATION", "UNCHECKED_CAST")
            (intent.getSerializableExtra(THREAD_ATTACHMENT_URIS) as? ArrayList<Uri>)?.forEach {
                if (IncomingShareGuard.isAllowed(it, packageName)) attachmentController.add(it, shared = true)
            }
        }
    }

    private fun onMessageTextChanged(text: String) {
        resend.onTextChanged(text)

        updateMessageType()
        val signed = withSignature(text)
        val messageString = if (config.useSimpleCharacters) signed.normalizeString() else signed
        val segments = if (messageString.isEmpty()) null else SmsSegments.of(messageString)
        vm.segments = segments
        noteLongTextAsMmsOnce(messageString)
    }

    /** Says once that a long text goes as MMS; it does not block the send. */
    private fun noteLongTextAsMmsOnce(messageString: String) {
        // RM-46: a long text turning into MMS used to happen without a word; say so once.
        if (!vm.longMmsNoted && vm.attachments.isEmpty() && messageString.isNotBlank() && isLongMmsMessage(messageString)) {
            vm.longMmsNoted = true
            lifecycleScope.launch { vm.snackbar.showSnackbar(getString(R.string.thread_long_text_as_mms)) }
        }
    }

    private fun hasOnlyScheduledMessages(): Boolean = repository.hasOnlyScheduled()

    private fun getProviderThreadParticipants(): ThreadRecipients? {
        if (isRecycleBin || threadId <= 0L || hasOnlyScheduledMessages()) {
            return null
        }

        return getThreadParticipants(threadId, null).takeIf { it.isNotEmpty() }?.let { ThreadRecipients(it) }
    }

    private fun reconcileProviderParticipants(generation: Int): Boolean {
        // A refresh must not overwrite recipients the user is still editing in the people panel.
        if (vm.showPeople) return false
        val providerParticipants = getProviderThreadParticipants() ?: return false
        val participantsChanged = !recipients.sameAddressesAs(providerParticipants)
        // M-UI3: not into another chat loaded in place meanwhile.
        if (!repository.ifCurrent(generation) { recipients = providerParticipants }) return false

        if (participantsChanged) {
            runOnUiThread {
                maybeDisableShortCodeReply()
            }
        }

        return participantsChanged
    }

    private fun setupParticipants(generation: Int) {
        if (recipients.isEmpty()) {
            // M-UI3: one read of the list; another chat loaded in place empties it between two reads, and the result
            // is not written into that chat.
            val loaded = messages
            val found = if (loaded.isEmpty()) {
                val intentNumbers = getPhoneNumbersFromIntent()
                ThreadRecipients(ThreadRecipients.withIntentNumbers(getThreadParticipants(threadId, null), intentNumbers))
            } else {
                getProviderThreadParticipants() ?: ThreadRecipients(loaded.first().participants)
            }
            if (!repository.ifCurrent(generation) { recipients = found }) return
            runOnUiThread {
                maybeDisableShortCodeReply()
            }
        }
    }

    private fun isSpecialNumber(): Boolean = recipients.hasShortCodeWithLetters

    /** Codes and service messages: no sounds for them. */
    private fun isCodeSender(): Boolean = isSpecialNumber() || recipients.addresses.any { isNumericShortCode(it) }

    private fun maybeDisableShortCodeReply() {
        if (isSpecialNumber() && !isRecycleBin) {
            currentFocus?.clearFocus()
            hideKeyboard()
            vm.text.clearText()
            vm.replyDisabled = true
        }
    }

    private fun setupThreadTitle() {
        val title = conversation?.title
        vm.title = if (!title.isNullOrEmpty()) {
            title
        } else {
            recipients.toArrayList().getThreadTitle()
        }
        // The people may have changed: a group shows how its messages go out.
        updateMessageType()
        // Same avatar as the conversation list: the conversation's photo, else the contact's, else initials or icon.
        vm.avatarPhotoUri = if (recipients.size == 1) {
            conversation?.photoUri?.takeIf { it.isNotEmpty() } ?: recipients.contacts.first().photoUri
        } else {
            ""
        }
        updateSubtitle()
    }

    /** Second line of the top bar: the number, or the group size. The SIM lives in the message field instead. */
    private fun updateSubtitle() {
        val parts = ArrayList<String>()
        if (recipients.size == 1) {
            val number = recipients.contacts.first().phoneNumbers.firstOrNull()?.normalizedNumber
            if (!number.isNullOrEmpty() && number != vm.title) parts.add(number)
        } else if (recipients.isGroup) {
            parts.add(resources.getQuantityString(R.plurals.thread_people, recipients.size, recipients.size))
        }
        vm.subtitle = parts.takeIf { it.isNotEmpty() }?.joinToString(getString(R.string.ui_separator))
    }

    private fun setupSIMSelector() {
        if (sim.load(recipients, messages.lastOrNull())) updateSubtitle()
    }

    private fun selectSim(index: Int) {
        if (sim.select(index, recipients)) updateSubtitle()
    }

    @SuppressLint("MissingPermission")
    /**
     * Thread-27: the stored reasons of the failed texts. Read off the main thread; the main thread reuses the
     * last read.
     */
    private fun failureCodes(): Map<Long, Int> {
        if (messages.none { it.type == Telephony.Sms.MESSAGE_TYPE_FAILED && !it.isMMS }) return emptyMap()
        if (Looper.myLooper() != Looper.getMainLooper()) lastFailureCodes = SmsFailureReason.failedCodes(this, threadId)
        return lastFailureCodes
    }

    @Volatile
    private var lastFailureCodes: Map<Long, Int> = emptyMap()

    // READ_PHONE_STATE may be denied: the SIM read is guarded by runCatching (thread-17).
    @android.annotation.SuppressLint("MissingPermission")
    private fun getThreadItems(): List<ThreadItem> {
        if (isFinishing) {
            return emptyList()
        }

        val simLabels = lookups.sims {
            val activeSims = runCatching { subscriptionManagerCompat().activeSubscriptionInfoList }.getOrNull()
            simHeaderLabels(activeSims, composerPrefs)
        }
        // TU-11: only mark messages read while the user can see them.
        // I26: decided once, from the provider's messages and before they are marked read.
        if (providerLoaded && !vm.unreadResolved) {
            vm.firstUnreadId = if (isRecycleBin) null else firstUnreadMessageId(messages)
            vm.unreadResolved = true
        }
        val result = buildThreadItems(
            messages, simLabels, seen = isSeenByUser(), deliveryReportsOn = config.enableDeliveryReports,
            firstUnreadId = vm.firstUnreadId, showTapbacks = w3cPrefs.showTapbacks,
            undownloadedMms = undownloadedMms(messages),
            failureCodes = failureCodes(),
        )
        latestReactions = result.reactions
        if (!isCodeSender()) {
            chatSounds.received(result.newlyRead.map { ChatArrival(it.id, it.isMMS, it.date * 1000L) })
        }
        if (result.newlyRead.isNotEmpty()) {
            result.newlyRead.forEach { markMessageRead(it.id, it.isMMS) }
            conversationsDB.markRead(threadId)
            bus?.post(Events.RefreshConversations(threadId))
        }
        return result.items
    }

    private fun <I> launchForResult(
        launcher: ActivityResultLauncher<I>,
        input: I,
        @StringRes error: Int = org.fossify.commons.R.string.no_app_found,
    ) {
        hideKeyboard()
        try {
            // The picker or camera the user opened here is not leaving the app: coming back does not lock it again.
            appLockManager.allowOwnTrip()
            launcher.launch(input)
            ownScreenAskedMs = SystemClock.elapsedRealtime()
        } catch (e: ActivityNotFoundException) {
            showErrorToast(getString(error))
        } catch (e: Exception) {
            showErrorToast(e)
        }
    }

    private fun handleAttach(kind: AttachKind) {
        when (kind) {
            AttachKind.Photo -> launchForResult(pickMedia, PickVisualMediaRequest(PickVisualMedia.ImageAndVideo))
            AttachKind.Camera -> askCameraKind()
            AttachKind.TakePhoto -> launchCapturePhotoIntent()
            AttachKind.RecordVideo -> launchCaptureVideoIntent()
            AttachKind.RecordAudio -> launchCaptureAudioIntent()
            AttachKind.File -> launchForResult(pickDocument, "*/*")
            AttachKind.Contact -> launchForResult(pickContact, null)
            AttachKind.Location -> location.start()
            AttachKind.SavedText -> vm.showSavedTexts = true
            AttachKind.Schedule ->
                scheduleUi.pickTime(vm.scheduledMillis)
        }
    }

    /** TH-22: the one Camera entry of the attach sheet asks whether to take a photo or record a video. */
    private fun askCameraKind() {
        showChoiceDialog(
            items = listOf(
                ChoiceItem(getString(R.string.thread_attach_take_photo), AttachKind.TakePhoto),
                ChoiceItem(getString(R.string.thread_attach_record_video), AttachKind.RecordVideo),
            ),
            title = getString(R.string.thread_review_attach_camera),
        ) { handleAttach(it) }
    }

    private fun launchCapturePhotoIntent() {
        val uri = attachmentController.newCaptureUri()
        // Kept in saved state: the camera often outlives this process on low-memory phones.
        vm.capturedImageUri = uri
        launchForResult(takePicture, uri)
    }

    private fun launchCaptureVideoIntent() {
        launchForResult(recordMedia, Intent(MediaStore.ACTION_VIDEO_CAPTURE))
    }

    /** The link goes in at the cursor, on its own line; the user reads it and presses Send. */
    private fun insertLocationLink(link: String) {
        val before = vm.text.text.subSequence(0, vm.text.selection.min)
        vm.insertAtCursor(LocationSharer.placedAfter(before, link))
    }

    private fun launchCaptureAudioIntent() {
        launchForResult(recordMedia, Intent(MediaStore.Audio.Media.RECORD_SOUND_ACTION))
    }

    /** Send: asks for the SIM and the group MMS choice when needed (each once, TU-03), then sends or schedules. */
    private fun sendMessage() {
        // Blank text, or a photo still being shrunk, cannot be sent (TU-04, TU-05).
        if (!vm.canSend) return
        // Recipients edited in the panel are only final after "Done".
        if (vm.showPeople) return
        SendSteps(
            needsSim = { sim.mustAsk() },
            askSim = { onChosen ->
                showChoiceDialog(
                    items = sim.choices.mapIndexed { index, card -> ChoiceItem(card.label, index) },
                    title = getString(R.string.thread_change_sim),
                    selected = sim.selectedIndex,
                ) { index ->
                    selectSim(index)
                    onChosen()
                }
            },
            needsGroupChoice = ::needsGroupMessageChoice,
            askGroupChoice = ::askGroupMessageChoice,
            send = ::sendComposedMessage,
        ).run()
    }

    private fun sendComposedMessage() {
        // The composer may have changed while a question was open.
        if (!vm.canSend) return
        var text = withSignature(vm.text.text.toString().trim())
        vm.firstUnreadId = null
        vm.scrollToBottom()

        text = removeDiacriticsIfNeeded(text)

        val subscriptionId = sim.subscriptionId

        if (vm.isScheduling) {
            sendScheduledMessage(text, subscriptionId)
        } else {
            sendNormalMessage(text, subscriptionId)
        }
    }

    private fun needsGroupMessageChoice(): Boolean =
        !config.isGroupMessageMmsPreferenceResolved && recipients.distinctAddresses.size >= 2

    private fun askGroupMessageChoice(onChosen: () -> Unit) {
        if (isGroupMessageConfirmationVisible) return
        isGroupMessageConfirmationVisible = true
        GroupMessageSendDialog(
            activity = this,
            callback = { sendAsGroupMms ->
                config.sendGroupMessageMMS = sendAsGroupMms
                updateMessageType()
                onChosen()
            },
            onDismiss = { isGroupMessageConfirmationVisible = false }
        )
    }

    private fun sendScheduledMessage(text: String, subscriptionId: Int) {
        val scheduledMillis = vm.scheduledMillis ?: return
        if (!isSchedulableTime(scheduledMillis, System.currentTimeMillis())) {
            toast(R.string.must_pick_time_in_the_future)
            scheduleUi.pickTime(scheduledMillis)
            return
        }

        // The composer clears only after the background save: a second tap meanwhile must not save a second copy.
        if (!composerSend.tryStart()) return
        refreshedSinceSent = false
        val messageId = vm.scheduledMessageId ?: generateRandomId()
        val repeat = RepeatRule.fromName(vm.scheduledRepeat)
        // Built here on the main thread: it reads the composer's attachments.
        val pickedMessage = buildScheduledMessage(
            text = text,
            recipients = recipients,
            subscriptionId = subscriptionId,
            messageId = messageId,
            millis = scheduledMillis,
            threadId = threadId,
            threadHasMessages = messages.isNotEmpty(),
            isMms = isMmsMessage(text),
            attachments = attachmentController.asMessageAttachments(messageId),
        )
        // A thread without messages gets a temporary thread until a real message is sent.
        val startsTemporaryThread = messages.isEmpty()
        val savedConversation = conversation
        // M-UI3 (K2-2): the chat this was scheduled in; another one loaded in place meanwhile keeps its own thread.
        val generation = repository.generation
        ensureBackgroundThread {
            try {
                val message = scheduleController.save(pickedMessage, startsTemporaryThread, savedConversation, repeat)
                repository.ifCurrent(generation) {
                    if (startsTemporaryThread) threadId = message.threadId
                }
                // Saved in any case; shown only in the chat it was scheduled in (J5-4).
                insertOrUpdateMessage(message, generation)

                runOnUiThread {
                    if (repository.isStale(generation)) return@runOnUiThread
                    clearCurrentMessage()
                    vm.clearSchedule()
                }
            } catch (e: Exception) {
                // The composer keeps the text, attachments and time so the user can try again.
                showErrorToast(
                    e.localizedMessage ?: getString(org.fossify.commons.R.string.unknown_error_occurred)
                )
            } finally {
                composerSend.finish()
            }
        }
    }

    /**
     * Sends [text] with [attachments] now, on a worker thread: the provider writes, the thread lookups of a group and
     * an MMS attachment's bytes never run on the main thread (MS5). The composer is cleared only when [fromComposer]
     * and the message was handed to the system: after a failure the user's text and photos stay so they can try
     * again. While a send from the composer runs, a second tap on Send does nothing. [onDone] gets whether the message
     * was handed off, on the worker thread.
     */
    private fun sendNormalMessage(
        text: String,
        subscriptionId: Int,
        attachments: List<Attachment> = attachmentController.asMessageAttachments(),
        resendId: MessageKey? = resend.target,
        fromComposer: Boolean = true,
        onPartial: (SendOutcome) -> Unit = {},
        onDone: (Boolean) -> Unit = {},
    ) {
        if (fromComposer && !composerSend.tryStart()) return
        refreshedSinceSent = false
        var outgoing = OutgoingMessage(text, recipients.addresses, subscriptionId, attachments, resendId)
        if (fromComposer) {
            // MS6: each photo goes out as its own MMS. One that was queued leaves the composer at once, so when a
            // later one fails and the user taps Send again, only what did not go out is sent, not every photo twice.
            outgoing = outgoing.copy(onAttachmentHandedOff = { sent ->
                runOnUiThread { vm.removeAttachment(sent.uriString) }
            })
        }
        val composerAtSend = vm.text.text.toString()
        // J5-11: the chat tapped in; its sent row never lands in another chat loaded in place during the send.
        val target = SendTarget(threadId, repository.generation)
        ensureBackgroundThread {
            try {
                onDone(sendOutgoing(outgoing, target, fromComposer, composerAtSend, onPartial))
            } finally {
                if (fromComposer) composerSend.finish()
            }
        }
    }

    /** The blocking part of [sendNormalMessage]; runs on a worker thread. True when the message was handed off. */
    private fun sendOutgoing(
        outgoing: OutgoingMessage,
        target: SendTarget,
        fromComposer: Boolean,
        composerAtSend: String,
        onPartial: (SendOutcome) -> Unit = {},
    ): Boolean {
        val result = sendController.send(outgoing, fromComposer)
        result.partial?.let { partial ->
            handlePartialSend(partial, fromComposer, composerAtSend)
            onPartial(partial)
        }
        if (result.completed) {
            // A failed send that left a row in the provider still shows up below, with its Retry button.
            repository.newProviderRows(target.thread, limit = maxOf(1, outgoing.attachments.size))
                .forEach { insertOrUpdateMessage(it, target.generation) }
        }
        runOnUiThread {
            // Not when the user already typed something else meanwhile.
            if (result.clearComposer && vm.text.text.toString() == composerAtSend) clearCurrentMessage()
            if (fromComposer && result.handedOff) chatSounds.sent()
        }
        return result.handedOff
    }

    /** Some pictures went out and some did not: say so, and make sure another try sends only the rest. */
    private fun handlePartialSend(partial: SendOutcome, fromComposer: Boolean, composerAtSend: String) {
        toast(R.string.message_partly_sent, android.widget.Toast.LENGTH_LONG)
        if (!fromComposer) return
        runOnUiThread {
            attachmentController.keepOnly(partial.remainingAttachments)
            // The text went with the last picture; not when the user already typed something else meanwhile.
            if (partial.textSent && vm.text.text.toString() == composerAtSend) vm.text.clearText()
            resend.clear()
            updateMessageType()
        }
    }

    private fun clearCurrentMessage() {
        vm.text.clearText()
        attachmentController.clear()
        updateMessageType()
    }

    /** Saves [message] and shows it, unless another chat was loaded in place since [expected] (M-UI3, J5-4). */
    private fun insertOrUpdateMessage(message: Message, expected: Int) {
        if (repository.upsert(message, expected) != null) {
            val newItems = getThreadItems()
            runOnUiThread {
                if (repository.isStale(expected)) return@runOnUiThread
                publishItems(newItems)
                vm.scrollToBottom()
                if (!refreshedSinceSent) {
                    refreshMessages()
                }
            }
        }
        messagesDB.insertOrUpdate(message)
        if (shouldUnarchive()) {
            updateConversationArchivedStatus(message.threadId, false)
            refreshConversations()
        }
    }

    /** The numbers the intent opened this chat for: one, or several for a group. */
    private fun getPhoneNumbersFromIntent(): List<String> = ThreadRecipients.numbersFromExtra(intent.getStringExtra(THREAD_NUMBER))

    private fun saveMMS(attachments: List<Attachment>) {
        vm.attachmentsToSave = attachments
        if (attachments.size == 1) {
            val attachment = attachments.first()
            Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                type = attachment.mimetype
                addCategory(Intent.CATEGORY_OPENABLE)
                // The part's file name ("IMG_2041.jpg"), not the last piece of its content uri ("1234").
                putExtra(Intent.EXTRA_TITLE, saveFileName(attachment))
                launchForResult(saveAttachmentsTarget, this, error = org.fossify.commons.R.string.system_service_disabled)
            }
        } else {
            Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                addCategory(Intent.CATEGORY_DEFAULT)
                launchForResult(saveAttachmentsTarget, this, error = org.fossify.commons.R.string.system_service_disabled)
            }
        }
    }

    @Subscribe(threadMode = ThreadMode.ASYNC)
    fun refreshMessages(@Suppress("unused") event: Events.RefreshMessages) {
        // M-UI3 (K2-2): the chat this refresh is for is fixed first, before it waits its turn and before any field is
        // read; once another chat is loaded in place, it does nothing.
        val generation = repository.generation
        // ASYNC events run on a pool: one refresh at a time, so an older read cannot publish over a newer one.
        synchronized(refreshLock) { refreshMessagesLocked(generation) }
    }

    private fun refreshMessagesLocked(generation: Int) {
        if (isRecycleBin || repository.isStale(generation)) {
            return
        }

        refreshedSinceSent = true
        repository.allFetched = false

        // The notification stays while the chat is open but not seen (TU-11).
        if (isSeenByUser()) {
            notificationManager.cancel(threadId.hashCode())
            // A text that came into this open Spam chat posted a quiet notice; it is seen already (4.6).
            BlockedNotifier.cancelChat(this, threadId)
        }

        // Still loading: no recipients yet, so there is nothing to look up (an empty list would blank the thread, B13).
        if (recipients.isEmpty()) return

        val messageSnapshot = messages.toSortedMessages()
        val lastMaxId = messageSnapshot.filterNot { it.isScheduled }.maxByOrNull { it.id }?.id ?: 0L
        val providerParticipantsChanged = reconcileProviderParticipants(generation)
        // The provider is asked for the thread only while this chat has none of its own yet (scheduled messages in a
        // temporary thread, or no messages): asking every time created an empty provider thread (B13).
        val newThreadId = providerThreadIdFor(messageSnapshot)
        val newMessages = repository.newestFromProvider(newThreadId)
        val thread = threadForRefresh(messageSnapshot, newThreadId, newMessages, generation) ?: return

        // A full page means older messages exist; the ones already paged in are kept (see mergeRefreshedMessages).
        repository.refresh(thread, newMessages, generation)

        messages.filter { !it.isScheduled && !it.isReceivedMessage() && it.id > lastMaxId }
            .forEach { latestMessage ->
                messagesDB.insertOrIgnore(latestMessage)
            }

        // A new conversation (a group just started here) is saved with its first text; the menu needs it.
        if (conversation == null) adoptSavedConversation(thread, generation)
        setupAdapter(generation)
        runOnUiThread {
            if (providerParticipantsChanged) {
                setupThreadTitle()
            }
            setupSIMSelector()
        }
    }

    /**
     * The thread a refresh reads: [newThreadId] once a chat of scheduled messages only got its real provider thread
     * (the scheduled rows move there), else the chat's own. M-UI3: null, and nothing is written, once another chat was
     * loaded in place since [generation].
     */
    private fun threadForRefresh(
        messageSnapshot: List<Message>,
        newThreadId: Long,
        newMessages: List<Message>,
        generation: Int,
    ): Long? {
        val gotRealThread =
            messageSnapshot.isNotEmpty() && messageSnapshot.all { it.isScheduled } && newMessages.isNotEmpty()
        var thread = 0L
        val current = repository.ifCurrent(generation) {
            if (gotRealThread) threadId = newThreadId
            thread = threadId
        }
        if (!current) return null
        if (gotRealThread) {
            updateScheduledMessagesThreadId(
                messages = messageSnapshot.filter { it.threadId != thread },
                newThreadId = thread,
            )
        }
        return thread
    }

    /** The conversation saved with a new chat's first text, kept unless another chat was loaded in place (M-UI3). */
    private fun adoptSavedConversation(thread: Long, generation: Int) {
        val saved = conversationsDB.getConversationWithThreadId(thread) ?: return
        repository.ifCurrent(generation) { if (conversation == null) conversation = saved }
    }

    private fun providerThreadIdFor(messageSnapshot: List<Message>): Long =
        if (threadId > 0L && messageSnapshot.any { !it.isScheduled }) {
            threadId
        } else {
            getThreadId(recipients.addresses.toSet())
        }

    /** I60: the signature goes under what is typed, but not under a failed message that is being sent again. */
    private fun withSignature(text: String): String =
        if (resend.target != null) text else MessageSignature.applyTo(text, composerPrefs.signature)

    private fun isMmsMessage(text: String): Boolean = sendsAsMms(
        hasAttachments = vm.attachments.isNotEmpty(),
        recipientCount = recipients.size,
        sendGroupAsMms = config.sendGroupMessageMMS,
        isLongText = isLongMmsMessage(text),
    )

    private fun updateMessageType() {
        vm.isMms = isMmsMessage(withSignature(vm.text.text.toString()))
        vm.groupSendMode = if (recipients.distinctAddresses.size >= 2 && config.isGroupMessageMmsPreferenceResolved) {
            config.sendGroupMessageMMS
        } else {
            null
        }
    }

    /**
     * "Send now" on a scheduled bubble: sends that message as it is, straight from its own text and attachments.
     * It used to go through the composer, which threw away whatever the user was typing. The scheduled copy is
     * removed only once the message was handed off.
     */
    private fun sendScheduledMessageNow(shown: Message) {
        val addresses = recipients.addresses
        refreshedSinceSent = false
        val target = SendTarget(threadId, repository.generation)
        ensureBackgroundThread {
            // F3: the alarm may be sending this very message; whoever claims it first sends it, the other does nothing.
            if (!ScheduledSendClaims.claim(this, shown.id, allowHeld = true)) return@ensureBackgroundThread
            try {
                // K1 hand-over: the row is read again after the claim. The start-up tidy may have swapped its
                // attachments to the app's own copies meanwhile, and the tracker must not write the picker uris back.
                // A row that is gone was sent or deleted meanwhile: nothing is sent.
                val message = runCatching { messagesDB.getScheduledMessageById(shown.id) }.getOrNull()
                    ?: return@ensureBackgroundThread
                val outgoing = OutgoingMessage(
                    text = message.body,
                    addresses = addresses,
                    subscriptionId = message.subscriptionId,
                    attachments = message.attachment?.attachments.orEmpty(),
                    resendOf = null,
                    onAttachmentHandedOff = scheduledAttachmentTracker(message),
                )
                val sent = sendOutgoing(outgoing, target, fromComposer = false, composerAtSend = "") { partial ->
                    keepUnsentOfScheduled(message, partial)
                }
                if (sent) {
                    // B2-13: the scheduled row goes before the claim is released, so a re-arm in between cannot post
                    // a false "not sent" for a message that went.
                    runCatching { scheduleController.cancel(message.id) }
                    runOnUiThread { releaseScheduledAudioAndRefresh(shown, message) }
                } else {
                    // Not sent: held again, so Retry stays and no alarm sends it by itself.
                    runCatching { messagesDB.updateScheduledType(message.id, Telephony.Sms.MESSAGE_TYPE_FAILED) }
                }
            } finally {
                ScheduledSendClaims.release(shown.id)
            }
        }
    }

    /**
     * A scheduled message that went out in part stays held, but with only what was not sent (and no text when that
     * went), so the next Retry does not send the same pictures again.
     */
    private fun keepUnsentOfScheduled(message: Message, partial: SendOutcome) {
        // Runs on the send's worker thread, before the caller holds the row;
        // stays held (failed) so no alarm resends it.
        val unsent = message.withUnsentOnly(partial).copy(type = Telephony.Sms.MESSAGE_TYPE_FAILED)
        runCatching { messagesDB.insertOrUpdate(unsent) }
        refreshMessages()
    }

    /** After "Send now" went: the audio players of its attachments stop, and the chat shows it sent. */
    private fun releaseScheduledAudioAndRefresh(shown: Message, message: Message) {
        // The bubble's uris (as 0.4.14 released them) and those of the row read after the claim, if they differ.
        (shown.attachment?.attachments.orEmpty() + message.attachment?.attachments.orEmpty())
            .map { it.getUri() }.distinct().forEach { AudioPlayerManager.release(it) }
        ensureBackgroundThread { refreshMessages() }
    }

    /**
     * [keepAttachmentFiles] when the composer still shows the message's attachments (the user left schedule mode
     * while editing it); those private copies are then deleted on the next app start.
     */
    private fun cancelScheduledMessageAndRefresh(messageId: Long, keepAttachmentFiles: Boolean = false) {
        messages.firstOrNull { it.id == messageId && it.isScheduled }?.attachment?.attachments?.forEach {
            AudioPlayerManager.release(it.getUri())
        }
        ensureBackgroundThread {
            scheduleController.cancel(messageId, keepAttachmentFiles)
            refreshMessages()
        }
    }

    companion object {
        private const val JUMP_RETRY_MS = 300L
        private const val NO_TIME = -1L
        /** Boolean extra: open the "Search in conversation" bar (TH-20), for example from Conversation details. */
        const val EXTRA_OPEN_SEARCH = "com.glooboo.spamless.extra.OPEN_THREAD_SEARCH"
    }
}

/** J5-11: the chat a send was tapped in, and the load of it then (M-UI3); read on the main thread at the tap. */
private class SendTarget(val thread: Long, val generation: Int)
