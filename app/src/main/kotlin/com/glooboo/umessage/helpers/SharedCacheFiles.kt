package com.glooboo.umessage.helpers

import java.io.File

/**
 * B2: more than one feature writes to the share cache folder (a shared conversation and the decision log). Each feature
 * owns a name pattern, so its cleanup never removes a file another feature is still sharing.
 */
object SharedCacheFiles {
    /** The cache folder the FileProvider already shares (see provider_paths.xml). */
    const val DIR = "shared_logs"

    /** Shared files are removed after this long, on top of each feature's own cleanup. */
    const val KEEP_MS = 24L * 60 * 60 * 1000

    /** A shared conversation starts with this, then the time it was written. */
    const val CONVERSATION_PREFIX = "conversation-"

    /** A decision log export ends with this; its name starts with the time it was written. */
    const val DECISION_LOG_SUFFIX = "-uMessage-decisions.csv"

    /** The name of a shared conversation file: a new [now] never reuses an earlier file. */
    fun conversationName(now: Long, extension: String): String = "$CONVERSATION_PREFIX$now.$extension"

    /** The name of a decision log export; the timestamp prefix keeps the log's exports in order. */
    fun decisionLogName(now: Long): String = "$now$DECISION_LOG_SUFFIX"

    fun isConversation(name: String): Boolean = name.startsWith(CONVERSATION_PREFIX)

    fun isDecisionLog(name: String): Boolean = name.endsWith(DECISION_LOG_SUFFIX)

    /**
     * Deletes the files in [dir] that [owned] claims and that are at least [maxAgeMs] old (0 removes every earlier
     * one). Files of another feature are never touched. Returns how many were deleted.
     */
    fun deleteOwn(dir: File, owned: (String) -> Boolean, now: Long, maxAgeMs: Long): Int {
        var deleted = 0
        dir.listFiles { file -> file.isFile && owned(file.name) && now - file.lastModified() >= maxAgeMs }
            ?.forEach { if (it.delete()) deleted++ }
        return deleted
    }
}
