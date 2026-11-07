package com.glooboo.umessage.helpers

import android.content.Context
import android.util.Xml
import com.glooboo.umessage.backup.AppDataBackupSupport
import com.glooboo.umessage.backup.BlockedBackupSupport
import com.glooboo.umessage.models.AppDataBackup
import com.glooboo.umessage.models.BlockedBackup
import com.glooboo.umessage.models.ImportSummary
import com.glooboo.umessage.models.MessagesBackup
import com.glooboo.umessage.models.MmsBackup
import com.glooboo.umessage.models.NotesBackup
import com.glooboo.umessage.models.SmsBackup
import com.glooboo.umessage.models.WriteOutcome
import com.glooboo.umessage.models.WriteResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.DecodeSequenceMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeToSequence
import org.fossify.commons.helpers.isUpsideDownCakePlus
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import java.io.BufferedInputStream
import java.io.FilterInputStream
import java.io.InputStream

/**
 * Restores messages from a backup file, reading it as a stream: one entry is in memory at a time, so a backup of any
 * size can be restored. Two formats are understood, told apart by their first character:
 *
 * - this app's JSON (an array of SMS and MMS entries, also written by Fossify Messages);
 * - the XML of "SMS Backup & Restore" (its SMS only; its picture messages are counted and reported, not restored).
 *
 * Every entry ends up in one bucket of the returned [ImportSummary]. A damaged entry is skipped and counted, and the
 * restore goes on; a file that stops making sense part way keeps everything before that point and says so. Nothing
 * is shown from here: the caller turns the summary into one message.
 *
 * Restoring is idempotent (see [MessagesWriter]), so running the same file again only adds what is missing.
 */
class MessagesImporter(
    private val context: Context,
    private val writer: MessagesWriter = MessagesWriter(context),
) {
    data class Options(val sms: Boolean, val mms: Boolean)

    /** Called every few entries with the number of entries read and bytes consumed so far. */
    fun interface Progress {
        fun update(entries: Int, bytesRead: Long)
    }

    private val json = Json { ignoreUnknownKeys = true }

    fun import(
        input: InputStream,
        options: Options,
        progress: Progress = Progress { _, _ -> },
        isCancelled: () -> Boolean = { false },
    ): ImportSummary {
        val summary = ImportSummary()
        val counting = CountingInputStream(input)
        val stream = BufferedInputStream(counting, BUFFER)
        try {
            when (peekFormat(stream)) {
                '[' -> importJson(stream, options, summary, counting, progress, isCancelled)
                '<' -> importXml(stream, options, summary, counting, progress, isCancelled)
                else -> summary.invalidFile = true
            }
        } finally {
            try {
                writer.fixConversationDates()
            } catch (_: Exception) {
                // the messages are written; only the conversation dates may be off until the next message
            }
            if (summary.changedAnything) {
                // The restored messages went to the provider only; cache them for search (DL-10).
                MessageCacheImport.restart(context)
                refreshConversations()
            }
        }
        return summary
    }

    /**
     * The first meaningful character of the file, skipping white space. A UTF-8 byte order mark is consumed, since
     * neither parser expects one; everything else is left in the stream.
     */
    private fun peekFormat(stream: BufferedInputStream): Char? {
        stream.mark(PEEK_LIMIT)
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val head = ByteArray(3)
        val hasBom = stream.read(head) == 3 && head.contentEquals(bom)
        stream.reset()
        if (hasBom) stream.skip(3)
        stream.mark(PEEK_LIMIT)
        try {
            repeat(PEEK_LIMIT) {
                val b = stream.read()
                if (b == -1) return null
                if (!Character.isWhitespace(b)) return b.toChar()
            }
            return null
        } finally {
            stream.reset()
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun importJson(
        stream: InputStream, options: Options, summary: ImportSummary, counting: CountingInputStream,
        progress: Progress, isCancelled: () -> Boolean,
    ) {
        val entries = json.decodeToSequence(stream, JsonElement.serializer(), DecodeSequenceMode.ARRAY_WRAPPED)
            .iterator()
        while (true) {
            if (isCancelled()) {
                summary.cancelled = true
                return
            }
            val element = try {
                if (!entries.hasNext()) return
                entries.next()
            } catch (_: Exception) {
                // Broken JSON: nothing after this point can be read. What came before is kept.
                if (summary.entriesRead == 0) summary.invalidFile = true else summary.stoppedEarly = true
                return
            }
            summary.entriesRead++
            val backup = try {
                json.decodeFromJsonElement(MessagesBackup.serializer(), element)
            } catch (_: Exception) {
                summary.damaged++
                null
            }
            if (backup is NotesBackup) {
                summary.entriesRead--
                restoreNotes(backup, options, summary)
            } else if (backup is AppDataBackup) {
                summary.entriesRead--
                restoreAppData(backup, options, summary)
            } else if (backup is BlockedBackup) {
                summary.entriesRead--
                restoreBlocked(backup, options, summary)
            } else if (backup != null) {
                restore(backup, options, summary)
            }
            if (summary.entriesRead % PROGRESS_EVERY == 0) progress.update(summary.entriesRead, counting.count)
        }
    }

    private fun restore(backup: MessagesBackup, options: Options, summary: ImportSummary) {
        val result = try {
            when (backup) {
                is SmsBackup -> if (options.sms) writer.writeSmsMessage(backup.forThisPhone()) else null
                is MmsBackup -> if (options.mms) writer.writeMmsMessage(backup.forThisPhone()) else null
                // handled by their own functions, never reach here
                is NotesBackup, is AppDataBackup, is BlockedBackup -> null
            }
        } catch (e: Exception) {
            WriteResult(WriteOutcome.FAILED, e.javaClass.simpleName)
        }
        if (result == null) summary.skippedByChoice++ else summary.record(result)
    }

    /**
     * Takes the notes of the file for the messages the user chose to restore (a key says SMS or MMS). A newer note
     * on this phone wins, see [MessageNotes.restore]. A failure leaves the notes out and never stops the restore.
     */
    private fun restoreNotes(backup: NotesBackup, options: Options, summary: ImportSummary) {
        val chosen = backup.notes
            .filter { if (MessageNoteKey.isMms(it.key)) options.mms else options.sms }
            .map { it.toNote() }
        summary.notesRestored += runCatching { runBlocking { context.messageNotes.restore(chosen) } }.getOrDefault(0)
    }

    /**
     * Takes the stars, labels, archived, muted and hidden chats, drafts, recycle-bin names, scheduled messages,
     * reminders and saved texts of the file. They only add to what the phone has, and they are restored last in the
     * file, after the messages their chats are found by. A failure never stops the restore.
     */
    private fun restoreAppData(backup: AppDataBackup, options: Options, summary: ImportSummary) {
        val chosen = backup.copy(
            stars = backup.stars.filter { if (it.isMms) options.mms else options.sms },
            recycled = backup.recycled.filter { if (it.isMms) options.mms else options.sms },
        )
        summary.appDataRestored += runCatching {
            runBlocking { AppDataBackupSupport.of(context).restore(chosen) }
        }.getOrDefault(0)
    }

    /**
     * Puts a blocked message back in the Blocked folder (the kinds the user chose). It never reaches the inbox or the
     * system store, sends nothing and shows no notification. A failure leaves the message out and goes on.
     */
    private fun restoreBlocked(backup: BlockedBackup, options: Options, summary: ImportSummary) {
        if (!(if (backup.isMms) options.mms else options.sms)) return
        if (runCatching { BlockedBackupSupport.of(context).restore(backup) }.getOrDefault(false)) {
            summary.appDataRestored++
        }
    }

    private fun importXml(
        stream: InputStream, options: Options, summary: ImportSummary, counting: CountingInputStream,
        progress: Progress, isCancelled: () -> Boolean,
    ) {
        val parser = Xml.newPullParser()
        try {
            parser.setInput(stream.bufferedReader())
            parser.nextTag()
            parser.require(XmlPullParser.START_TAG, null, "smses")
        } catch (_: Exception) {
            summary.invalidFile = true
            return
        }
        try {
            while (true) {
                if (isCancelled()) {
                    summary.cancelled = true
                    return
                }
                val event = parser.next()
                if (event == XmlPullParser.END_DOCUMENT) return
                if (event != XmlPullParser.START_TAG) continue
                when (parser.name) {
                    "sms" -> {
                        summary.entriesRead++
                        val sms = try {
                            parser.readSms()
                        } catch (_: Exception) {
                            summary.damaged++
                            null
                        }
                        if (sms != null) restore(sms, options, summary)
                        parser.skipToEnd()
                    }

                    "mms" -> {
                        summary.entriesRead++
                        if (options.mms) summary.unsupportedMms++ else summary.skippedByChoice++
                        parser.skipToEnd()
                    }

                    else -> Unit // a wrapper element; its children are read by the loop
                }
                if (summary.entriesRead % PROGRESS_EVERY == 0) progress.update(summary.entriesRead, counting.count)
            }
        } catch (_: XmlPullParserException) {
            summary.stoppedEarly = true
        } catch (_: java.io.IOException) {
            summary.stoppedEarly = true
        }
    }

    private fun XmlPullParser.readSms(): SmsBackup {
        fun attr(name: String): String? = getAttributeValue(null, name)?.takeUnless { it == "null" }
        // The exporter writes the text null for a missing service center or protocol; a body that says null is a body.
        fun raw(name: String): String? = getAttributeValue(null, name)
        val address = attr("address")?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("no address")
        val date = attr("date")?.toLongOrNull() ?: throw IllegalArgumentException("no date")
        val type = attr("type")?.toIntOrNull() ?: throw IllegalArgumentException("no type")
        return SmsBackup(
            subscriptionId = 0,
            address = address,
            body = raw("body"),
            date = date,
            // SMS Backup & Restore writes the network sent time as date_sent; older files may lack it.
            dateSent = attr("date_sent")?.toLongOrNull() ?: date,
            locked = attr("locked")?.toIntOrNull() ?: 0,
            protocol = attr("protocol"),
            read = attr("read")?.toIntOrNull() ?: 1,
            status = attr("status")?.toIntOrNull() ?: -1,
            type = type,
            serviceCenter = attr("service_center"),
        )
    }

    /** Moves past the end of the element the parser is on, whatever it contains. */
    private fun XmlPullParser.skipToEnd() {
        if (eventType != XmlPullParser.START_TAG) return
        var depth = 1
        while (depth != 0) {
            when (next()) {
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_DOCUMENT -> throw XmlPullParserException("file ends inside an element")
            }
        }
    }

    private class CountingInputStream(input: InputStream) : FilterInputStream(input) {
        @Volatile var count = 0L
            private set

        override fun read(): Int = super.read().also { if (it >= 0) count++ }
        override fun read(b: ByteArray, off: Int, len: Int): Int =
            super.read(b, off, len).also { if (it > 0) count += it }

        override fun skip(n: Long): Long = super.skip(n).also { count += it }
    }

    private companion object {
        const val BUFFER = 64 * 1024
        const val PEEK_LIMIT = 64
        const val PROGRESS_EVERY = 25
    }
}

// Android 14 does not import messages whose subscription id is not one of this phone's (see Fossify #191).
private fun SmsBackup.forThisPhone() = if (isUpsideDownCakePlus()) copy(subscriptionId = -1) else this
private fun MmsBackup.forThisPhone() = if (isUpsideDownCakePlus()) copy(subscriptionId = -1) else this
