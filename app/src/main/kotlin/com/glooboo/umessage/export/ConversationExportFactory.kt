package com.glooboo.umessage.export

import android.content.Context
import android.text.format.DateFormat
import com.glooboo.umessage.R
import com.glooboo.umessage.helpers.DateStyle
import com.glooboo.umessage.helpers.MessageNoteKey
import com.glooboo.umessage.helpers.w3cPrefs
import com.glooboo.umessage.models.Message
import com.glooboo.umessage.models.MessageNote
import com.glooboo.umessage.ui.thread.MessageNoteLines
import com.glooboo.umessage.ui.thread.fullDateLabel
import com.glooboo.umessage.ui.thread.messageDetailsTime
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/** The title and the lines under it that open an export, in the user's language. */
class ExportHeader(val title: String, val lines: List<String>)

/**
 * Builds exports with the app's words and the user's date style. Dates are written in both calendars as soon as the
 * date style is not plain Gregorian, so a reader of the file never has to convert one into the other.
 */
class ConversationExportFactory(private val context: Context, private val zone: ZoneId = ZoneId.systemDefault()) {
    private val locale: Locale = context.resources.configuration.locales[0] ?: Locale.getDefault()
    private val jalali = context.w3cPrefs.dateStyle != DateStyle.GREGORIAN
    private val use24Hour = DateFormat.is24HourFormat(context)

    fun builder() = ConversationExportBuilder(labels(), ::timeOf, ::rangeOf)

    fun timeText(epochMillis: Long): String =
        messageDetailsTime(epochMillis, zone, use24Hour, locale, jalali = jalali, both = jalali)

    private fun timeOf(message: Message) = timeText(message.millis())

    private fun rangeOf(first: Message, last: Message): String {
        fun day(message: Message) = fullDateLabel(
            Instant.ofEpochMilli(message.millis()).atZone(zone).toLocalDate(), locale, jalali = jalali, both = jalali,
        )
        val from = day(first)
        val to = day(last)
        return if (from == to) from else context.getString(R.string.share_text_range, from, to)
    }

    private fun labels() = ExportLabels(
        me = context.getString(R.string.me),
        attachmentTag = context.getString(R.string.export_conv_attachment_label),
        status = ExportStatusLabels(
            received = context.getString(R.string.export_status_received),
            sent = context.getString(R.string.export_status_sent),
            delivered = context.getString(R.string.export_status_delivered),
            failed = context.getString(R.string.export_status_failed),
            sending = context.getString(R.string.export_status_sending),
            draft = context.getString(R.string.export_status_draft),
        ),
        unnamedAttachment = context.getString(R.string.export_pdf_attachment_unnamed),
        attachmentLine = { name, type -> context.getString(R.string.export_pdf_attachment, name, type) },
    )

    /** [firstMillis] and [lastMillis] are the first and last message times: the zone line lists their offsets. */
    fun header(
        export: ConversationExport, exportedAtMillis: Long, firstMillis: Long = exportedAtMillis,
        lastMillis: Long = exportedAtMillis,
    ): ExportHeader {
        val lines = buildList {
            add(context.getString(R.string.export_pdf_with, export.title))
            if (export.numbers.isNotEmpty() && export.numbers != listOf(export.title)) {
                add(context.getString(R.string.export_pdf_numbers, export.numbers.joinToString(", ")))
            }
            export.rangeText?.let { add(context.getString(R.string.export_pdf_range, it)) }
            add(context.resources.getQuantityString(R.plurals.export_pdf_count, export.count, export.count))
            add(context.getString(R.string.export_pdf_zone, zoneText(firstMillis, lastMillis)))
            add(exportedLine(exportedAtMillis))
        }
        return ExportHeader(context.getString(R.string.export_pdf_title), lines)
    }

    fun exportedLine(exportedAtMillis: Long): String =
        context.getString(R.string.export_pdf_exported, timeText(exportedAtMillis))

    fun hashLine(export: ConversationExport): String = context.getString(R.string.export_pdf_hash, export.sha256)

    fun notCertifiedLine(): String = context.getString(R.string.export_pdf_not_certified)

    /** "Europe/London (UTC+00:00)", with every offset the messages between the two times can carry (D14). */
    private fun zoneText(firstMillis: Long, lastMillis: Long): String =
        "${zone.id} (${ZoneOffsets.text(ZoneOffsets.between(zone, firstMillis, lastMillis))})"
}

/** The notes of a chat (by note key, see MessageNotes.notesForThread) as the lines under the messages. */
fun noteLines(context: Context, notes: Map<String, MessageNote>) =
    MessageNoteLines(context.getString(R.string.note)) { notes[MessageNoteKey.of(it)]?.text }
