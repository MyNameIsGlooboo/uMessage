package com.glooboo.umessage.helpers

/**
 * Pure rules of the blocked-keywords list: clean-up, case-insensitive duplicates and the import/export file format.
 * Keywords match inside words without regard to case, so "Free" and "free" are the same keyword, and a blank or
 * one-letter keyword would block nearly every message.
 */
object BlockedKeywordsFormat {
    const val MIN_LENGTH = 2

    /** The keyword to store, or null when it is too short or spans lines. */
    fun clean(raw: String): String? = raw.trim().takeIf { it.length >= MIN_LENGTH && it.none { c -> c == '\n' || c == '\r' } }

    fun contains(existing: Collection<String>, keyword: String): Boolean = existing.any { it.equals(keyword.trim(), ignoreCase = true) }

    /** [existing] plus [keyword], unless it is not valid or already there in any case. */
    fun add(existing: Set<String>, keyword: String): Set<String> {
        val cleaned = clean(keyword) ?: return existing
        return if (contains(existing, cleaned)) existing else existing + cleaned
    }

    fun sorted(keywords: Collection<String>): List<String> = keywords.sortedWith(String.CASE_INSENSITIVE_ORDER)

    /** One keyword per line, so keywords that contain a comma stay whole. */
    fun export(keywords: Collection<String>): String = sorted(keywords).joinToString("\n", postfix = "\n")

    /**
     * Keywords in a file. Files written by [export] have one keyword per line and end with a line break; older
     * Fossify files are a single comma-separated line. Blanks, too-short entries and duplicates are dropped.
     */
    fun parse(text: String): List<String> {
        val lines = text.lines().filter { it.isNotBlank() }
        val entries = when {
            lines.size >= 2 -> lines
            lines.size == 1 && text.trimEnd(' ', '\t').endsWith('\n') -> lines
            lines.size == 1 -> lines.single().split(BLOCKED_KEYWORDS_EXPORT_DELIMITER)
            else -> emptyList()
        }
        return entries.mapNotNull(::clean).distinctBy { it.lowercase() }
    }
}
