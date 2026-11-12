package com.glooboo.umessage.spam.mms

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

    /** Whether the size announced by the notification allows a download. An unknown size (0 or less) does. */
    fun sizeAllowed(announcedBytes: Long): Boolean = announcedBytes <= MAX_MESSAGE_BYTES

    /** Whether a download may start: never while roaming, and not once [keptFiles] reached [MAX_KEPT_FILES]. */
    fun mayDownload(roaming: Boolean, keptFiles: Int): Boolean = !roaming && keptFiles < MAX_KEPT_FILES
}
