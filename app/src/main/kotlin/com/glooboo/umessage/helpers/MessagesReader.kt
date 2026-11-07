package com.glooboo.umessage.helpers

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.Telephony.Mms
import android.provider.Telephony.Sms
import android.util.Base64
import com.glooboo.umessage.backup.AppDataBackupSupport
import com.glooboo.umessage.extensions.getConversationIds
import com.glooboo.umessage.models.ExportSummary
import com.glooboo.umessage.models.MessagesBackup
import com.glooboo.umessage.models.MmsAddress
import com.glooboo.umessage.models.MmsBackup
import com.glooboo.umessage.models.MmsPart
import com.glooboo.umessage.models.NoteBackup
import com.glooboo.umessage.models.NotesBackup
import com.glooboo.umessage.models.SmsBackup
import kotlinx.coroutines.runBlocking
import java.io.OutputStream

/**
 * Writes a backup of the phone's messages (as a JSON array, or as the XML of SMS Backup & Restore), one conversation
 * and one message at a time: only the message being written is in memory, so a backup of any size finishes. The JSON
 * is the format Fossify Messages writes and [MessagesImporter] reads.
 *
 * Nothing is skipped silently. A conversation, message or attachment that cannot be read is counted in the returned
 * [ExportSummary]; an attachment that cannot be read stays in the file without its bytes, so a restore can say which
 * picture is missing instead of showing a blank one.
 */
class MessagesReader(private val context: Context) {

    /** Called after each conversation with the messages written so far and the conversations done / total. */
    fun interface Progress {
        fun update(messages: Int, conversationsDone: Int, conversationsTotal: Int)
    }

    /**
     * [conversationIds] are the conversations to write (all by default); [format] decides the file. The XML format
     * wants the number of messages up front, so it counts them first. [contactName] gives the saved name of a number
     * for formats that write one.
     */
    fun export(
        output: OutputStream,
        sms: Boolean,
        mms: Boolean,
        progress: Progress = Progress { _, _, _ -> },
        isCancelled: () -> Boolean = { false },
        conversationIds: List<Long> = context.getConversationIds(),
        format: ExportFormat = ExportFormat.JSON,
        contactName: (String) -> String? = { null },
    ): ExportSummary {
        val summary = ExportSummary()
        val writer = output.bufferedWriter(Charsets.UTF_8)
        val sink = when (format) {
            ExportFormat.JSON -> JsonEntrySink(writer)
            ExportFormat.SMS_XML -> SmsXmlWriter(writer, contactName)
        }
        sink.begin(if (format == ExportFormat.SMS_XML) countMessages(conversationIds, sms, mms) else -1)
        for ((index, threadId) in conversationIds.withIndex()) {
            if (isCancelled()) {
                summary.cancelled = true
                break
            }
            if (sms) readThread(summary) { exportSms(threadId, summary, sink::write) }
            if (mms) readThread(summary) { exportMms(threadId, summary, sink::write) }
            progress.update(summary.total, index + 1, conversationIds.size)
        }
        if (format == ExportFormat.JSON && !summary.cancelled) {
            writeNotes(sink, conversationIds, sms, mms, summary)
            writeAppData(sink, conversationIds, sms, mms, summary)
        }
        sink.end()
        return summary
    }

    /**
     * The private notes of the exported chats as the last entry of the JSON file (see [NotesBackup]). Notes whose chat
     * is not known yet (restored, not yet matched) go along. A failure here never fails the backup of the messages.
     */
    private fun writeNotes(
        sink: BackupEntrySink, conversationIds: List<Long>, sms: Boolean, mms: Boolean, summary: ExportSummary,
    ) {
        val wanted = conversationIds.toSet()
        val notes = runCatching { runBlocking { context.messageNotes.exportAll() } }.getOrNull().orEmpty()
            .filter { it.threadId == 0L || it.threadId in wanted }
            .filter { if (MessageNoteKey.isMms(it.key)) mms else sms }
        if (notes.isEmpty()) return
        sink.write(NotesBackup(notes.map { NoteBackup.of(it) }))
        summary.notes = notes.size
    }

    /**
     * Stars, labels and archived chats as the entry after the notes (see [AppDataBackup]). A failure here never
     * fails the backup of the messages.
     */
    private fun writeAppData(
        sink: BackupEntrySink, conversationIds: List<Long>, sms: Boolean, mms: Boolean, summary: ExportSummary,
    ) {
        val data = runCatching {
            runBlocking { AppDataBackupSupport.of(context).collect(conversationIds.toSet(), sms, mms) }
        }.getOrNull() ?: return
        sink.write(data)
        summary.appData = data.size
    }

    private fun countMessages(conversationIds: List<Long>, sms: Boolean, mms: Boolean): Int {
        var total = 0
        for (threadId in conversationIds) {
            val args = arrayOf(threadId.toString())
            try {
                if (sms) total += countRows(Sms.CONTENT_URI, Sms._ID, Sms.THREAD_ID, args)
                if (mms) total += countRows(Mms.CONTENT_URI, Mms._ID, Mms.THREAD_ID, args)
            } catch (_: Exception) {
                // the count in the header is only a hint for the reader; the read itself reports what failed
            }
        }
        return total
    }

    private fun countRows(uri: Uri, idColumn: String, threadColumn: String, args: Array<String>): Int =
        query(uri, arrayOf(idColumn), "$threadColumn = ?", args).use { it.count }

    private inline fun readThread(summary: ExportSummary, block: () -> Unit) {
        try {
            block()
        } catch (e: java.io.IOException) {
            throw e // the output failed: the backup cannot be finished
        } catch (_: Exception) {
            summary.unreadableConversations++
        }
    }

    private fun query(uri: Uri, projection: Array<String>, selection: String, args: Array<String>): Cursor =
        context.contentResolver.query(uri, projection, selection, args, null)
            ?: error("no cursor for $uri")

    private fun exportSms(threadId: Long, summary: ExportSummary, emit: (MessagesBackup) -> Unit) {
        val projection = arrayOf(
            Sms.SUBSCRIPTION_ID, Sms.ADDRESS, Sms.BODY, Sms.DATE, Sms.DATE_SENT, Sms.LOCKED, Sms.PROTOCOL,
            Sms.READ, Sms.STATUS, Sms.TYPE, Sms.SERVICE_CENTER,
        )
        query(Sms.CONTENT_URI, projection, "${Sms.THREAD_ID} = ?", arrayOf(threadId.toString())).use { c ->
            while (c.moveToNext()) {
                val entry = try {
                    SmsBackup(
                        subscriptionId = c.long(Sms.SUBSCRIPTION_ID),
                        address = c.string(Sms.ADDRESS).orEmpty(),
                        body = c.string(Sms.BODY),
                        date = c.long(Sms.DATE),
                        dateSent = c.long(Sms.DATE_SENT),
                        locked = c.int(Sms.LOCKED),
                        protocol = c.string(Sms.PROTOCOL),
                        read = c.int(Sms.READ),
                        status = c.int(Sms.STATUS),
                        type = c.int(Sms.TYPE),
                        serviceCenter = c.string(Sms.SERVICE_CENTER),
                    )
                } catch (_: Exception) {
                    summary.unreadableMessages++
                    continue
                }
                emit(entry)
                summary.sms++
            }
        }
    }

    private fun exportMms(threadId: Long, summary: ExportSummary, emit: (MessagesBackup) -> Unit) {
        val projection = arrayOf(
            Mms._ID, Mms.CREATOR, Mms.CONTENT_TYPE, Mms.DELIVERY_REPORT, Mms.DATE, Mms.DATE_SENT, Mms.LOCKED,
            Mms.MESSAGE_TYPE, Mms.MESSAGE_BOX, Mms.READ, Mms.READ_REPORT, Mms.SEEN, Mms.TEXT_ONLY, Mms.STATUS,
            Mms.SUBJECT, Mms.SUBJECT_CHARSET, Mms.SUBSCRIPTION_ID, Mms.TRANSACTION_ID,
        )
        query(Mms.CONTENT_URI, projection, "${Mms.THREAD_ID} = ?", arrayOf(threadId.toString())).use { c ->
            while (c.moveToNext()) {
                val entry = try {
                    val mmsId = c.long(Mms._ID)
                    MmsBackup(
                        creator = c.string(Mms.CREATOR),
                        contentType = c.string(Mms.CONTENT_TYPE),
                        deliveryReport = c.int(Mms.DELIVERY_REPORT),
                        date = c.long(Mms.DATE),
                        dateSent = c.long(Mms.DATE_SENT),
                        locked = c.int(Mms.LOCKED),
                        messageType = c.int(Mms.MESSAGE_TYPE),
                        messageBox = c.int(Mms.MESSAGE_BOX),
                        read = c.int(Mms.READ),
                        readReport = c.int(Mms.READ_REPORT),
                        seen = c.int(Mms.SEEN),
                        textOnly = c.int(Mms.TEXT_ONLY),
                        status = c.string(Mms.STATUS),
                        subject = c.string(Mms.SUBJECT),
                        subjectCharSet = c.string(Mms.SUBJECT_CHARSET),
                        subscriptionId = c.long(Mms.SUBSCRIPTION_ID),
                        transactionId = c.string(Mms.TRANSACTION_ID),
                        addresses = getMmsAddresses(mmsId),
                        parts = getParts(mmsId, summary),
                    )
                } catch (_: Exception) {
                    summary.unreadableMessages++
                    continue
                }
                emit(entry)
                summary.mms++
            }
        }
    }

    private fun getParts(mmsId: Long, summary: ExportSummary): List<MmsPart> {
        val projection = arrayOf(
            Mms.Part._ID, Mms.Part.CONTENT_DISPOSITION, Mms.Part.CHARSET, Mms.Part.CONTENT_ID,
            Mms.Part.CONTENT_LOCATION, Mms.Part.CONTENT_TYPE, Mms.Part.CT_START, Mms.Part.CT_TYPE, Mms.Part.FILENAME,
            Mms.Part.NAME, Mms.Part.SEQ, Mms.Part.TEXT,
        )
        val parts = mutableListOf<MmsPart>()
        query(
            Uri.parse("content://mms/$mmsId/part"), projection, "${Mms.Part.MSG_ID} = ?", arrayOf(mmsId.toString()),
        ).use { c ->
            while (c.moveToNext()) {
                val partId = c.long(Mms.Part._ID)
                val part = MmsPart(
                    contentDisposition = c.string(Mms.Part.CONTENT_DISPOSITION),
                    charset = c.string(Mms.Part.CHARSET),
                    contentId = c.string(Mms.Part.CONTENT_ID),
                    contentLocation = c.string(Mms.Part.CONTENT_LOCATION),
                    contentType = c.string(Mms.Part.CONTENT_TYPE).orEmpty(),
                    ctStart = c.string(Mms.Part.CT_START),
                    ctType = c.string(Mms.Part.CT_TYPE),
                    filename = c.string(Mms.Part.FILENAME),
                    name = c.string(Mms.Part.NAME),
                    sequenceOrder = c.int(Mms.Part.SEQ),
                    text = c.string(Mms.Part.TEXT),
                    data = null,
                )
                // Text and layout parts live in the text column; only real attachments have bytes to copy.
                val data = if (part.isNonText()) {
                    readPart(partId) ?: run {
                        summary.unreadableAttachments++
                        null
                    }
                } else {
                    null
                }
                parts += part.copy(data = data)
            }
        }
        return parts
    }

    /** The attachment's bytes as Base64, or null when they cannot be read or there are none. */
    private fun readPart(partId: Long): String? = try {
        context.contentResolver.openInputStream(Uri.parse("content://mms/part/$partId"))?.use { stream ->
            stream.readBytes().takeIf { it.isNotEmpty() }?.let { Base64.encodeToString(it, Base64.NO_WRAP) }
        }
    } catch (_: Exception) {
        null
    }

    private fun getMmsAddresses(messageId: Long): List<MmsAddress> {
        val addresses = mutableListOf<MmsAddress>()
        query(
            Uri.parse("content://mms/$messageId/addr"), arrayOf(Mms.Addr.ADDRESS, Mms.Addr.TYPE, Mms.Addr.CHARSET),
            "${Mms.Addr.MSG_ID} = ?", arrayOf(messageId.toString()),
        ).use { c ->
            while (c.moveToNext()) {
                addresses += MmsAddress(c.string(Mms.Addr.ADDRESS).orEmpty(), c.int(Mms.Addr.TYPE), c.int(Mms.Addr.CHARSET))
            }
        }
        return addresses
    }

    private fun Cursor.index(column: String) = getColumnIndexOrThrow(column)
    private fun Cursor.string(column: String): String? = index(column).let { if (isNull(it)) null else getString(it) }
    private fun Cursor.long(column: String): Long = index(column).let { if (isNull(it)) 0L else getLong(it) }
    private fun Cursor.int(column: String): Int = index(column).let { if (isNull(it)) 0 else getInt(it) }
}
