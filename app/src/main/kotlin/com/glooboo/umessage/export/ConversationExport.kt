package com.glooboo.umessage.export

import android.provider.Telephony.Sms
import com.glooboo.umessage.extensions.toSortedMessages
import com.glooboo.umessage.models.Message
import com.glooboo.umessage.ui.thread.ConversationTextFormatter
import java.security.MessageDigest

enum class ExportDirection { RECEIVED, SENT }

/** One message as the export shows it. [time], [from], [to], [status] and [attachments] are ready to print. */
data class ExportRow(
    val time: String,
    val direction: ExportDirection,
    val from: String,
    val to: String,
    val status: String,
    val body: String,
    val attachments: List<String>,
)

/** The status words of a message in the export. */
class ExportStatusLabels(
    val received: String,
    val sent: String,
    val delivered: String,
    val failed: String,
    val sending: String,
    val draft: String,
)

/** The words the export uses, so the builder itself stays free of Android resources. */
class ExportLabels(
    val me: String,
    /** Stands in for the picture or file of a message that has no text, in the plain text block. */
    val attachmentTag: String,
    val status: ExportStatusLabels,
    val unnamedAttachment: String,
    /** "Attachment: name (type)". */
    val attachmentLine: (name: String, type: String) -> String,
)

/**
 * A conversation ready to be written as a PDF or as text. [textBlock] is the messages as the share-as-text feature
 * writes them (oldest first, `[date] sender: text`), and [sha256] is the SHA-256 of that text in UTF-8, so the same
 * hash comes out of the PDF and of the text file and anyone can check a text file against it.
 */
class ConversationExport(
    val title: String,
    val numbers: List<String>,
    /** "from the first to the last message", or null when there are no messages. */
    val rangeText: String?,
    val rows: List<ExportRow>,
    val textBlock: String,
    val sha256: String,
) {
    val count: Int get() = rows.size
}

/** Turns messages into a [ConversationExport]. Pure: the caller supplies the date text and the labels. */
class ConversationExportBuilder(
    private val labels: ExportLabels,
    private val dateOf: (Message) -> String,
    private val rangeOf: (first: Message, last: Message) -> String,
) {
    fun build(messages: List<Message>, title: String, numbers: List<String>): ConversationExport {
        val shown = messages.toSortedMessages().filter { it.body.isNotEmpty() || it.hasAttachments() }
        val textBlock = ConversationTextFormatter(labels.me, labels.attachmentTag, dateOf).format(shown).text
        val first = shown.firstOrNull()
        val last = shown.lastOrNull()
        return ConversationExport(
            title = title,
            numbers = numbers,
            rangeText = if (first != null && last != null) rangeOf(first, last) else null,
            rows = shown.map { row(it, numbers) },
            textBlock = textBlock,
            sha256 = sha256(textBlock),
        )
    }

    private fun row(message: Message, numbers: List<String>): ExportRow {
        val received = message.isReceivedMessage()
        val attachments = message.attachment?.attachments.orEmpty().map {
            labels.attachmentLine(it.filename.ifBlank { labels.unnamedAttachment }, it.mimetype)
        }
        return ExportRow(
            time = dateOf(message),
            direction = if (received) ExportDirection.RECEIVED else ExportDirection.SENT,
            from = if (received) person(message) else labels.me,
            to = if (received) labels.me else numbers.joinToString(", "),
            status = statusOf(message),
            body = message.body,
            attachments = attachments,
        )
    }

    /** The sender with the full number: "Name (+15550001234)", or just the number when there is no name. */
    private fun person(message: Message): String {
        val number = message.senderPhoneNumber
        val name = message.senderName
        return if (name.isBlank() || name == number) number else "$name ($number)"
    }

    /** Received, or how far a sent message got. A picture message never claims delivery: the phone has no report. */
    fun statusOf(message: Message): String = when {
        message.isReceivedMessage() -> labels.status.received
        message.type == Sms.MESSAGE_TYPE_FAILED || message.status == Sms.STATUS_FAILED -> labels.status.failed
        message.type == Sms.MESSAGE_TYPE_OUTBOX || message.type == Sms.MESSAGE_TYPE_QUEUED -> labels.status.sending
        message.type == Sms.MESSAGE_TYPE_DRAFT -> labels.status.draft
        message.status == Sms.STATUS_COMPLETE && !message.isMMS -> labels.status.delivered
        else -> labels.status.sent
    }

    private fun Message.hasAttachments() = attachment?.attachments?.isNotEmpty() == true

    companion object {
        fun sha256(text: String): String =
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }
}
