package com.glooboo.umessage.helpers

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/** Most recent searches kept for the chips on the empty search page (CL-14). */
const val MAX_RECENT_SEARCHES = 5

/**
 * [recent] with [query] moved to the front: trimmed, no duplicate (ignoring case), at most [max] entries. A blank
 * query leaves the list as it was.
 */
fun pushRecentSearch(recent: List<String>, query: String, max: Int = MAX_RECENT_SEARCHES): List<String> {
    val clean = query.replace('\n', ' ').replace('\r', ' ').trim()
    if (clean.isEmpty()) return recent
    return (listOf(clean) + recent.filterNot { it.equals(clean, ignoreCase = true) }).take(max)
}

/** Recent searches of the main screen, in their own preferences file (`Config` keeps its preferences private). */
class SearchHistoryPrefs private constructor(private val prefs: SharedPreferences) {

    var recentSearches: List<String>
        get() = prefs.getString(KEY_RECENT, null)?.split(SEPARATOR)?.filter { it.isNotBlank() }.orEmpty()
        set(value) = prefs.edit {
            if (value.isEmpty()) remove(KEY_RECENT) else putString(KEY_RECENT, value.joinToString(SEPARATOR.toString()))
        }

    /** Remembers [query] as the latest search and returns the new list. */
    fun remember(query: String): List<String> {
        val updated = pushRecentSearch(recentSearches, query)
        recentSearches = updated
        return updated
    }

    companion object {
        private const val PREFS_NAME = "spamless_search_history"
        private const val KEY_RECENT = "recent"
        private const val SEPARATOR = '\n'

        fun get(context: Context): SearchHistoryPrefs =
            SearchHistoryPrefs(context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))
    }
}
