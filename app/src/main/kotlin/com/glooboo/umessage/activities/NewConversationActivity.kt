package com.glooboo.umessage.activities

import android.content.Intent
import android.net.Uri
import android.util.Log
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.glooboo.umessage.R
import com.glooboo.umessage.extensions.conversationsDB
import com.glooboo.umessage.extensions.getPrivateContactsIfAvailable
import com.glooboo.umessage.extensions.getSuggestedContacts
import com.glooboo.umessage.extensions.getThreadId
import com.glooboo.umessage.helpers.HiddenChats
import com.glooboo.umessage.helpers.SmsIntentParser
import com.glooboo.umessage.helpers.THREAD_ATTACHMENT_URI
import com.glooboo.umessage.helpers.THREAD_ATTACHMENT_URIS
import com.glooboo.umessage.helpers.THREAD_ID
import com.glooboo.umessage.helpers.THREAD_NUMBER
import com.glooboo.umessage.helpers.THREAD_TEXT
import com.glooboo.umessage.helpers.THREAD_TITLE
import com.glooboo.umessage.helpers.stripControlCharacters
import com.glooboo.umessage.messaging.isShortCodeWithLetters
import com.glooboo.umessage.models.Conversation
import com.glooboo.umessage.ui.components.displayTitle
import com.glooboo.umessage.ui.newconversation.GroupRecipient
import com.glooboo.umessage.ui.newconversation.NewConversationScreen
import com.glooboo.umessage.ui.newconversation.loadPhoneContacts
import com.glooboo.umessage.ui.newconversation.typedPhoneNumber
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fossify.commons.extensions.hasPermission
import org.fossify.commons.extensions.hideKeyboard
import org.fossify.commons.extensions.toast
import org.fossify.commons.helpers.PERMISSION_READ_CONTACTS
import org.fossify.commons.models.SimpleContact

/** Recipient picker; also the entry point for sms:/mms: links and share intents (the intent extras are unchanged). */
class NewConversationActivity : ComposeSimpleActivity() {
    private var contacts by mutableStateOf<List<SimpleContact>>(emptyList())
    private var suggestions by mutableStateOf<List<SimpleContact>>(emptyList())
    private var loaded by mutableStateOf(false)
    private var hasContactsPermission by mutableStateOf(false)

    /** Share mode only: the chats offered as share targets (the screen picks the newest and filters them). */
    private var recentChats by mutableStateOf<List<Conversation>>(emptyList())
    private val isShare: Boolean
        get() = intent.action == Intent.ACTION_SEND || intent.action == Intent.ACTION_SEND_MULTIPLE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
        )
        hasContactsPermission = hasPermission(PERMISSION_READ_CONTACTS)
        setUMessageContent {
            NewConversationScreen(
                contacts = contacts,
                suggestions = suggestions,
                loaded = loaded,
                hasContactsPermission = hasContactsPermission,
                onBack = ::finish,
                onRequestPermission = {
                    handlePermission(PERMISSION_READ_CONTACTS) {
                        hasContactsPermission = it
                        if (it) fetchContacts()
                    }
                },
                onSubmitAddress = ::submitAddress,
                onPickNumber = { contact, number -> launchThreadActivity(number.normalizedNumber, contact.name) },
                onStartGroup = ::startGroup,
                shareMode = isShare,
                recentChats = recentChats,
                onPickRecent = ::openRecentChat,
            )
        }

        // READ_CONTACTS permission is not mandatory, but without it we won't be able to show any suggestions during typing
        handlePermission(PERMISSION_READ_CONTACTS) {
            hasContactsPermission = it
            initContacts()
        }
        if (isShare) loadRecentChats()
    }

    /** The saved chat list the main screen shows, read off the main thread; chats hidden by the lock stay out. */
    private fun loadRecentChats() {
        lifecycleScope.launch {
            recentChats = withContext(Dispatchers.IO) {
                runCatching {
                    HiddenChats.get(this@NewConversationActivity)
                        .withoutHidden(conversationsDB.getNonArchived()) { it.threadId }
                }.onFailure { Log.w("NewConversation", "Recent chats not loaded", it) }.getOrDefault(emptyList())
            }
        }
    }

    /** A tap on a recent chat: its thread opens with the shared content, exactly as after picking a contact. */
    private fun openRecentChat(chat: Conversation) {
        hideKeyboard()
        val number = if (chat.isGroupConversation) "" else chat.phoneNumber
        startThread(chat.threadId, number, displayTitle(this, chat.title.stripControlCharacters()), "")
        finish()
    }

    private fun initContacts() {
        if (isThirdPartyIntent()) {
            return
        }
        fetchContacts()
    }

    private fun submitAddress(number: String) {
        if (isShortCodeWithLetters(number)) {
            toast(R.string.invalid_short_code, length = Toast.LENGTH_LONG)
            return
        }
        // MC-08: a number typed on a Persian keyboard (۰۹۱۲...) or with spaces becomes plain digits.
        val address = typedPhoneNumber(number) ?: number
        launchThreadActivity(address, address)
    }

    /**
     * MC-13: "Next" in group mode opens the conversation with everyone picked; the thread reads the numbers as a
     * JSON list and sends to all of them. One person picked is simply their chat, with their name.
     */
    private fun startGroup(recipients: List<GroupRecipient>) {
        val numbers = recipients.map { it.number }
        if (numbers.isEmpty()) return
        if (numbers.any { isShortCodeWithLetters(it) }) {
            toast(R.string.invalid_short_code, length = Toast.LENGTH_LONG)
            return
        }
        launchThreadActivity(numbers.joinToString(";"), if (recipients.size == 1) recipients.first().name else "")
    }

    private fun isThirdPartyIntent(): Boolean {
        val result = SmsIntentParser.parse(intent)

        // No valid recipient (for example sms:%00): show the normal new-conversation screen, not an empty thread.
        if (result != null && result.second.isNotEmpty()) {
            val (body, recipients) = result
            launchThreadActivity(
                // SmsIntentParser already decoded them once; a second decode crashed on links such as sms:%25zz.
                phoneNumber = recipients,
                name = "",
                body = body,
                finishAfter = true,
            )
            return true
        }
        return false
    }

    /**
     * Address book from the platform Contacts provider (see [loadPhoneContacts]) plus recent-conversation suggestions,
     * all read on [Dispatchers.IO].
     */
    private fun fetchContacts() {
        val canReadContacts = hasContactsPermission
        lifecycleScope.launch {
            val loadedContacts = withContext(Dispatchers.IO) {
                val privateContacts = getPrivateContactsIfAvailable()
                val suggested = runCatching {
                    getSuggestedContacts(privateContacts).filter { it.phoneNumbers.isNotEmpty() }
                }.getOrDefault(emptyList())
                val phoneContacts = if (canReadContacts) loadPhoneContacts(this@NewConversationActivity) else emptyList()
                val all = (phoneContacts + privateContacts.filter { it.phoneNumbers.isNotEmpty() })
                    .distinctBy { it.phoneNumbers.first().normalizedNumber }
                LoadedContacts(all, suggested)
            }
            suggestions = loadedContacts.suggestions
            contacts = loadedContacts.contacts
            loaded = true
        }
    }

    private class LoadedContacts(val contacts: List<SimpleContact>, val suggestions: List<SimpleContact>)

    /**
     * Opens the chat and closes this picker, so Back from the chat returns to the list (MC-10). When Android can't make
     * a chat for the number (thread id 0) it says so and the picker stays (MC-07); a link from another app closes.
     */
    private fun launchThreadActivity(phoneNumber: String, name: String, body: String = "", finishAfter: Boolean = false) {
        hideKeyboard()
        val numbers = phoneNumber.split(";").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        if (numbers.isEmpty()) {
            toast(R.string.new_conversation_invalid_number)
            if (finishAfter) finish()
            return
        }
        val number = if (numbers.size == 1) numbers.first() else Gson().toJson(numbers)
        // getOrCreateThreadId is a Telephony provider round trip: keep it off the main thread.
        lifecycleScope.launch {
            val threadId = withContext(Dispatchers.IO) { runCatching { getThreadId(numbers) }.getOrDefault(0L) }
            if (threadId <= 0L) {
                toast(R.string.new_conversation_invalid_number, length = Toast.LENGTH_LONG)
                if (finishAfter) finish()
                return@launch
            }
            startThread(threadId, number, name, body)
            finish()
        }
    }

    private fun startThread(threadId: Long, number: String, name: String, body: String) {
        Intent(this, ThreadActivity::class.java).apply {
            putExtra(THREAD_ID, threadId)
            putExtra(THREAD_TITLE, name)
            putExtra(THREAD_TEXT, body.ifEmpty { intent.getStringExtra(Intent.EXTRA_TEXT) })
            if (number.isNotEmpty()) putExtra(THREAD_NUMBER, number)

            if (intent.action == Intent.ACTION_SEND && intent.extras?.containsKey(Intent.EXTRA_STREAM) == true) {
                val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                putExtra(THREAD_ATTACHMENT_URI, uri?.toString())
            } else if (intent.action == Intent.ACTION_SEND_MULTIPLE && intent.extras?.containsKey(
                    Intent.EXTRA_STREAM,
                ) == true
            ) {
                val uris = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                putExtra(THREAD_ATTACHMENT_URIS, uris)
            }

            startActivity(this)
        }
    }
}
