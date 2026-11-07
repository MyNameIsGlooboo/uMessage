package com.glooboo.umessage.ui.spam

import android.content.res.Resources
import androidx.annotation.StringRes
import com.glooboo.umessage.R

/**
 * Notes the app writes on rules by itself ("Blocked by you", "Reported as spam"). They are stored as a short code, not
 * as text, and turned into words only when shown, so they follow the app's language (bug 4). Rules saved before the
 * codes existed hold the English (or Persian) words; [codeOf] maps those to the same codes. A note the user typed is
 * shown as it is.
 */
object RuleNotes {
    private const val PREFIX = "@spamless:"

    const val BLOCKED_BY_YOU = "${PREFIX}blocked_by_you"
    const val ALLOWED_BY_YOU = "${PREFIX}allowed_by_you"
    const val BLOCKED_FROM_MESSAGE = "${PREFIX}blocked_from_message"
    const val BLOCKED_FROM_KEYWORD = "${PREFIX}blocked_from_keyword"
    const val ALLOWED_FROM_KEYWORD = "${PREFIX}allowed_from_keyword"
    const val REPORTED_SPAM = "${PREFIX}reported_spam"
    const val OLD_WORD_LIST = "${PREFIX}old_word_list"
    const val SYSTEM_LIST = "${PREFIX}system_list"

    /** Words older versions stored, in English and Persian, by the code they stand for. */
    private val legacy: Map<String, String> = mapOf(
        "Blocked by you" to BLOCKED_BY_YOU,
        "Allowed by you" to ALLOWED_BY_YOU,
        "Blocked from message" to BLOCKED_FROM_MESSAGE,
        "مسدودشده از روی پیام" to BLOCKED_FROM_MESSAGE,
        "Blocked from a keyword" to BLOCKED_FROM_KEYWORD,
        "مسدودشده از روی کلمهٔ کلیدی" to BLOCKED_FROM_KEYWORD,
        "Allowed from a keyword" to ALLOWED_FROM_KEYWORD,
        "مجاز شده از روی کلمهٔ کلیدی" to ALLOWED_FROM_KEYWORD,
        "Reported as spam" to REPORTED_SPAM,
        "گزارش‌شده به‌عنوان هرزنامه" to REPORTED_SPAM,
        "From the old blocked words list" to OLD_WORD_LIST,
        "از فهرست قدیمی کلمه‌های مسدود" to OLD_WORD_LIST,
        "From the system block list" to SYSTEM_LIST,
        "از فهرست مسدودی سیستم" to SYSTEM_LIST,
    )

    private val codes = setOf(
        BLOCKED_BY_YOU, ALLOWED_BY_YOU, BLOCKED_FROM_MESSAGE, BLOCKED_FROM_KEYWORD, ALLOWED_FROM_KEYWORD,
        REPORTED_SPAM, OLD_WORD_LIST, SYSTEM_LIST,
    )

    /** The code of an app-written note (stored as a code or as old words), or null for a note the user typed. */
    fun codeOf(note: String): String? {
        val trimmed = note.trim()
        return if (trimmed in codes) trimmed else legacy[trimmed]
    }

    /** Whether [note] was written by the app rather than typed by the user. */
    fun isAutomatic(note: String): Boolean = codeOf(note) != null

    @StringRes
    fun textRes(code: String): Int? = when (code) {
        BLOCKED_BY_YOU -> R.string.e_note_blocked_by_you
        ALLOWED_BY_YOU -> R.string.e_note_allowed_by_you
        BLOCKED_FROM_MESSAGE -> R.string.e_note_blocked_from_message
        BLOCKED_FROM_KEYWORD -> R.string.e_note_blocked_from_keyword
        ALLOWED_FROM_KEYWORD -> R.string.e_note_allowed_from_keyword
        REPORTED_SPAM -> R.string.e_note_reported_spam
        OLD_WORD_LIST -> R.string.e_note_old_word_list
        SYSTEM_LIST -> R.string.e_note_system_list
        else -> null
    }

    /** The note as the rule list shows it: an app note in the current language, a user note unchanged. */
    fun display(resources: Resources, note: String): String =
        codeOf(note)?.let(::textRes)?.let(resources::getString) ?: note
}
