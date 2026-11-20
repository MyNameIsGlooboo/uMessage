package com.glooboo.umessage.helpers

import android.content.Context
import android.content.Intent
import androidx.annotation.VisibleForTesting
import androidx.core.app.Person
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.core.text.isDigitsOnly
import com.glooboo.umessage.ui.components.displayNumber
import org.fossify.commons.extensions.getMyContactsCursor
import org.fossify.commons.helpers.MyContactsContentProvider
import org.fossify.commons.helpers.SimpleContactsHelper
import org.fossify.commons.helpers.isOnMainThread
import org.fossify.commons.models.SimpleContact
import com.glooboo.umessage.activities.ThreadActivity
import com.glooboo.umessage.extensions.conversationsDB
import com.glooboo.umessage.extensions.getThreadParticipants
import com.glooboo.umessage.extensions.toPerson
import com.glooboo.umessage.models.Conversation
import com.glooboo.umessage.spam.SpamSorting
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean


class ShortcutHelper(private val context: Context) {
    val contactsHelper = SimpleContactsHelper(context)

    fun getShortcuts(): List<ShortcutInfoCompat> {
        return ShortcutManagerCompat.getDynamicShortcuts(context)
    }

    fun getShortcut(threadId: Long): ShortcutInfoCompat? {
        return getShortcuts().find { it.id == threadId.toString() }
    }

    fun buildShortcut(
        conv: Conversation,
        capabilities: List<String> = emptyList(),
    ): ShortcutInfoCompat {
        val contactsMap: HashMap<Int, SimpleContact>? = if (!isOnMainThread()) {
            val contacts = context.getMyContactsCursor(favoritesOnly = false, withPhoneNumbersOnly = true).use {
                MyContactsContentProvider.getSimpleContacts(context, it)
            }
            HashMap(contacts.associateBy { it.rawId })
        } else {
            null
        }

        val participants = context.getThreadParticipants(conv.threadId, contactsMap)
        val persons: Array<Person> = participants.map { it.toPerson(context) }.toTypedArray()
        val intent = Intent(context, ThreadActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            putExtra(THREAD_ID, conv.threadId)
            putExtra(THREAD_TITLE, conv.title)
            putExtra(IS_RECYCLE_BIN, false) // TODO: verify that thread isn't in recycle bin
            putExtra(IS_LAUNCHED_FROM_SHORTCUT, true)
            putExtra(THREAD_NUMBER, conv.phoneNumber.ifEmpty { "unknown_phone_number" })
            addCategory(Intent.CATEGORY_DEFAULT)
            addCategory(Intent.CATEGORY_BROWSABLE)
        }

        // HyperOS takes the notification title from this label, so an unsaved number is spelled as the notification
        // writes it (displayNumber), and the sender line never shows a second spelling.
        val label =
            if (conv.isGroupConversation || conv.usesCustomTitle) conv.title else displayNumber(context, conv.title)
        val shortcut = ShortcutInfoCompat.Builder(context, conv.threadId.toString()).apply {
            setShortLabel(label)
            setLongLabel(label)
            setIsConversation()
            setLongLived(true)
            setPersons(persons)
            setIntent(intent)
            setRank(1)
            if (!conv.isGroupConversation && !conv.usesCustomTitle && persons.isNotEmpty()) {
                setIcon(persons[0].icon)
            } else {
                val icon = if (conv.isGroupConversation) {
                    IconCompat.createWithAdaptiveBitmap(
                        contactsHelper.getColoredGroupIcon(conv.title).toBitmap()
                    )
                } else {
                    IconCompat.createWithAdaptiveBitmap(avatarBitmap(context, conv.title, adaptive = true))
                }
                setIcon(icon)
            }
            capabilities.forEach { c ->
                addCapabilityBinding(c)
            }
            if (!shouldPresentShortcut(conv)) {
                setRank(99)
            }
        }.build()

        return shortcut
    }

    fun buildShortcut(
        threadId: Long,
        capabilities: List<String> = emptyList(),
    ): ShortcutInfoCompat {
        val conv = if (!isOnMainThread()) {
            context.conversationsDB.getConversationWithThreadId(threadId)
        } else {
            null
        }

        if (conv == null) {
            val conv = Conversation(
                threadId = threadId,
                snippet = "",
                date = 0,
                read = false,
                title = threadId.toString(),
                photoUri = "",
                isGroupConversation = false,
                phoneNumber = "",
                isScheduled = false,
                usesCustomTitle = false,
                isArchived = false,
            )
            return buildShortcut(conv, capabilities)
        }

        return buildShortcut(conv, capabilities)
    }

    /**
     * I48: asks the launcher to pin [conv] to the home screen. False when the launcher cannot pin shortcuts or turned
     * the request down at once; true means the request went out (the launcher confirms with the user, and some
     * launchers, such as MIUI's, drop it silently until "Home screen shortcuts" is allowed). Off the main thread.
     */
    fun requestPin(conv: Conversation): Boolean {
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(context) || isHidden(conv.threadId)) return false
        return ShortcutManagerCompat.requestPinShortcut(context, buildShortcut(conv), null)
    }

    fun createOrUpdateShortcut(conv: Conversation): ShortcutInfoCompat {
        val shortcut = buildShortcut(conv)
        createOrUpdateShortcut(shortcut)
        return shortcut
    }

    fun createOrUpdateShortcut(threadId: Long): ShortcutInfoCompat {
        val shortcut = buildShortcut(threadId)
        createOrUpdateShortcut(shortcut)
        return shortcut
    }

    /**
     * B06: a hidden chat gets no shortcut: the launcher and the Share sheet would show its name. U9 (provider truth):
     * neither does a chat in Spam, nor one that may be (see [spamState]).
     */
    private fun isHidden(threadId: Long) = HiddenChats.get(context).isMarked(threadId) || spamState(threadId) != false

    /**
     * Provider truth (U9, section 5): whether [threadId] is in Spam; always false while ProviderTruthFlag is off. It
     * is a database read, so on the main thread, or when the read fails, it is not known (null) and counts as "may be".
     */
    private fun spamState(threadId: Long): Boolean? = when {
        !ProviderTruthFlag.enabled -> false
        isOnMainThread() -> null
        else -> runCatching { context.conversationsDB.isInSpam(threadId) }.getOrNull()
    }

    /** A chat that is in Spam loses the shortcut it had from before (also the cached long-lived one). */
    private fun dropIfSpam(threadId: Long) {
        if (spamState(threadId) == true) removeSpamShortcuts(listOf(threadId.toString()))
    }

    private fun removeSpamShortcuts(ids: List<String>) {
        if (ids.isEmpty()) return
        runCatching { ShortcutManagerCompat.removeDynamicShortcuts(context, ids) }
        // The long-lived copy Android keeps for conversations (Share sheet, bubbles) goes too.
        runCatching { ShortcutManagerCompat.removeLongLivedShortcuts(context, ids) }
    }

    /**
     * Provider truth (U9, section 5): removes the shortcuts of every chat in Spam, so the launcher and the Share sheet
     * do not offer them. A pinned home-screen shortcut cannot be removed by the app (U22); it opens the chat behind its
     * lock gate. Off the main thread. Nothing happens while ProviderTruthFlag is off.
     */
    fun removeSpamShortcuts() {
        if (!ProviderTruthFlag.enabled || isOnMainThread()) return
        val spam = runCatching { context.conversationsDB.getValidSpamThreads() }.getOrNull() ?: return
        if (spam.isEmpty()) return
        val ids = spam.mapTo(HashSet()) { it.threadId.toString() }
        // The dynamic ones and the cached long-lived ones (the Share sheet keeps those after a dynamic one went).
        val cached = runCatching {
            ShortcutManagerCompat.getShortcuts(
                context, ShortcutManagerCompat.FLAG_MATCH_DYNAMIC or ShortcutManagerCompat.FLAG_MATCH_CACHED,
            )
        }.getOrDefault(emptyList())
        val dynamic = runCatching { getShortcuts() }.getOrDefault(emptyList())
        removeSpamShortcuts((cached + dynamic).map { it.id }.distinct().filter { it in ids })
    }

    private fun createOrUpdateShortcut(shortcut: ShortcutInfoCompat) {
        if (isHidden(shortcut.id.toLong())) {
            dropIfSpam(shortcut.id.toLong())
            return
        }
        if (getShortcut(shortcut.id.toLong()) != null) {
            ShortcutManagerCompat.updateShortcuts(context, listOf(shortcut))
            return
        }
        ShortcutManagerCompat.pushDynamicShortcut(context, shortcut)
    }

    /**
     * Report the usage of a thread. create shortcut if it doesn't exist
     */
    fun reportSendMessageUsage(threadId: Long) {
        usageReportFault?.invoke(threadId)
        if (isHidden(threadId)) {
            dropIfSpam(threadId)
            return
        }
        val shortcut = buildShortcut(threadId, listOf("actions.intent.SEND_MESSAGE"))
        ShortcutManagerCompat.pushDynamicShortcut(context, shortcut)
    }

    fun reportReceiveMessageUsage(threadId: Long) {
        usageReportFault?.invoke(threadId)
        if (isHidden(threadId)) {
            dropIfSpam(threadId)
            return
        }
        val shortcut = buildShortcut(threadId, listOf("actions.intent.RECEIVE_MESSAGE"))
        ShortcutManagerCompat.pushDynamicShortcut(context, shortcut)
    }

    fun removeShortcutForThread(threadId: Long) {
        val shortcut = getShortcut(threadId) ?: return
        ShortcutManagerCompat.removeDynamicShortcuts(context, mutableListOf(shortcut.id))
    }

    fun shouldPresentShortcut(conv: Conversation): Boolean {
        if (conv.isGroupConversation) {
            return true
        }
        if (conv.isArchived || !isPhoneNumber(conv.phoneNumber)) {
            return false
        }
        return true
    }

    companion object {
        /**
         * M-P1 test seam: runs first in each usage report (send and receive), so a test can make the report throw and
         * check that the send or the notification still stands. Null in the app.
         */
        @VisibleForTesting
        @Volatile
        internal var usageReportFault: ((threadId: Long) -> Unit)? = null

        private val watching = AtomicBoolean(false)

        /**
         * Provider truth (U9): after every sort that moved chats, the shortcuts of chats in Spam go
         * ([removeSpamShortcuts]). Call once from App.onCreate; a second call does nothing. Nothing happens while
         * ProviderTruthFlag is off.
         */
        fun watchSpam(context: Context) {
            if (!watching.compareAndSet(false, true)) return
            val app = context.applicationContext
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                SpamSorting.changes.collect { runCatching { ShortcutHelper(app).removeSpamShortcuts() } }
            }
        }

        /**
         * MC-28: a real phone number, so its chat may be suggested in Share and the launcher. International numbers
         * (+1555...) and numbers stored with spaces or dashes count; sender names such as "BANK" and empty numbers don't.
         */
        fun isPhoneNumber(number: String): Boolean {
            val digits = number.trim().removePrefix("+").filterNot { it == ' ' || it == '-' || it == '(' || it == ')' }
            return digits.isNotEmpty() && digits.isDigitsOnly()
        }
    }
}
