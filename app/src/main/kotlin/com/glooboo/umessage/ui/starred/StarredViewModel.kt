package com.glooboo.umessage.ui.starred

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.glooboo.umessage.helpers.StarredList
import com.glooboo.umessage.helpers.StarredMessages
import com.glooboo.umessage.helpers.StarredPage
import com.glooboo.umessage.helpers.starredDB
import com.glooboo.umessage.models.MessageKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The Starred screen: one row per starred message, newest star first, up to [StarredList.PAGE] at a time ("Show more"
 * adds the next page). The list reads again whenever a star is added or removed, and when the screen is shown again
 * (a note may have changed).
 */
class StarredViewModel(application: Application) : AndroidViewModel(application) {
    private val app: Application get() = getApplication()
    private val limit = MutableStateFlow(StarredList.PAGE)
    private val refreshTick = MutableStateFlow(0)
    private val _page = MutableStateFlow<StarredPage?>(null)
    private val _selected = MutableStateFlow<Set<MessageKey>>(emptySet())

    /** Null until the first read finishes. */
    val page: StateFlow<StarredPage?> = _page.asStateFlow()

    /** The rows picked in selection mode. */
    val selected: StateFlow<Set<MessageKey>> = _selected.asStateFlow()

    private val reads = combine(app.starredDB.observeAll(), limit, refreshTick) { _, size, _ -> size }

    init {
        viewModelScope.launch {
            StarredMessages.relink(app)
            reads.collect { size -> _page.value = StarredList.load(app, size) }
        }
    }

    fun refresh() = refreshTick.update { it + 1 }

    fun showMore() = limit.update { it + StarredList.PAGE }

    fun toggle(key: MessageKey) = _selected.update { if (key in it) it - key else it + key }

    fun selectAll() {
        _selected.value = _page.value?.items?.mapTo(HashSet()) { it.key }.orEmpty()
    }

    fun clearSelection() {
        _selected.value = emptySet()
    }

    /** Removes the stars of [keys]; the rows leave the list when the read that follows comes back. */
    fun unstar(keys: Set<MessageKey>) {
        viewModelScope.launch {
            StarredMessages.unstar(app, keys)
            _selected.update { it - keys }
        }
    }
}
