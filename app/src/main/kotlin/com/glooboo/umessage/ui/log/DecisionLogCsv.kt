package com.glooboo.umessage.ui.log

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.glooboo.umessage.R
import com.glooboo.umessage.spam.SpamLogEntry
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * ID-39: the shown decisions as CSV. One line per decision (collapsed rows are expanded again), never message text.
 * Columns use the log's own tokens (BLOCK, UNKNOWN_SENDER, ...), so a shared file reads the same in every language.
 */
internal object DecisionLogCsv {
    private const val HEADER = "time,sender,sim,decision,reason,rule,source,fixed"
    private const val FILE_DIR = "shared_logs"
    private const val FILE_NAME = "uMessage-decisions.csv"

    private val timeFormat: DateTimeFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME

    /** [slotOf] gives the SIM slot to write (B4), or null to leave the column empty. */
    fun build(
        entries: List<SpamLogEntry>,
        slotOf: (SpamLogEntry) -> Int?,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String = buildString {
        append(HEADER).append("\r\n")
        entries.forEach { e ->
            val columns = listOf(
                Instant.ofEpochMilli(e.time).atZone(zone).format(timeFormat),
                e.sender,
                slotOf(e)?.toString().orEmpty(),
                e.decision,
                e.reason,
                e.ruleLabel.ifEmpty { e.ruleId?.let { "#$it" }.orEmpty() },
                e.source,
                e.fix,
            )
            append(columns.joinToString(",") { field(it) }).append("\r\n")
        }
    }

    /**
     * RFC 4180 quoting; a leading = + - @ gets a ' in front, so a spreadsheet never runs a sender name as a formula.
     */
    internal fun field(value: String): String {
        val safe = if (value.firstOrNull() in FORMULA_STARTS && !value.isPhoneNumberLike()) "'$value" else value
        val needsQuotes = safe.any { it == ',' || it == '"' || it == '\n' || it == '\r' } || safe != safe.trim()
        return if (needsQuotes) "\"" + safe.replace("\"", "\"\"") + "\"" else safe
    }

    private val FORMULA_STARTS = setOf('=', '+', '-', '@')

    /** "+14155550133" stays as-is: numbers are the most common sender and are not formulas. */
    private fun String.isPhoneNumberLike(): Boolean = drop(1).isNotEmpty() && drop(1).all { it.isDigit() || it == ' ' }

    /** Removes earlier exports (sender numbers) from the cache; called when the log screen opens. */
    fun deleteOldExports(context: Context) {
        runCatching { File(context.cacheDir, FILE_DIR).listFiles()?.forEach { it.delete() } }
    }

    /** Writes [csv] to the app cache and opens the share sheet for it. */
    fun share(context: Context, csv: String, count: Int) {
        val dir = File(context.cacheDir, FILE_DIR).apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "${System.currentTimeMillis()}-$FILE_NAME")
        file.writeText(csv)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/csv")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.declog_csv_subject))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val title = context.resources.getQuantityString(R.plurals.declog_share_count, count, count)
        context.startActivity(Intent.createChooser(send, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        com.glooboo.umessage.helpers.CacheCleanup.deleteAfterShare(file)
    }
}
