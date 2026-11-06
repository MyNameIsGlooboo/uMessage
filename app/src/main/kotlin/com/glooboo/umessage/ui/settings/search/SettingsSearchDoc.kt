package com.glooboo.umessage.ui.settings.search

/** One setting as the matcher sees it: its texts in the app's current language. [id] is the index of its entry. */
class SettingsSearchDoc(
    val id: Int,
    val title: String,
    val summary: String = "",
    val keywords: String = "",
    val page: String = "",
)
