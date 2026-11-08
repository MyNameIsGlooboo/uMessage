package com.glooboo.umessage.ui.settings.search

import android.content.res.Resources
import com.glooboo.umessage.ui.settings.search.SettingsIndexEntry.Needs

/**
 * The settings search for one language: reads the texts of [SettingsIndex] from [resources] and finds entries for a
 * typed query. Entries whose row is not on this phone (see [Needs]) are left out unless listed in [available]. With
 * [forwarding] off the forwarding entries are left out too.
 */
class SettingsSearch(resources: Resources, available: Set<Needs> = emptySet(), forwarding: Boolean = true) {
    /** One result: where it goes and the texts to show. */
    class Hit(val entry: SettingsIndexEntry, val title: String, val summary: String, val page: String)

    private val entries = SettingsIndex.entries
        .filter { it.needs == Needs.NOTHING || it.needs in available }
        .filter { forwarding || it.target !in SettingsTarget.FORWARDING_TARGETS }
    private val hits = entries.map { entry ->
        Hit(
            entry = entry,
            title = resources.getString(entry.titleRes),
            summary = entry.summaryRes?.let { resources.getString(it) }.orEmpty(),
            page = resources.getString(entry.pageRes),
        )
    }
    private val matcher = SettingsSearchMatcher(
        hits.mapIndexed { index, hit ->
            val keywords = entries[index].keywordsRes?.let { resources.getString(it) }.orEmpty()
            SettingsSearchDoc(index, hit.title, hit.summary, keywords, hit.page)
        },
    )

    fun find(query: String): List<Hit> = matcher.search(query).map { hits[it.id] }

    companion object {
        /** The [Needs] this phone meets, from what the Settings screen knows. */
        fun available(dualSim: Boolean, decisionLog: Boolean, languageChoice: Boolean): Set<Needs> = buildSet {
            if (dualSim) add(Needs.DUAL_SIM)
            if (decisionLog) add(Needs.DECISION_LOG)
            if (languageChoice) add(Needs.LANGUAGE_CHOICE)
        }
    }
}
