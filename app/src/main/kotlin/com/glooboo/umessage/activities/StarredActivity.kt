package com.glooboo.umessage.activities

import android.os.Bundle
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.glooboo.umessage.ui.starred.StarredActions
import com.glooboo.umessage.ui.starred.StarredScreen
import com.glooboo.umessage.ui.starred.StarredViewModel
import com.glooboo.umessage.ui.starred.starredOpenIntent

/** The starred messages, one row per message; a tap opens the chat at that message. */
class StarredActivity : ComposeSimpleActivity() {
    private val viewModel: StarredViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val actions = StarredActions(
            onBack = ::finish,
            onOpen = { item -> startActivity(starredOpenIntent(this, item)) },
            onToggle = { viewModel.toggle(it.key) },
            onSelectAll = viewModel::selectAll,
            onClearSelection = viewModel::clearSelection,
            onUnstar = viewModel::unstar,
            onShowMore = viewModel::showMore,
        )
        setUMessageContent {
            val page by viewModel.page.collectAsStateWithLifecycle()
            val selected by viewModel.selected.collectAsStateWithLifecycle()
            StarredScreen(page = page, selected = selected, actions = actions)
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refresh()
    }
}
