package com.glooboo.umessage.models

/** What happened to one message the restore tried to write. */
enum class WriteOutcome {
    /** A new message is now on the phone. */
    ADDED,

    /** The phone already had it, with everything the backup has. Nothing was written. */
    DUPLICATE,

    /** The phone had the message but was missing attachments or recipients from an earlier restore; they were added. */
    REPAIRED,

    /** The message is on the phone, but some of its attachments could not be written or were not in the backup. */
    INCOMPLETE,

    /** Nothing could be written. */
    FAILED,
}

data class WriteResult(val outcome: WriteOutcome, val reason: String? = null)

/**
 * The honest result of a restore: every entry of the file ends up in exactly one bucket. [reasons] keeps a count per
 * distinct reason (the first [MAX_REASONS] of them), so the summary can say why without flooding the screen.
 */
class ImportSummary {
    var added = 0
    var duplicates = 0
    var repaired = 0
    var incomplete = 0
    var failed = 0

    /** Entries the file holds but that could not be read (damaged JSON, missing fields). */
    var damaged = 0

    /** Entries of a type the user chose not to import. */
    var skippedByChoice = 0

    /** MMS entries in an XML backup; that format's picture messages are not supported. */
    var unsupportedMms = 0

    /** The file stopped making sense part way, so the entries after that point were not read. */
    var stoppedEarly = false

    /** The file is not a backup at all. */
    var invalidFile = false

    /** The file is protected with a password and none was given, or it was wrong; nothing was restored. */
    var wrongPassword = false

    /** The user cancelled, or the system stopped the restore; [entriesRead] says how far it got. */
    var cancelled = false

    var entriesRead = 0

    /** Private notes of the file that were taken (the entry for notes is not counted in [entriesRead]). */
    var notesRestored = 0

    /** Stars, labels and archived chats of the file that were taken (see [AppDataBackup]). */
    var appDataRestored = 0

    val reasons = linkedMapOf<String, Int>()

    fun record(result: WriteResult) {
        when (result.outcome) {
            WriteOutcome.ADDED -> added++
            WriteOutcome.DUPLICATE -> duplicates++
            WriteOutcome.REPAIRED -> repaired++
            WriteOutcome.INCOMPLETE -> incomplete++
            WriteOutcome.FAILED -> failed++
        }
        result.reason?.let(::addReason)
    }

    fun addReason(reason: String) {
        if (reason in reasons || reasons.size < MAX_REASONS) reasons[reason] = (reasons[reason] ?: 0) + 1
    }

    /** True when the phone gained something: a new message or missing pieces of one. */
    val changedAnything get() = added > 0 || repaired > 0 || incomplete > 0

    val hasProblems get() =
        failed > 0 || incomplete > 0 || damaged > 0 || stoppedEarly || invalidFile || cancelled || wrongPassword

    override fun toString() =
        "ImportSummary(added=$added, duplicates=$duplicates, repaired=$repaired, incomplete=$incomplete, " +
            "failed=$failed, damaged=$damaged, skippedByChoice=$skippedByChoice, unsupportedMms=$unsupportedMms, " +
            "stoppedEarly=$stoppedEarly, invalidFile=$invalidFile, wrongPassword=$wrongPassword, " +
            "cancelled=$cancelled, notesRestored=$notesRestored, reasons=$reasons)"

    companion object {
        const val MAX_REASONS = 5
    }
}

/** The result of a backup. Anything that could not be read is counted, so the user knows the file is not complete. */
class ExportSummary {
    var sms = 0
    var mms = 0

    /** Attachments whose bytes could not be read; the message is in the file without them. */
    var unreadableAttachments = 0

    /** Conversations that could not be read at all. */
    var unreadableConversations = 0

    /** Single messages that could not be read. */
    var unreadableMessages = 0

    var cancelled = false

    /** Private notes written at the end of a JSON backup. */
    var notes = 0

    /** Stars, labels and archived chats written at the end of a JSON backup. */
    var appData = 0

    val total get() = sms + mms
    val hasProblems get() = unreadableAttachments > 0 || unreadableConversations > 0 || unreadableMessages > 0

    override fun toString() =
        "ExportSummary(sms=$sms, mms=$mms, unreadableAttachments=$unreadableAttachments, " +
            "unreadableConversations=$unreadableConversations, unreadableMessages=$unreadableMessages, " +
            "cancelled=$cancelled, notes=$notes)"
}
