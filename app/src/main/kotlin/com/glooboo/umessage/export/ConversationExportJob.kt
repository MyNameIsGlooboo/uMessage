package com.glooboo.umessage.export

import android.content.Context
import com.glooboo.umessage.R
import com.glooboo.umessage.helpers.messageNotes
import com.glooboo.umessage.ui.thread.readWholeThread
import kotlinx.coroutines.runBlocking
import java.io.File
import java.time.ZoneId

enum class ConversationFileFormat(val extension: String, val mimeType: String) {
    PDF("pdf", "application/pdf"),
    TEXT("txt", "text/plain"),
}

/**
 * Reads one conversation, builds the export and writes the file in the app cache, ready to be shared. Meant for a
 * background thread. Files of earlier exports are deleted after a day, so old conversations do not pile up there.
 */
class ConversationExportJob(private val context: Context) {

    sealed interface Result {
        class Done(val file: File, val export: ConversationExport, val mimeType: String) : Result
        data object Empty : Result
        data object Cancelled : Result
    }

    fun run(
        threadId: Long,
        title: String,
        numbers: List<String>,
        format: ConversationFileFormat,
        range: ExportRange,
        isCancelled: () -> Boolean,
    ): Result {
        val now = System.currentTimeMillis()
        val factory = ConversationExportFactory(context)
        val messages = range.filter(readWholeThread(context, threadId), now, ZoneId.systemDefault())
        val notes = runCatching { runBlocking { context.messageNotes.notesForThread(threadId) } }
            .getOrDefault(emptyMap())
        val export = factory.builder().build(messages, title, numbers, noteLines(context, notes))
        if (export.count == 0) return Result.Empty
        if (isCancelled()) return Result.Cancelled
        val file = newFile(title, format, now)
        val header = factory.header(
            export, now, firstMillis = messages.minOfOrNull { it.millis() } ?: now,
            lastMillis = messages.maxOfOrNull { it.millis() } ?: now,
        )
        val finished = when (format) {
            ConversationFileFormat.PDF -> file.outputStream().buffered().use { out ->
                ConversationPdfWriter(context).write(export, pdfTexts(factory, export, header, now), out, isCancelled)
            }

            ConversationFileFormat.TEXT -> {
                val footer = listOf(factory.hashLine(export), factory.notCertifiedLine())
                file.writeText(ConversationTextWriter.render(export, listOf(header.title) + header.lines, footer))
                true
            }
        }
        if (!finished) {
            file.delete()
            return Result.Cancelled
        }
        return Result.Done(file, export, format.mimeType)
    }

    private fun pdfTexts(
        factory: ConversationExportFactory, export: ConversationExport, header: ExportHeader, now: Long,
    ) = PdfTexts(
        header = header,
        notCertified = factory.notCertifiedLine(),
        verification = factory.hashLine(export) + "  |  " + factory.exportedLine(now),
        page = { number, total -> context.getString(R.string.export_pdf_page, number, total) },
        from = { context.getString(R.string.export_pdf_from, it) },
        to = { context.getString(R.string.export_pdf_to, it) },
    )

    private fun newFile(title: String, format: ConversationFileFormat, now: Long): File {
        val dir = File(context.cacheDir, SHARED_DIR).apply { mkdirs() }
        ExportFileName.deleteOld(dir, now, KEEP_MS)
        return ExportFileName.unique(dir, title, format.extension, now)
    }

    companion object {
        /** The cache folder the FileProvider already shares (see provider_paths.xml). */
        const val SHARED_DIR = "shared_logs"

        /** Earlier export files are removed after this long, not at the next export (D15). */
        const val KEEP_MS = 24L * 60 * 60 * 1000
    }
}
