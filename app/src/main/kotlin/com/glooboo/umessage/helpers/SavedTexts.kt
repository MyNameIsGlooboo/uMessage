package com.glooboo.umessage.helpers

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.UUID

/** One saved text: a [title] to find it by and the [text] that goes into the message field. */
data class SavedText(val id: String, val title: String, val text: String)

/** What adding a saved text came to. */
enum class SavedTextAdd { Added, Empty, Duplicate, Full }

/**
 * The saved texts (N06, I10): an address, bank details, a usual answer. At most [MAX_COUNT], plain text in the
 * app's preferences on the phone (not encrypted), oldest first. Corrupt stored data reads as an empty list.
 */
class SavedTexts(private val prefs: ComposerPrefs) {

    fun all(): List<SavedText> = parse(prefs.savedTextsJson)

    /**
     * Texts offered as quick replies on a notification: short, and without a run of digits, so a card or account
     * number never shows up there.
     */
    fun quickReplies(): List<SavedText> = all()
        .filter { it.text.length < QUICK_REPLY_MAX_LENGTH && !hasLongNumber(it.text) }
        .take(QUICK_REPLY_MAX_COUNT)

    fun add(title: String, text: String): SavedTextAdd {
        val clean = clean(text)
        val current = all()
        return when {
            clean.isEmpty() -> SavedTextAdd.Empty
            current.any { it.text == clean } -> SavedTextAdd.Duplicate
            current.size >= MAX_COUNT -> SavedTextAdd.Full
            else -> {
                save(current + SavedText(UUID.randomUUID().toString(), titleFor(title, clean), clean))
                SavedTextAdd.Added
            }
        }
    }

    /** Changes the saved text [id]; false when it is gone or [text] is blank. */
    fun update(id: String, title: String, text: String): Boolean {
        val clean = clean(text)
        val current = all()
        if (clean.isEmpty() || current.none { it.id == id }) return false
        save(current.map { if (it.id == id) SavedText(id, titleFor(title, clean), clean) else it })
        return true
    }

    fun delete(id: String) = save(all().filterNot { it.id == id })

    private fun save(list: List<SavedText>) {
        prefs.savedTextsJson = toJson(list)
    }

    companion object {
        const val MAX_COUNT = 20
        const val MAX_TITLE_LENGTH = 40
        const val MAX_TEXT_LENGTH = 500
        const val QUICK_REPLY_MAX_LENGTH = 40
        const val QUICK_REPLY_MAX_COUNT = 5
        private val LONG_NUMBER = Regex("""\p{Nd}{6,}""")
        private val DIGIT_GROUPING = Regex("""[\s\-.]""")
        private const val QUICK_REPLY_MAX_DIGITS = 8

        /** True for a run of 6+ digits, or 8+ digits in total once spaces, dashes and dots are ignored. */
        internal fun hasLongNumber(text: String): Boolean =
            LONG_NUMBER.containsMatchIn(text) ||
                text.replace(DIGIT_GROUPING, "").count { Character.isDigit(it) } >= QUICK_REPLY_MAX_DIGITS
        private const val KEY_ID = "id"
        private const val KEY_TITLE = "title"
        private const val KEY_TEXT = "text"

        private fun clean(text: String) = text.trim().take(MAX_TEXT_LENGTH)

        /** The typed [title], or the start of the text's first line when no title was typed. */
        fun titleFor(title: String, text: String): String =
            title.trim().ifEmpty { text.lineSequence().first().trim() }.take(MAX_TITLE_LENGTH)

        fun parse(json: String): List<SavedText> = try {
            val array = JSONArray(json)
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val text = item.optString(KEY_TEXT)
                SavedText(item.optString(KEY_ID), item.optString(KEY_TITLE), text)
                    .takeIf { it.id.isNotEmpty() && text.isNotBlank() }
            }.take(MAX_COUNT)
        } catch (_: JSONException) {
            emptyList()
        }

        fun toJson(list: List<SavedText>): String = JSONArray().apply {
            list.forEach {
                put(JSONObject().put(KEY_ID, it.id).put(KEY_TITLE, it.title).put(KEY_TEXT, it.text))
            }
        }.toString()
    }
}
