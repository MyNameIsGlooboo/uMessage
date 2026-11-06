package com.glooboo.umessage.activities

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.provider.MediaStore
import android.provider.Telephony
import android.view.WindowManager
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.activity.viewModels
import androidx.annotation.StringRes
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
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
import com.glooboo.umessage.helpers.composerPrefs
import com.glooboo.umessage.helpers.generateRandomId
import com.glooboo.umessage.helpers.refreshConversations
import com.glooboo.umessage.helpers.refreshMessages
import com.glooboo.umessage.messaging.isLongMmsMessage
import com.glooboo.umessage.models.Attachment
import com.glooboo.umessage.models.Conversation
import com.glooboo.umessage.models.Events
import com.glooboo.umessage.models.Message
import com.glooboo.umessage.models.MessageKey
import com.glooboo.umessage.models.ThreadItem
import com.glooboo.umessage.ui.thread.MessageNoteStore
import com.glooboo.umessage.ui.thread.AttachKind
import com.glooboo.umessage.ui.thread.MediaSizes
import com.glooboo.umessage.ui.thread.ThreadExportController
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

    private var threadId = 0L

    /** B06: a hidden chat asks for the app lock before it shows. */
    private val hiddenGate = HiddenChatGate(this) { threadId }
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

    private var isGroupMessageConfirmationVisible = false
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
    )

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
            intent.getStringExtra(THREAD_TEXT)?.let { if (vm.text.text.isEmpty()) vm.text.setTextAndPlaceCursorAtEnd(it) }
            addAttachmentsFromIntent()
            takeSearchedMessage()?.let { jumpToMessage(it) }
            takeOpenSearch()
            applyLaunchHints()
            return
        }

        resetForNewConversation()
        readIntentExtras()
        // onResume does not run again for a chat that is already in front: the hidden-chat cover is decided here.
        hiddenGate.check()
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
        hints.notice?.let { note -> lifecycleScope.launch { vm.snackbar.showSnackbar(note) } }
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
        conversation = null
        recipients = ThreadRecipients()
        privateContacts = ArrayList()
        repository.reset()
        threadItems = emptyList()
        refreshedSinceSent = false
        isJumpingToMessage = false
        isGroupMessageConfirmationVisible = false
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
        // After the process was killed the time survives in saved state but the label does not.
        vm.scheduledMillis?.let { scheduleUi.show(it) }

        bus = EventBus.getDefault()
        bus!!.register(this)

        refreshMenuItems()
        senderActions.observeRules()
        vm.stars.attach(this, lifecycleScope)
        vm.notes.attach(MessageNoteStore(messageNotes), lifecycleScope)
        loadConversation(isFreshLaunch = savedInstanceState == null)
    }

    override fun onResume() {
        super.onResume()
        hiddenGate.check()

        isActivityVisible = true

        scheduleUi.onResume()

        ensureBackgroundThread {
            val newConv = conversationsDB.getConversationWithThreadId(threadId)
            if (newConv != null) {
                conversation = newConv
                runOnUiThread {
                    setupThreadTitle()
                    // "Conversation details" and the other entries that need the saved conversation.
                    refreshMenuItems()
                }
            }

            val smsDraft = getSmsDraft(threadId)
            if (smsDraft.isNotEmpty()) {
                runOnUiThread {
                    vm.text.setTextAndPlaceCursorAtEnd(smsDraft)
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
        isActivityVisible && hasWindowFocusNow && !hiddenGate.blocking && !LockScope.isLockedNow(this)

    private fun markReadIfSeen() {
        if (!isSeenByUser()) return
        shownToUser = true
        markThreadReadInBackground(threadId)
    }

    private fun markThreadReadInBackground(thread: Long) {
        notificationManager.cancel(thread.hashCode())
        ensureBackgroundThread {
            // I26: the unread messages are noted first; the build that does it marks them read itself.
            if (!providerLoaded && !isRecycleBin) return@ensureBackgroundThread
            // A refused provider write must not skip the saved flags or the list refresh.
            runCatching { markThreadMessagesRead(thread) }
            repository.markAllRead(thread)
            bus?.post(Events.RefreshConversations(thread))
            // A notification posted while the chat was being read would otherwise stay.
            notificationManager.cancel(thread.hashCode())
        }
    }

    override fun onPause() {
        super.onPause()
        saveDraftMessage()
        bus?.post(Events.RefreshConversations())
        isActivityVisible = false
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
        )
    }

    private fun refreshMenuItems() {
        vm.threadId = threadId
        senderActions.refreshIfPeopleChanged()
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
        ensureBackgroundThread {
            repository.loadCached(threadId, isRecycleBin)

            setupParticipants()
            setupAdapter()

            runOnUiThread {
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
        ensureBackgroundThread {
            privateContacts = MyContactsContentProvider.getSimpleContacts(this, privateCursor)

            val cachedMessagesCode = messages.hashCode()
            if (!isRecycleBin) repository.loadNewest(threadId)
            providerLoaded = true
            // The first mark-read attempt may have come before the messages were loaded. When the user already
            // saw the chat but left before this point, nobody would ask again: mark it now.
            val loadedThread = threadId
            if (shownToUser) markThreadReadInBackground(loadedThread) else runOnUiThread { markReadIfSeen() }

            val providerParticipantsChanged = reconcileProviderParticipants()
            val hasParticipantWithoutName = recipients.contacts.any { contact ->
                contact.phoneNumbers.map { it.normalizedNumber }.contains(contact.name)
            }

            try {
                if (canReuseLoadedThread(providerParticipantsChanged, cachedMessagesCode, hasParticipantWithoutName)) {
                    adoptWaitingScheduledMessages()
                    setupAdapter()
                    runOnUiThread { callback() }
                    return@ensureBackgroundThread
                }
            } catch (ignored: Exception) {
            }

            setupParticipants()

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
                val name = intent.getStringExtra(THREAD_TITLE) ?: ""
                val numbers = getPhoneNumbersFromIntent()
                if (numbers.isEmpty()) {
                    toast(org.fossify.commons.R.string.unknown_error_occurred)
                    finish()
                    return@ensureBackgroundThread
                }
                recipients = ThreadRecipients.fromNumbers(numbers, name)
            }

            adoptWaitingScheduledMessages()

            if (!isRecycleBin) {
                messages.chunked(30).forEach { currentMessages ->
                    messagesDB.insertMessages(*currentMessages.toTypedArray())
                }
            }

            setupAdapter()
            runOnUiThread {
                setupThreadTitle()
                setupSIMSelector()
                callback()
            }
        }
    }

    /**
     * Brings in messages scheduled for these people while they had no conversation yet, which waited in a temporary
     * thread of their own: without this the real conversation said "No messages yet". Runs in the background.
     */
    private fun adoptWaitingScheduledMessages() {
        if (isRecycleBin || recipients.isEmpty()) return
        val adopted = adoptScheduledMessages(threadId, recipients.addresses)
        if (adopted.isEmpty()) return
        repository.addAll(adopted)
        bus?.post(Events.RefreshConversations())
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

    /** Rebuilds the thread items from [messages] and hands them to the Compose list. */
    private fun setupAdapter() {
        val latestThreadItems = getThreadItems()
        // Saved picture sizes are read before the rows are shown, so photo bubbles start at their size (TU-25).
        if (Looper.myLooper() != Looper.getMainLooper()) MediaSizes.warm(this, messages)
        val number = recipients.singleNumber
        firstNumberIsSaved = number != null && isSavedContactNumber(number)
        senderIsStranger = unknownCard.isStranger(number, firstNumberIsSaved, recipients.isGroup)

        runOnUiThread {
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
            val handedOff = sendNormalMessage(
                text = failed.body.ifEmpty { text },
                subscriptionId = failed.subscriptionId.takeIf { it >= 0 } ?: sim.subscriptionId,
                attachments = failed.attachment?.attachments.orEmpty(),
                resendId = failedKey,
                fromComposer = false,
            )
            if (!handedOff) toast(R.string.message_not_sent_short)
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
        removeThreadMessages(
            messagesToRemove, toRecycleBin, fromRecycleBin, threadId, messages.toSortedMessages(), conversation, scheduleController,
        )?.let { threadId = it }
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
            vm.scrollToMessage(target)
            return
        }

        ensureBackgroundThread {
            if (repository.loadingOlder) return@ensureBackgroundThread
            repository.loadingOlder = true
            isJumpingToMessage = true

            var cutoff = repository.oldestDate() ?: Int.MAX_VALUE
            var found = false
            var loops = 0

            // not the best solution, but this will do for now.
            runCatching {
                while (!found && !repository.allFetched) {
                    if (repository.fetchOlder(threadId, cutoff).isEmpty() || loops >= 1000) break
                    val messageSnapshot = messages.toSortedMessages()
                    cutoff = messageSnapshot.first().date
                    found = messageSnapshot.any { it.key == target }
                    loops++
                }
            }

            val latestThreadItems = getThreadItems()
            runOnUiThread {
                publishItems(latestThreadItems)
                repository.loadingOlder = false
                vm.scrollToMessage(target)
                isJumpingToMessage = false
            }
        }
    }

    private fun loadMoreMessages() {
        val cutoff = repository.oldestDate() ?: return
        if (repository.allFetched || repository.loadingOlder) return
        repository.loadingOlder = true
        val thread = threadId
        ensureBackgroundThread {
            // A failure must not leave loadingOlder set: that stopped paging until the chat was reopened.
            val loaded = runCatching { repository.fetchOlder(thread, cutoff) }.isSuccess
            val latestThreadItems = if (loaded) getThreadItems() else null
            runOnUiThread {
                latestThreadItems?.let { publishItems(it) }
                repository.loadingOlder = false
            }
        }
    }

    private fun loadConversation(isFreshLaunch: Boolean) {
        handlePermission(PERMISSION_READ_PHONE_STATE) { granted ->
            if (granted) {
                setupComposer(isFreshLaunch)
                setupConversation()
                setupCachedMessages {
                    setupThread {
                        takeSearchedMessage()?.let { jumpToMessage(it) }
                        if (isFreshLaunch) applyLaunchHints()
                    }
                }
            } else {
                finish()
            }
        }
    }

    private fun setupConversation() {
        ensureBackgroundThread {
            conversation = conversationsDB.getConversationWithThreadId(threadId)
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
            val uri = intent.getStringExtra(THREAD_ATTACHMENT_URI)!!.toUri()
            attachmentController.add(uri)
        } else if (intent.extras?.containsKey(THREAD_ATTACHMENT_URIS) == true) {
            @Suppress("DEPRECATION", "UNCHECKED_CAST")
            (intent.getSerializableExtra(THREAD_ATTACHMENT_URIS) as? ArrayList<Uri>)?.forEach {
                attachmentController.add(it)
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

        // RM-46: Persian text needs a second SMS after only 70 characters; say so once.
        if (segments != null && segments.unicode && segments.isMultipart && !vm.unicodePartsNoted && !vm.isMms) {
            vm.unicodePartsNoted = true
            lifecycleScope.launch {
                vm.snackbar.showSnackbar(getString(R.string.thread_unicode_parts_note, segments.parts))
            }
        }

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

    private fun reconcileProviderParticipants(): Boolean {
        val providerParticipants = getProviderThreadParticipants() ?: return false
        val participantsChanged = !recipients.sameAddressesAs(providerParticipants)
        recipients = providerParticipants

        if (participantsChanged) {
            runOnUiThread {
                maybeDisableShortCodeReply()
            }
        }

        return participantsChanged
    }

    private fun setupParticipants() {
        if (recipients.isEmpty()) {
            recipients = if (messages.isEmpty()) {
                val intentNumbers = getPhoneNumbersFromIntent()
                ThreadRecipients(ThreadRecipients.withIntentNumbers(getThreadParticipants(threadId, null), intentNumbers))
            } else {
                getProviderThreadParticipants() ?: ThreadRecipients(messages.first().participants)
            }
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
    private fun getThreadItems(): List<ThreadItem> {
        if (isFinishing) {
            return emptyList()
        }

        val simLabels = simHeaderLabels(subscriptionManagerCompat().activeSubscriptionInfoList, composerPrefs)
        // TU-11: only mark messages read while the user can see them.
        // I26: decided once, from the provider's messages and before they are marked read.
        if (providerLoaded && !vm.unreadResolved) {
            vm.firstUnreadId = if (isRecycleBin) null else firstUnreadMessageId(messages)
            vm.unreadResolved = true
        }
        val result = buildThreadItems(
            messages, simLabels, seen = isSeenByUser(), deliveryReportsOn = config.enableDeliveryReports,
            firstUnreadId = vm.firstUnreadId, showTapbacks = w3cPrefs.showTapbacks,
        )
        latestReactions = result.reactions
        if (!isCodeSender()) {
            chatSounds.received(result.newlyRead.map { ChatArrival(it.id, it.isMMS, it.date * 1000L) })
        }
        if (result.newlyRead.isNotEmpty()) {
            result.newlyRead.forEach { markMessageRead(it.id, it.isMMS) }
            conversationsDB.markRead(threadId)
            bus?.post(Events.RefreshConversations())
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
        ensureBackgroundThread {
            try {
                // A thread without messages gets a temporary thread until a real message is sent.
                val startsTemporaryThread = messages.isEmpty()
                val message = scheduleController.save(pickedMessage, startsTemporaryThread, conversation, repeat)
                if (startsTemporaryThread) threadId = message.threadId
                insertOrUpdateMessage(message)

                runOnUiThread {
                    clearCurrentMessage()
                    vm.clearSchedule()
                }
            } catch (e: Exception) {
                // The composer keeps the text, attachments and time so the user can try again.
                showErrorToast(
                    e.localizedMessage ?: getString(org.fossify.commons.R.string.unknown_error_occurred)
                )
            }
        }
    }

    /**
     * Sends [text] with [attachments] now. The composer is cleared only when [fromComposer] and the message was
     * handed to the system: after a failure the user's text and photos stay so they can try again.
     * Returns whether the message was handed off.
     */
    private fun sendNormalMessage(
        text: String,
        subscriptionId: Int,
        attachments: List<Attachment> = attachmentController.asMessageAttachments(),
        resendId: MessageKey? = resend.target,
        fromComposer: Boolean = true,
    ): Boolean {
        refreshedSinceSent = false
        val outgoing = OutgoingMessage(text, recipients.addresses, subscriptionId, attachments, resendId)
        val result = sendController.send(outgoing, fromComposer)
        if (result.completed) {
            // A failed send that left a row in the provider still shows up below, with its Retry button.
            val thread = threadId
            ensureBackgroundThread {
                repository.newProviderRows(thread, limit = maxOf(1, attachments.size)).forEach { insertOrUpdateMessage(it) }
            }
        }
        if (result.clearComposer) clearCurrentMessage()
        if (fromComposer && result.handedOff) chatSounds.sent()
        return result.handedOff
    }

    private fun clearCurrentMessage() {
        vm.text.clearText()
        attachmentController.clear()
        updateMessageType()
    }

    private fun insertOrUpdateMessage(message: Message) {
        repository.upsert(message)

        val newItems = getThreadItems()
        runOnUiThread {
            publishItems(newItems)
            vm.scrollToBottom()
            if (!refreshedSinceSent) {
                refreshMessages()
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
        if (isRecycleBin) {
            return
        }

        refreshedSinceSent = true
        repository.allFetched = false

        // The notification stays while the chat is open but not seen (TU-11).
        if (isSeenByUser()) {
            notificationManager.cancel(threadId.hashCode())
        }

        val messageSnapshot = messages.toSortedMessages()
        val lastMaxId = messageSnapshot.filterNot { it.isScheduled }.maxByOrNull { it.id }?.id ?: 0L
        val providerParticipantsChanged = reconcileProviderParticipants()
        val newThreadId = getThreadId(recipients.addresses.toSet())
        val newMessages = repository.newestFromProvider(newThreadId)
        if (messageSnapshot.isNotEmpty() && messageSnapshot.all { it.isScheduled } && newMessages.isNotEmpty()) {
            // update scheduled messages with real thread id
            threadId = newThreadId
            updateScheduledMessagesThreadId(
                messages = messageSnapshot.filter { it.threadId != threadId },
                newThreadId = threadId
            )
        }

        // A full page means older messages exist; the ones already paged in are kept (see mergeRefreshedMessages).
        repository.refresh(threadId, newMessages)

        messages.filter { !it.isScheduled && !it.isReceivedMessage() && it.id > lastMaxId }
            .forEach { latestMessage ->
                messagesDB.insertOrIgnore(latestMessage)
            }

        // A new conversation (a group just started here) is saved with its first text; the menu needs it.
        if (conversation == null) conversation = conversationsDB.getConversationWithThreadId(threadId)
        setupAdapter()
        runOnUiThread {
            if (providerParticipantsChanged) {
                setupThreadTitle()
            }
            setupSIMSelector()
        }
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
    private fun sendScheduledMessageNow(message: Message) {
        val attachments = message.attachment?.attachments.orEmpty()
        val handedOff = sendNormalMessage(
            text = message.body,
            subscriptionId = message.subscriptionId,
            attachments = attachments,
            resendId = null,
            fromComposer = false,
        )
        if (handedOff) cancelScheduledMessageAndRefresh(message.id)
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
        /** Boolean extra: open the "Search in conversation" bar (TH-20), for example from Conversation details. */
        const val EXTRA_OPEN_SEARCH = "com.glooboo.spamless.extra.OPEN_THREAD_SEARCH"
    }
}
