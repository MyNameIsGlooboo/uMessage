package com.glooboo.umessage.ui.main

import com.glooboo.umessage.models.SearchResult

/** What the search page's taps do. */
internal class SearchPanelActions(
    val onPickRecent: (String) -> Unit,
    val onClearRecent: () -> Unit,
    val onFilter: (InboxFilter) -> Unit,
    val onSim: (Int) -> Unit,
    val onOpen: (SearchResult) -> Unit,
    /** Opens the blocked thread of a sender found in the spam section (main-22). */
    val onOpenBlocked: (String) -> Unit = {},
)
