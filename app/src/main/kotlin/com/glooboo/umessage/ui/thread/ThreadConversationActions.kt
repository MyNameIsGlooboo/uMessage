package com.glooboo.umessage.ui.thread

import android.content.Intent
import com.glooboo.umessage.R
import com.glooboo.umessage.dialogs.RenameConversationDialog
import com.glooboo.umessage.dialogs.ShareTextDialog
import com.glooboo.umessage.extensions.getMessages
import com.glooboo.umessage.extensions.shortcutHelper
import com.glooboo.umessage.helpers.MESSAGES_LIMIT
import com.glooboo.umessage.ui.components.ChoiceItem
import com.glooboo.umessage.ui.components.showChoiceDialog
import com.glooboo.umessage.extensions.conversationsDB
import com.glooboo.umessage.extensions.deleteConversation
import com.glooboo.umessage.extensions.dialNumber
import com.glooboo.umessage.extensions.emptyMessagesRecycleBinForConversation
import com.glooboo.umessage.extensions.markThreadMessagesUnread
import com.glooboo.umessage.extensions.renameConversation
import com.glooboo.umessage.extensions.restoreAllMessagesFromRecycleBinForConversation
import com.glooboo.umessage.extensions.updateConversationArchivedStatus
import com.glooboo.umessage.helpers.refreshConversations
import com.glooboo.umessage.models.Conversation
import com.glooboo.umessage.models.Events
import com.glooboo.umessage.ui.components.askYesNo
import org.fossify.commons.activities.BaseSimpleActivity
import org.fossify.commons.extensions.copyToClipboard
import org.fossify.commons.extensions.launchActivityIntent
import org.fossify.commons.helpers.KEY_PHONE
import org.fossify.commons.helpers.ensureBackgroundThread
import org.greenrobot.eventbus.EventBus

/**
 * The thread menu's actions on the whole conversation: delete, restore, archive, mark unread, rename, and the
 * single person's number (call, copy, add to contacts). The ones that end with the conversation gone from view
 * close the screen.
 */
class ThreadConversationActions(
    private val activity: BaseSimpleActivity,
    private val threadId: () -> Long,
    private val isRecycleBin: () -> Boolean,
    private val recipients: () -> ThreadRecipients,
    private val conversation: () -> Conversation?,
    private val title: () -> String,
    /** Shows a short note to the user (a snackbar). */
    private val showMessage: (String) -> Unit,
    /** Runs on the main thread with the renamed conversation. */
    private val onRenamed: (Conversation) -> Unit,
) {
    /** Does [work] in the background, then refreshes the conversation list and closes the screen. */
    private fun thenClose(work: () -> Unit) {
        ensureBackgroundThread {
            work()
            activity.runOnUiThread {
                refreshConversations()
                activity.finish()
            }
        }
    }

    fun askDelete() {
        activity.askYesNo(activity.getString(R.string.delete_whole_conversation_confirmation)) {
            val thread = threadId()
            val fromRecycleBin = isRecycleBin()
            thenClose {
                if (fromRecycleBin) activity.emptyMessagesRecycleBinForConversation(thread) else activity.deleteConversation(thread)
            }
        }
    }

    fun askRestoreAll() {
        activity.askYesNo(activity.getString(R.string.restore_confirmation)) {
            val thread = threadId()
            thenClose { activity.restoreAllMessagesFromRecycleBinForConversation(thread) }
        }
    }

    fun setArchived(archived: Boolean) {
        val thread = threadId()
        thenClose { activity.updateConversationArchivedStatus(thread, archived) }
    }

    fun markUnread() {
        val thread = threadId()
        ensureBackgroundThread {
            activity.conversationsDB.markUnread(thread)
            activity.markThreadMessagesUnread(thread)
            activity.runOnUiThread {
                activity.finish()
                EventBus.getDefault().post(Events.RefreshConversations())
            }
        }
    }

    fun dial() {
        activity.dialNumber(recipients().contacts.first().phoneNumbers.first().normalizedNumber)
    }

    fun copyNumber() {
        val phoneNumber = conversation()?.phoneNumber
            ?.ifEmpty { recipients().contacts.firstOrNull()?.phoneNumbers?.firstOrNull()?.value }
            ?: return
        activity.copyToClipboard(phoneNumber)
    }

    fun addNumberToContacts() {
        val phoneNumber = recipients().contacts.firstOrNull()?.phoneNumbers?.firstOrNull()?.normalizedNumber ?: return
        Intent().apply {
            action = Intent.ACTION_INSERT_OR_EDIT
            type = "vnd.android.cursor.item/contact"
            putExtra(KEY_PHONE, phoneNumber)
            activity.launchActivityIntent(this)
        }
    }

    /** I38: asks which messages, then warns before the text leaves the app. */
    fun shareAsText() {
        val thread = threadId()
        activity.showChoiceDialog(
            items = listOf(
                ChoiceItem(activity.getString(R.string.share_text_scope_whole), true),
                ChoiceItem(activity.getString(R.string.share_text_scope_last), false),
            ),
            title = activity.getString(R.string.share_text_scope_title),
        ) { whole ->
            ensureBackgroundThread {
                val messages = if (whole) {
                    readWholeThread(activity, thread)
                } else {
                    activity.getMessages(thread, includeScheduledMessages = false, limit = MESSAGES_LIMIT)
                }
                val shared = conversationTextFormatter(activity).format(messages, title())
                activity.runOnUiThread { confirmShare(shared) }
            }
        }
    }

    private fun confirmShare(shared: SharedConversation) {
        if (shared.count == 0) {
            showMessage(activity.getString(R.string.share_text_nothing))
            return
        }
        val count = activity.resources.getQuantityString(R.plurals.share_text_count, shared.count, shared.count)
        val summary = listOfNotNull(count, sharedDateRange(activity, shared)).joinToString("\n")
        ShareTextDialog(activity, summary) {
            startShareText(activity, shared, activity.getString(R.string.share_text_subject))
        }
    }

    /** I48: asks the launcher to pin this conversation; the launcher then asks the user to confirm. */
    fun addToHomeScreen() {
        val current = conversation() ?: return
        ensureBackgroundThread {
            val requested = activity.shortcutHelper.requestPin(current)
            val note = if (requested) R.string.home_shortcut_requested else R.string.home_shortcut_failed
            activity.runOnUiThread { showMessage(activity.getString(note)) }
        }
    }

    fun rename() {
        val current = conversation() ?: return
        RenameConversationDialog(activity, current) { title ->
            ensureBackgroundThread {
                val renamed = activity.renameConversation(current, newTitle = title)
                activity.runOnUiThread { onRenamed(renamed) }
            }
        }
    }
}
