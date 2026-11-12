package com.glooboo.umessage.activities

import android.os.Bundle
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.glooboo.umessage.dialogs.RenameConversationDialog
import com.glooboo.umessage.extensions.config
import com.glooboo.umessage.extensions.conversationsDB
import com.glooboo.umessage.extensions.dialNumber
import com.glooboo.umessage.extensions.getContactFromAddress
import com.glooboo.umessage.extensions.getThreadParticipants
import com.glooboo.umessage.extensions.messagesDB
import com.glooboo.umessage.extensions.renameConversation
import com.glooboo.umessage.extensions.startContactDetailsIntent
import com.glooboo.umessage.helpers.THREAD_ID
import com.glooboo.umessage.helpers.refreshConversations
import com.glooboo.umessage.messaging.isShortCodeWithLetters
import com.glooboo.umessage.models.Conversation
import com.glooboo.umessage.models.Message
import com.glooboo.umessage.ui.details.ConversationDetailsScreen
import com.glooboo.umessage.ui.details.ConversationMediaViewModel
import com.glooboo.umessage.ui.details.ConversationSearchScreen
import com.glooboo.umessage.ui.lock.HiddenChatGate
import com.glooboo.umessage.spam.ForwardingAvailability
import com.glooboo.umessage.ui.spam.SenderBlocking
import com.glooboo.umessage.ui.spam.SpamRepositoryProvider
import com.glooboo.umessage.ui.thread.ThreadPrivacyActions
import com.glooboo.umessage.ui.thread.planSpamReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fossify.commons.extensions.copyToClipboard
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.models.SimpleContact

class ConversationDetailsActivity : ComposeSimpleActivity() {

    private var threadId: Long = 0L
    private var conversation: Conversation? = null

    private var loaded by mutableStateOf(false)
    private var name by mutableStateOf("")
    private var participants by mutableStateOf<List<SimpleContact>>(emptyList())
    private var customNotifications by mutableStateOf(false)

    /** N05: what the Mute row says; null until the chat is loaded. */
    private var muteSummary by mutableStateOf<String?>(null)
    private val privacy by lazy { ThreadPrivacyActions(this, { threadId }, onChange = refreshMute) }
    private val refreshMute: () -> Unit = { muteSummary = privacy.muteSummary() }

    /** B06: the details of a hidden chat ask for the app lock first, like the chat itself. */
    private val hiddenGate = HiddenChatGate(this) { threadId }
    private var phoneNumber by mutableStateOf("")
    private var photoUri by mutableStateOf("")
    private var isGroup by mutableStateOf(false)

    /** Whether a uMessage block rule covers this sender; null for groups and when the rules could not be read. */
    private var blocked by mutableStateOf<Boolean?>(null)

    /** ID-50: whether an allow rule covers this sender; null together with [blocked]. */
    private var allowed by mutableStateOf<Boolean?>(null)

    /** B6: the address-book entry for this sender, null when the number is not saved (or is a group). */
    private var savedContact by mutableStateOf<SimpleContact?>(null)

    /** CL-23: "Search in conversation" is open, and the chat's messages once they are loaded for it. */
    private var searching by mutableStateOf(false)
    private var searchMessages by mutableStateOf<List<Message>?>(null)

    /** I56: the chat's pictures and links, read once; the controller opens the full list, a picture or a link. */
    private val mediaModel: ConversationMediaViewModel by viewModels()
    private val mediaPage = ConversationMediaController(this, { threadId }, { savedContact != null })

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        threadId = intent.getLongExtra(THREAD_ID, 0L)
        mediaModel.load(threadId)
        customNotifications = config.customNotifications.contains(threadId.toString())
        refreshMute()
        if (savedInstanceState?.getBoolean(KEY_SEARCHING) == true || intent.getBooleanExtra(EXTRA_OPEN_SEARCH, false)) {
            openSearch()
        }

        setUMessageContent {
            mediaPage.LinkSheetHost()
            val media = mediaModel.media
            if (searching) {
                ConversationSearchScreen(
                    messages = searchMessages,
                    onBack = { searching = false },
                    onOpen = { message ->
                        openThreadAt(threadId, message)
                        finish()
                    },
                )
            } else if (mediaPage.open && media != null) {
                mediaPage.Page(media)
            } else {
                val canForward = ForwardingAvailability.isAvailable(this@ConversationDetailsActivity) &&
                    !isGroup && phoneNumber.isNotBlank()
                ConversationDetailsScreen(
                    loaded = loaded,
                    name = name,
                    participants = participants,
                    customNotifications = customNotifications,
                    onBack = ::finish,
                    onRename = ::rename,
                    onCustomNotificationsChange = ::toggleCustomNotifications,
                    onOpenChannelSettings = { openThreadChannelSettings(threadId) },
                    onOpenParticipant = { openContactOf(it) },
                    phoneNumber = phoneNumber,
                    photoUri = photoUri,
                    isGroup = isGroup,
                    canCall = phoneNumber.isNotBlank() && !isShortCodeWithLetters(phoneNumber),
                    blocked = blocked,
                    onCall = { dialNumber(phoneNumber) },
                    onCopyNumber = { copyToClipboard(phoneNumber) },
                    onAddContact = { addNumberToContacts(phoneNumber) },
                    onBlockedChange = ::setBlocked,
                    allowed = allowed,
                    allowSpoofable = phoneNumber.any { it.isLetter() },
                    onAllowedChange = ::setAllowed,
                    savedContact = savedContact != null,
                    onViewContact = { savedContact?.let { startContactDetailsIntent(it) } },
                    onSearch = ::openSearch,
                    onForwardRule = { openForwardRulesFor(phoneNumber) }.takeIf { canForward },
                    media = media,
                    mediaActions = mediaPage.actions,
                    muteSummary = muteSummary,
                    onMute = privacy::toggleMute,
                )
            }
        }

        ensureBackgroundThread {
            val loadedConversation = conversationsDB.getConversationWithThreadId(threadId)
            val members = if (loadedConversation != null && loadedConversation.isScheduled) {
                val message = messagesDB.getAnyThreadMessage(loadedConversation.threadId)
                message?.participants ?: arrayListOf()
            } else {
                getThreadParticipants(threadId, null)
            }
            runOnUiThread {
                conversation = loadedConversation
                name = loadedConversation?.title.orEmpty()
                participants = members
                phoneNumber = loadedConversation?.phoneNumber.orEmpty()
                photoUri = loadedConversation?.photoUri.orEmpty()
                isGroup = loadedConversation?.isGroupConversation == true
                loaded = true
            }
            loadSenderState(loadedConversation)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_SEARCHING, searching)
    }

    /**
     * For a one-to-one chat: the sender's block and allow rules, and (B6, B14) whether the sender is a saved contact,
     * which swaps "Add contact" for "View contact" and hides "Always allow".
     */
    private fun loadSenderState(loadedConversation: Conversation?) {
        val address = loadedConversation?.phoneNumber?.trim().orEmpty()
        if (loadedConversation == null || loadedConversation.isGroupConversation || address.isEmpty()) return
        // Without the contacts permission the lookup fails; the page then keeps "Add contact".
        runCatching { getContactFromAddress(address) { contact -> runOnUiThread { savedContact = contact } } }
        lifecycleScope.launch {
            val plan = runCatching {
                val rules = SpamRepositoryProvider.get(this@ConversationDetailsActivity).rules.first()
                withContext(Dispatchers.Default) { planSpamReport(rules, address) }
            }.getOrNull()
            blocked = plan?.alreadyBlocked
            allowed = plan?.let { it.allowRulesToRemove.isNotEmpty() }
        }
    }

    override fun onResume() {
        super.onResume()
        hiddenGate.check()
        // The system notification settings can change both while this page is in the background.
        if (threadId != 0L) {
            customNotifications = config.customNotifications.contains(threadId.toString())
            refreshMute()
        }
    }

    /** CL-23: shows the search page and loads this chat's messages for it (once). */
    private fun openSearch() {
        searching = true
        if (searchMessages != null) return
        ensureBackgroundThread {
            val messages = runCatching { messagesDB.getNonRecycledThreadMessages(threadId) }.getOrDefault(emptyList())
            runOnUiThread { searchMessages = messages }
        }
    }

    private fun setBlocked(block: Boolean) {
        val address = phoneNumber.trim()
        if (address.isEmpty()) return
        blocked = block
        // Blocking removes the sender's allow rules (they would keep the texts in the inbox).
        if (block && allowed != null) allowed = false
        lifecycleScope.launch {
            val ok = runCatching {
                val repository = SpamRepositoryProvider.get(this@ConversationDetailsActivity)
                if (block) {
                    SenderBlocking.block(repository, listOf(address))
                } else {
                    SenderBlocking.unblock(repository, address)
                }
            }.isSuccess
            if (!ok) blocked = !block
        }
    }

    /** ID-50: "Always allow this sender": an allow rule (and no block rule), or none again. */
    private fun setAllowed(allow: Boolean) {
        val address = phoneNumber.trim()
        if (address.isEmpty()) return
        val previousBlocked = blocked
        allowed = allow
        if (allow && blocked != null) blocked = false
        lifecycleScope.launch {
            val ok = runCatching {
                val repository = SpamRepositoryProvider.get(this@ConversationDetailsActivity)
                if (allow) {
                    SenderBlocking.alwaysAllow(repository, listOf(address))
                } else {
                    val rules = repository.rules.first()
                    withContext(Dispatchers.Default) { planSpamReport(rules, address) }
                        .allowRulesToRemove.forEach { repository.removeRule(it.id) }
                }
            }.isSuccess
            if (!ok) {
                allowed = !allow
                blocked = previousBlocked
            }
        }
    }

    private fun rename() {
        val current = conversation ?: return
        // CL-28: "Reset to default name" hands the sender's own name (contact name or number) back.
        val defaultName = participants.joinToString(", ") { it.name }.ifBlank { null }
        val onReset = if (defaultName != null && current.usesCustomTitle) {
            { resetName(current, defaultName) }
        } else {
            null
        }
        RenameConversationDialog(this, current, onReset) { title ->
            name = title
            ensureBackgroundThread {
                conversation = renameConversation(current, newTitle = title)
            }
        }
    }

    private fun resetName(current: Conversation, defaultName: String) {
        name = defaultName
        ensureBackgroundThread {
            val reset = current.copy(title = defaultName, usesCustomTitle = false)
            runCatching { conversationsDB.insertOrUpdate(reset) }
            conversation = reset
            refreshConversations(current.threadId)
        }
    }

    private fun toggleCustomNotifications(enabled: Boolean) {
        customNotifications = enabled
        if (enabled) {
            config.addCustomNotificationsByThreadId(threadId)
            createThreadNotificationChannel(threadId, conversation?.title)
        } else {
            config.removeCustomNotificationsByThreadId(threadId)
            removeThreadNotificationChannel(threadId)
        }
    }

    companion object {
        /** Boolean extra: open straight on "Search in conversation" (for a thread menu item). */
        const val EXTRA_OPEN_SEARCH = "com.glooboo.spamless.extra.OPEN_CONVERSATION_SEARCH"

        /** String extra on the forward rules screen: the sender a new forward rule could be prefilled with. */
        const val EXTRA_FORWARD_SENDER = "com.glooboo.spamless.extra.FORWARD_SENDER"

        private const val KEY_SEARCHING = "searching"
    }
}
