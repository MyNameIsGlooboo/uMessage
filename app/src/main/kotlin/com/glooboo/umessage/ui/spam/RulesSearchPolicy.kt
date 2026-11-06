package com.glooboo.umessage.ui.spam

import com.glooboo.umessage.spam.RuleType
import com.glooboo.umessage.spam.SpamRule

/** When the Rules list is long enough to need a search box and type chips; a short list should look short. */
object RulesSearchPolicy {
    const val MIN_ROWS_FOR_SEARCH = 5

    /** Search stays while something is typed or picked, so it can always be cleared. */
    fun showSearch(rowCount: Int, filtering: Boolean): Boolean = filtering || rowCount > MIN_ROWS_FOR_SEARCH

    /** The type chips: only the types the list holds, and none when there is nothing to tell apart. */
    fun chipTypes(rules: List<SpamRule>, active: RuleType?): List<RuleType> {
        val present = rules.mapTo(mutableSetOf()) { it.type }
        if (active != null) present += active
        return if (present.size < 2 && active == null) emptyList() else RuleType.entries.filter { it in present }
    }
}
