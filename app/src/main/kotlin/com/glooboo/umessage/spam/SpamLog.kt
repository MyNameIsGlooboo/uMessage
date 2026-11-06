package com.glooboo.umessage.spam

import java.io.File

/** One filter decision. Deliberately has no message body. */
data class SpamLogEntry(
    val time: Long,
    val sender: String,
    val subscriptionId: Int,
    /** "ALLOW", "BLOCK" or "ERROR". */
    val decision: String,
    val reason: String,
    /** Where the decision was taken, e.g. "SMS", "MMS_PUSH", "MMS_CONTENT". */
    val source: String,
    /** The rule that decided a BLOCK or an ALLOW (SE-39); null when none did (unknown sender, old Fossify list). */
    val ruleId: Long? = null,
    /** A short label of that rule (its value, never message text), so the entry still reads once the rule is gone. */
    val ruleLabel: String = "",
    /** What the user did about this decision from the log (B5): a [SpamLog] FIX_ value, empty when nothing. */
    val fix: String = "",
)

/**
 * Ring buffer of the last [capacity] decisions, kept in memory and mirrored to a small local file.
 * Entries older than [retentionMs] are dropped: they are never returned, and [purgeExpired] (run with the
 * blocked-message purge) removes them from the file. Never contains message bodies. All methods are thread safe
 * and never throw.
 */
class SpamLog(
    private val file: File?,
    val capacity: Int = 200,
    val retentionMs: Long = RETENTION_MS,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()
    private val buffer = ArrayDeque<SpamLogEntry>()
    private var loaded = false
    private var linesOnDisk = 0

    fun record(entry: SpamLogEntry) {
        synchronized(lock) {
            ensureLoaded()
            buffer.addLast(entry)
            while (buffer.size > capacity) buffer.removeFirst()
            // When old entries just expired, the file is rewritten (with the new one) so they leave the disk too.
            if (dropExpired()) rewrite() else persist(entry)
        }
    }

    /** Newest first, without expired entries. */
    fun entries(): List<SpamLogEntry> = synchronized(lock) {
        ensureLoaded()
        dropExpired()
        buffer.toList().asReversed()
    }

    /** Drops expired entries and rewrites the file without them. */
    fun purgeExpired() {
        synchronized(lock) {
            ensureLoaded()
            if (dropExpired()) rewrite()
        }
    }

    /** True when something was dropped. Entries dated in the future (clock moved back) are kept. */
    private fun dropExpired(): Boolean {
        val cutoff = now() - retentionMs
        var dropped = false
        while (buffer.isNotEmpty() && buffer.first().time < cutoff) {
            buffer.removeFirst()
            dropped = true
        }
        return dropped
    }

    private fun rewrite() {
        val f = file ?: return
        try {
            if (buffer.isEmpty()) {
                f.delete()
            } else {
                f.parentFile?.mkdirs()
                f.writeText(buffer.joinToString(separator = "") { encode(it) + "\n" })
            }
            linesOnDisk = buffer.size
        } catch (_: Exception) {
        }
    }

    /**
     * Stores (or with an empty [fix], removes) the user's fix of the decision made at [time] for [sender], so the
     * "Fixed" mark survives leaving the screen. Returns false when no such entry is kept any more.
     */
    fun markFixed(time: Long, sender: String, fix: String): Boolean = synchronized(lock) {
        ensureLoaded()
        val index = buffer.indexOfLast { it.time == time && it.sender == sender }
        if (index < 0) return@synchronized false
        buffer[index] = buffer[index].copy(fix = fix)
        rewrite()
        true
    }

    fun clear() {
        synchronized(lock) {
            ensureLoaded()
            buffer.clear()
            linesOnDisk = 0
            try {
                file?.delete()
            } catch (_: Exception) {
            }
        }
    }

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        try {
            val f = file ?: return
            if (!f.exists()) return
            f.readLines().forEach { line -> decode(line)?.let { buffer.addLast(it) } }
            linesOnDisk = buffer.size
            while (buffer.size > capacity) buffer.removeFirst()
            if (dropExpired()) rewrite()
        } catch (_: Exception) {
        }
    }

    private fun persist(entry: SpamLogEntry) {
        val f = file ?: return
        try {
            f.parentFile?.mkdirs()
            if (linesOnDisk >= capacity * 2) {
                rewrite()
            } else {
                f.appendText(encode(entry) + "\n")
                linesOnDisk++
            }
        } catch (_: Exception) {
        }
    }

    companion object {
        /** Decisions are kept for a week: enough to check why a message was blocked, without a long contact trail. */
        const val RETENTION_DAYS = 7
        const val RETENTION_MS = RETENTION_DAYS * 24L * 60 * 60 * 1000

        private fun clean(s: String) = s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')

        /** Fix marks; anything else read from the file is kept as-is and shown as fixed. */
        const val FIX_ALLOWED = "ALLOWED"
        const val FIX_BLOCKED = "BLOCKED"
        const val FIX_RESTORED = "RESTORED"

        private const val BASE_COLUMNS = 6
        private const val RULE_COLUMNS = 8
        private const val FIX_COLUMNS = 9
        private const val RULE_ID_COLUMN = 6
        private const val RULE_LABEL_COLUMN = 7
        private const val FIX_COLUMN = 8

        /**
         * Six columns; a decision made by a stored rule adds the rule id and its short label (SE-39); a decision the
         * user fixed has a ninth column with the fix (B5), the rule id then being empty when no rule decided.
         */
        fun encode(e: SpamLogEntry): String {
            val columns = mutableListOf(
                e.time.toString(), clean(e.sender), e.subscriptionId.toString(), clean(e.decision), clean(e.reason),
                clean(e.source),
            )
            if (e.ruleId != null || e.fix.isNotEmpty()) {
                columns += e.ruleId?.toString().orEmpty()
                columns += clean(e.ruleLabel)
            }
            if (e.fix.isNotEmpty()) columns += clean(e.fix)
            return columns.joinToString("\t")
        }

        /** Reads all three shapes, so lines written by older versions stay readable. Null for a damaged line. */
        fun decode(line: String): SpamLogEntry? {
            val p = line.split('\t')
            if (p.size != BASE_COLUMNS && p.size != RULE_COLUMNS && p.size != FIX_COLUMNS) return null
            val time = p[0].toLongOrNull()
            val subscriptionId = p[2].toIntOrNull()
            val ruleText = p.getOrNull(RULE_ID_COLUMN).orEmpty()
            val ruleId = ruleText.toLongOrNull()
            // An 8-column line always names a rule; only the 9-column shape may leave it empty.
            val ruleReadable = ruleId != null || (ruleText.isEmpty() && p.size != RULE_COLUMNS)
            if (time == null || subscriptionId == null || !ruleReadable) return null
            return SpamLogEntry(
                time = time,
                sender = p[1],
                subscriptionId = subscriptionId,
                decision = p[3],
                reason = p[4],
                source = p[5],
                ruleId = ruleId,
                ruleLabel = if (p.size >= RULE_COLUMNS) p[RULE_LABEL_COLUMN] else "",
                fix = if (p.size == FIX_COLUMNS) p[FIX_COLUMN] else "",
            )
        }
    }
}
