package com.glooboo.umessage.ui.thread

import android.content.Context
import android.content.Intent
import android.text.format.DateFormat
import androidx.core.content.FileProvider
import com.glooboo.umessage.R
import com.glooboo.umessage.extensions.getMessages
import com.glooboo.umessage.extensions.getOlderThreadMessages
import com.glooboo.umessage.extensions.toSortedMessages
import com.glooboo.umessage.helpers.DateStyle
import com.glooboo.umessage.helpers.w3cPrefs
import com.glooboo.umessage.models.Message
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/** [first] and [last] are the oldest and newest message in [text]; [count] how many messages it holds. */
data class SharedConversation(val text: String, val count: Int, val first: Message?, val last: Message?)

/**
 * Writes messages as plain text, one block per message: `[date] sender: text`, oldest first, with the conversation's
 * [title] on top when given. The date, sender and text are each wrapped in direction isolates, so a Persian name next
 * to an English text (or the other way round) does not reorder the brackets. A message with only an attachment says
 * [attachmentLabel]. Shared by "Share as text" (I38) and, later, the PDF export (N01).
 *
 * [dateOf] writes a message's time, [me] names the user's own messages.
 */
class ConversationTextFormatter(
    private val me: String,
    private val attachmentLabel: String,
    private val dateOf: (Message) -> String,
) {
    fun format(messages: List<Message>, title: String? = null): SharedConversation {
        val shown = messages.toSortedMessages().filter { it.body.isNotEmpty() || it.hasAttachments() }
        val blocks = shown.map { message ->
            val sender = when {
                !message.isReceivedMessage() -> me
                message.senderName.isNotBlank() -> message.senderName
                else -> message.senderPhoneNumber
            }
            val body = listOf(message.body, if (message.hasAttachments()) attachmentLabel else "")
                .filter { it.isNotEmpty() }
                .joinToString("\n")
            "[${isolate(dateOf(message))}] ${isolate(sender)}: ${isolate(body)}"
        }
        val head = title?.takeIf { it.isNotBlank() }?.let { listOf(isolate(it)) }.orEmpty()
        val text = (head + blocks).joinToString("\n\n")
        return SharedConversation(text, shown.size, shown.firstOrNull(), shown.lastOrNull())
    }

    private fun Message.hasAttachments() = attachment?.attachments?.isNotEmpty() == true

    /** First-strong isolate: the text takes its own direction and never leaks it into its neighbours. */
    private fun isolate(text: String) = "\u2068$text\u2069"
}

/** Text above this many bytes goes as a .txt file: a share intent holding more can fail with TransactionTooLarge. */
const val SHARE_TEXT_LIMIT_BYTES = 200_000

private const val MAX_SHARE_PAGES = 2_000
private const val SHARED_FILE_DIR = "shared_logs"
private const val SHARED_FILE_NAME = "conversation.txt"

/** The formatter with the user's date style (Gregorian, Jalali or both) and language. */
fun conversationTextFormatter(context: Context): ConversationTextFormatter {
    val style = context.w3cPrefs.dateStyle
    val locale = context.resources.configuration.locales[0] ?: Locale.getDefault()
    val use24Hour = DateFormat.is24HourFormat(context)
    val zone = ZoneId.systemDefault()
    return ConversationTextFormatter(
        me = context.getString(R.string.me),
        attachmentLabel = context.getString(R.string.share_text_attachment),
        dateOf = { message ->
            messageDetailsTime(
                epochMillis = message.millis(), zone = zone, use24Hour = use24Hour, locale = locale,
                jalali = style != DateStyle.GREGORIAN, both = style == DateStyle.BOTH,
            )
        },
    )
}

/** The date range of [shared] as "2 Nov 2026 to 4 Nov 2026" in the user's date style, or null for no messages. */
fun sharedDateRange(context: Context, shared: SharedConversation): String? {
    val first = shared.first ?: return null
    val last = shared.last ?: return null
    val style = context.w3cPrefs.dateStyle
    val locale = context.resources.configuration.locales[0] ?: Locale.getDefault()
    fun day(message: Message) = fullDateLabel(
        Instant.ofEpochMilli(message.millis()).atZone(ZoneId.systemDefault()).toLocalDate(), locale,
        jalali = style != DateStyle.GREGORIAN, both = style == DateStyle.BOTH,
    )
    val from = day(first)
    val to = day(last)
    return if (from == to) from else context.getString(R.string.share_text_range, from, to)
}

/** Every message of the thread from the provider, oldest first. Run it off the main thread. */
fun readWholeThread(context: Context, threadId: Long): List<Message> {
    val all = ArrayList(context.getMessages(threadId, includeScheduledMessages = false))
    repeat(MAX_SHARE_PAGES) {
        val oldest = all.minOfOrNull { it.date } ?: return all.toSortedMessages()
        val older = context.getOlderThreadMessages(threadId, oldest, all)
        if (older.isEmpty()) return all.toSortedMessages()
        all.addAll(older)
    }
    return all.toSortedMessages()
}

/** Opens the system chooser for [shared]: as text, or as a .txt file in the app cache when too long for an intent. */
fun startShareText(context: Context, shared: SharedConversation, subject: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, subject)
    if (shared.text.toByteArray().size <= SHARE_TEXT_LIMIT_BYTES) {
        send.putExtra(Intent.EXTRA_TEXT, shared.text)
    } else {
        val dir = File(context.cacheDir, SHARED_FILE_DIR).apply { mkdirs() }
        val file = File(dir, SHARED_FILE_NAME).apply { writeText(shared.text) }
        send.putExtra(Intent.EXTRA_STREAM, FileProvider.getUriForFile(context, "${context.packageName}.provider", file))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, subject).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
