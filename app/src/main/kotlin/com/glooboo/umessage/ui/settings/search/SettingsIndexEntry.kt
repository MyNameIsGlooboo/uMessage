package com.glooboo.umessage.ui.settings.search

import androidx.annotation.StringRes

/**
 * One searchable setting. [titleRes] and [summaryRes] are the texts of its row, [pageRes] names the page it lives on
 * (the breadcrumb), [keywordsRes] holds words people may type for it that its row does not say (synonyms, in each
 * language), [target] is where a tap on the result goes, and [needs] is what the phone must have for the row to exist.
 */
class SettingsIndexEntry(
    @StringRes val titleRes: Int,
    @StringRes val pageRes: Int,
    val target: SettingsTarget,
    @StringRes val summaryRes: Int? = null,
    @StringRes val keywordsRes: Int? = null,
    val needs: Needs = Needs.NOTHING,
) {
    /** Rows that only show on some phones; the search hides their results elsewhere. */
    enum class Needs { NOTHING, DUAL_SIM, DECISION_LOG, LANGUAGE_CHOICE }
}
