package com.glooboo.umessage.ui.starred

import com.glooboo.umessage.helpers.StarredItem
import com.glooboo.umessage.models.MessageKey

/** What the Starred screen's taps do. */
class StarredActions(
    val onBack: () -> Unit,
    val onOpen: (StarredItem) -> Unit,
    val onToggle: (StarredItem) -> Unit,
    val onSelectAll: () -> Unit,
    val onClearSelection: () -> Unit,
    val onUnstar: (Set<MessageKey>) -> Unit,
    val onShowMore: () -> Unit,
)
