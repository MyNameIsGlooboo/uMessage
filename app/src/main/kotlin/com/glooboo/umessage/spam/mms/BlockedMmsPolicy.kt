package com.glooboo.umessage.spam.mms

import com.glooboo.umessage.spam.DecisionReason

/**
 * spam-10: when a blocked MMS is worth downloading into uMessage's private storage. A blocked MMS that is not
 * downloaded stays in Blocked as a placeholder (its row and sender remain; only its pictures are missing), so the
 * user can still open it. The limits are deliberately conservative: spam MMS is small and rare, and an unlimited
 * download of what a stranger sends is a way to fill the phone or the data plan. Pure JVM.
 */
object BlockedMmsPolicy {
    /** Largest announced message size that is downloaded: 1 MiB, above the usual 300 KB to 1 MB carrier limits. */
    const val MAX_MESSAGE_BYTES = 1_048_576L

    /** Most kept downloads at one time; more would fill storage until the purge. */
    const val MAX_KEPT_FILES = 100

    /** Whether the size announced by the notification allows a download. An unknown size (0 or less) does not. */
    fun sizeAllowed(announcedBytes: Long): Boolean = announcedBytes in 1..MAX_MESSAGE_BYTES

    /**
     * Whether the reason a sender was blocked allows a download. A sender the user blocked by a rule (BLOCK_RULE: the
     * push carries only the sender, so these are number and sender-id rules, including the old block list) is never
     * downloaded from; only a mode or heuristic block (unknown sender, foreign number) may be.
     */
    fun reasonAllowed(reason: DecisionReason): Boolean = reason != DecisionReason.BLOCK_RULE

    /** Whether a download may start: never while roaming, and not once [keptFiles] reached [MAX_KEPT_FILES]. */
    fun mayDownload(roaming: Boolean, keptFiles: Int): Boolean = !roaming && keptFiles < MAX_KEPT_FILES
}
