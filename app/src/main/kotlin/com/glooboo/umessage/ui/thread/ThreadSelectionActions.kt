package com.glooboo.umessage.ui.thread

import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.tts.TextToSpeech
import android.text.format.DateUtils
import com.glooboo.umessage.R
import com.glooboo.umessage.activities.NewConversationActivity
import com.glooboo.umessage.dialogs.DeleteConfirmationDialog
import com.glooboo.umessage.dialogs.MessageDetailsDialog
import com.glooboo.umessage.dialogs.askForReminder
import com.glooboo.umessage.dialogs.ShareTextDialog
import com.glooboo.umessage.dialogs.SelectTextDialog
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.lifecycle.lifecycleScope
import com.glooboo.umessage.extensions.config
import com.glooboo.umessage.helpers.AndroidSpeechEngine
import com.glooboo.umessage.helpers.MessageSpeaker
import com.glooboo.umessage.helpers.canReadAloud
import com.glooboo.umessage.helpers.w3aPrefs
import kotlinx.coroutines.launch
import java.util.Locale
import com.glooboo.umessage.models.Attachment
import com.glooboo.umessage.models.Message
import com.glooboo.umessage.ui.components.askYesNo
import org.fossify.commons.activities.BaseSimpleActivity
import org.fossify.commons.extensions.copyToClipboard
import org.fossify.commons.extensions.shareTextIntent
import org.fossify.commons.extensions.showErrorToast
import org.fossify.commons.helpers.ensureBackgroundThread

/**
 * The text "Copy" puts on the clipboard: one message's text as it is, several as "[date] sender: text" blocks.
 * [dateOf] formats a message's time; [me] names the user's own messages.
 */
fun copiedText(selected: List<Message>, dateOf: (Message) -> String, me: String): String =
    if (selected.size == 1) {
        selected.first().body
    } else {
        selected.filter { it.body.isNotEmpty() }.joinToString("\n\n") { message ->
            val sender = if (message.isReceivedMessage()) message.senderName else me
            "[${dateOf(message)}] $sender: ${message.body}"
        }
    }

/**
 * What the selection bar does with the selected messages. [saveAttachments] asks where to save attachments;
 * [deleteMessages] removes (or, from the recycle bin, restores) messages in the background.
 */
class ThreadSelectionActions(
    private val activity: BaseSimpleActivity,
    private val vm: ThreadViewModel,
    private val isRecycleBin: () -> Boolean,
    private val saveAttachments: (List<Attachment>) -> Unit,
    private val deleteMessages: (messages: List<Message>, toRecycleBin: Boolean, fromRecycleBin: Boolean) -> Unit,
) {
    private val speaker = MessageSpeaker(
        newEngine = { AndroidSpeechEngine(activity) },
        appLocale = { activity.resources.configuration.locales[0] ?: Locale.getDefault() },
        onNoVoice = ::offerVoiceInstall,
        onFailed = { say(activity.getString(R.string.read_aloud_failed)) },
        onSpeakingChanged = { vm.readingAloudId = it },
    )

    /** Stops reading and frees the speech engine; called when the screen closes. */
    fun release() = speaker.release()

    fun handle(action: SelectionAction) {
        val selected = vm.selectedMessages
        if (selected.isEmpty()) return
        when (action) {
            SelectionAction.Copy -> copy(selected)
            SelectionAction.Share -> share(selected)
            SelectionAction.ReadAloud -> readAloud(selected.first())
            SelectionAction.SaveAs -> saveSelected(selected)
            SelectionAction.Forward -> forward(selected.first())
            SelectionAction.Star -> vm.stars.toggle(selected)
            SelectionAction.SelectText -> selectText(selected.first().body)
            else -> handleSingleOrRemove(action, selected)
        }
    }

    /** The actions on the first selected message, then delete and restore. */
    private fun handleSingleOrRemove(action: SelectionAction, selected: List<Message>) {
        when (action) {
            SelectionAction.Details -> MessageDetailsDialog(activity, selected.first())
            SelectionAction.Remind -> askForReminder(activity, selected.first())
            SelectionAction.SaveText -> saveAsSavedText(activity, selected.first().body)
            SelectionAction.Note -> vm.notes.startEdit(selected.first())
            SelectionAction.Delete -> askDelete(selected)
            SelectionAction.Restore -> askRestore(selected)
            else -> Unit
        }
    }

    private fun saveSelected(selected: List<Message>) {
        val attachments = selected.flatMap { it.attachment?.attachments.orEmpty() }
        if (attachments.isNotEmpty()) saveAttachments(attachments)
    }

    private fun selectText(body: String) {
        if (body.trim().isNotEmpty()) SelectTextDialog(activity, body)
    }

    private fun copy(selected: List<Message>) {
        val flags = DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR or DateUtils.FORMAT_SHOW_TIME
        val text = copiedText(selected, { DateUtils.formatDateTime(activity, it.millis(), flags) }, activity.getString(R.string.me))
        if (text.isNotEmpty()) activity.copyToClipboard(text)
    }

    /** One message goes as it is; several go as "[date] sender: text" blocks, after a warning (I38). */
    private fun share(selected: List<Message>) {
        if (selected.size == 1) {
            activity.shareTextIntent(selected.first().body)
            return
        }
        val shared = conversationTextFormatter(activity).format(selected)
        if (shared.count == 0) return
        val count = activity.resources.getQuantityString(R.plurals.share_text_count, shared.count, shared.count)
        val summary = listOfNotNull(count, sharedDateRange(activity, shared)).joinToString("\n")
        ShareTextDialog(activity, summary) {
            startShareText(activity, shared, activity.getString(R.string.share_text_subject))
        }
    }

    private fun readAloud(message: Message) {
        val hasCode = detectCopyTokens(message.body).isNotEmpty()
        if (!canReadAloud(message.body, activity.w3aPrefs.hideCodesOnLockScreen, hasCode)) return
        speaker.toggle(message.getStableId(), message.body)
    }

    private fun say(text: String) {
        activity.lifecycleScope.launch { vm.snackbar.showSnackbar(text) }
    }

    /** No voice for the message's language: says so, with a button to the system's voice download (I49). */
    private fun offerVoiceInstall(locale: Locale) {
        val language = locale.getDisplayLanguage(activity.resources.configuration.locales[0] ?: Locale.getDefault())
        activity.lifecycleScope.launch {
            val result = vm.snackbar.showSnackbar(
                message = activity.getString(R.string.read_aloud_no_voice, language),
                actionLabel = activity.getString(R.string.read_aloud_install),
                duration = SnackbarDuration.Long,
            )
            if (result == SnackbarResult.ActionPerformed) {
                try {
                    activity.startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA))
                } catch (_: ActivityNotFoundException) {
                    say(activity.getString(R.string.read_aloud_install_missing))
                }
            }
        }
    }

    private fun forward(message: Message) {
        val attachment = message.attachment?.attachments?.firstOrNull()
        Intent(activity, NewConversationActivity::class.java).apply {
            action = Intent.ACTION_SEND
            putExtra(Intent.EXTRA_TEXT, message.body)
            if (attachment != null) putExtra(Intent.EXTRA_STREAM, attachment.getUri())
            activity.startActivity(this)
        }
    }

    /** "3 messages", or null after showing the error (an UnknownFormatConversionException was once reported). */
    private fun countLabel(count: Int): String? = try {
        activity.resources.getQuantityString(R.plurals.delete_messages, count, count)
    } catch (e: Exception) {
        activity.showErrorToast(e)
        null
    }

    private fun askDelete(selected: List<Message>) {
        val items = countLabel(selected.size) ?: return
        val useRecycleBin = activity.config.useRecycleBin && !isRecycleBin()
        val baseString = if (useRecycleBin) {
            org.fossify.commons.R.string.move_to_recycle_bin_confirmation
        } else {
            org.fossify.commons.R.string.deletion_confirmation
        }
        val question = String.format(activity.resources.getString(baseString), items)
        DeleteConfirmationDialog(activity, question, useRecycleBin) { skipRecycleBin ->
            ensureBackgroundThread {
                val toRecycleBin = !skipRecycleBin && activity.config.useRecycleBin && !isRecycleBin()
                deleteMessages(selected, toRecycleBin, false)
            }
        }
    }

    private fun askRestore(selected: List<Message>) {
        val items = countLabel(selected.size) ?: return
        val question = String.format(activity.resources.getString(R.string.restore_confirmation), items)
        activity.askYesNo(question) {
            ensureBackgroundThread { deleteMessages(selected, false, true) }
        }
    }
}
