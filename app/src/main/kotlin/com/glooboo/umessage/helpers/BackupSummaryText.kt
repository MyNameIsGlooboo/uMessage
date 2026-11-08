package com.glooboo.umessage.helpers

import android.content.Context
import com.glooboo.umessage.R
import com.glooboo.umessage.models.ExportSummary
import com.glooboo.umessage.models.ImportSummary

/** Turns backup and restore results into the one message the user sees, in the dialog and in the notification. */
object BackupSummaryText {

    fun importTitle(context: Context, summary: ImportSummary): String = context.getString(
        when {
            summary.invalidFile || summary.wrongPassword -> R.string.backup_restore_failed
            summary.cancelled -> R.string.backup_restore_stopped
            else -> R.string.backup_restore_finished
        }
    )

    fun import(context: Context, summary: ImportSummary): String {
        if (summary.wrongPassword) return context.getString(R.string.export_password_wrong_restore)
        if (summary.invalidFile) return context.getString(R.string.backup_invalid_file)
        val lines = mutableListOf<String>()
        val written = summary.added + summary.repaired + summary.incomplete + summary.failed
        if (written == 0 && summary.duplicates > 0 && !summary.hasProblems) {
            lines += context.resources.getQuantityString(R.plurals.backup_nothing_new, summary.duplicates, summary.duplicates)
        } else if (summary.entriesRead == 0 && !summary.cancelled) {
            lines += context.getString(R.string.backup_no_entries)
        } else {
            fun line(count: Int, res: Int) {
                if (count > 0) lines += context.getString(res, count)
            }
            line(summary.added, R.string.backup_line_added)
            line(summary.duplicates, R.string.backup_line_duplicates)
            line(summary.repaired, R.string.backup_line_repaired)
            line(summary.incomplete, R.string.backup_line_incomplete)
            line(summary.failed, R.string.backup_line_failed)
            line(summary.damaged, R.string.backup_line_damaged)
            line(summary.unsupportedMms, R.string.backup_line_xml_mms)
            line(summary.skippedByChoice, R.string.backup_line_not_selected)
        }
        if (summary.reasons.isNotEmpty()) {
            lines += context.getString(R.string.backup_reasons)
            summary.reasons.forEach { (reason, count) -> lines += "• ${reasonText(context, reason)} ($count)" }
        }
        if (summary.stoppedEarly) lines += context.getString(R.string.backup_stopped_early, summary.entriesRead)
        if (summary.cancelled) lines += context.resources.getQuantityString(R.plurals.backup_cancelled_import, summary.entriesRead, summary.entriesRead)
        if (summary.failed > 0 || summary.incomplete > 0) lines += context.getString(R.string.backup_retry_hint)
        return lines.joinToString("\n")
    }

    fun exportTitle(context: Context, summary: ExportSummary, error: String?): String = context.getString(
        when {
            error != null -> R.string.backup_export_failed
            summary.cancelled -> R.string.backup_export_stopped
            else -> R.string.backup_export_finished
        }
    )

    fun export(context: Context, summary: ExportSummary, error: String?): String {
        if (error != null) return context.getString(R.string.backup_export_error, error)
        if (summary.cancelled) return context.getString(R.string.backup_export_cancelled)
        if (summary.total == 0 && !summary.hasProblems) return context.getString(R.string.backup_export_nothing)
        val lines = mutableListOf<String>()
        lines += context.getString(R.string.backup_export_counts, summary.sms, summary.mms)
        fun line(count: Int, res: Int) {
            if (count > 0) lines += context.getString(res, count)
        }
        line(summary.unreadableAttachments, R.string.backup_export_unreadable_attachments)
        line(summary.unreadableMessages, R.string.backup_export_unreadable_messages)
        line(summary.unreadableConversations, R.string.backup_export_unreadable_conversations)
        line(summary.hiddenLeftOut, R.string.backup_export_hidden_left_out)
        if (summary.hasProblems) lines += context.getString(R.string.backup_export_incomplete)
        return lines.joinToString("\n")
    }

    private fun reasonText(context: Context, reason: String): String = when (reason) {
        MessagesWriter.REASON_NO_ADDRESS -> context.getString(R.string.backup_reason_no_address)
        MessagesWriter.REASON_NO_THREAD -> context.getString(R.string.backup_reason_no_thread)
        MessagesWriter.REASON_REFUSED -> context.getString(R.string.backup_reason_refused)
        MessagesWriter.REASON_NOT_IN_BACKUP -> context.getString(R.string.backup_reason_not_in_backup)
        MessagesWriter.REASON_DAMAGED_ATTACHMENT -> context.getString(R.string.backup_reason_damaged_attachment)
        MessagesWriter.REASON_ATTACHMENT_WRITE -> context.getString(R.string.backup_reason_attachment_write)
        else -> reason
    }
}
