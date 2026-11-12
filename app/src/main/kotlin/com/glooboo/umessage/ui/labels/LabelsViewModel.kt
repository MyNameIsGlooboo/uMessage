package com.glooboo.umessage.ui.labels

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.glooboo.umessage.extensions.labelsDB
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * I29: the user's labels for the screens that show or change them (search tiles, label picker, manager). One instance
 * per activity; the lists follow the database, so every screen sees a change at once.
 */
class LabelsViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application
    private val _state = MutableStateFlow(LabelsUiState())
    val state: StateFlow<LabelsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val dao = app.labelsDB
                combine(dao.observeLabels(), dao.observeLinks()) { labels, links -> LabelsUiState.of(labels, links) }
                    .collect { _state.value = it }
            }.onFailure {
                // A database error must not leave the screens waiting for labels forever: show none.
                if (it is CancellationException) throw it
                _state.value = LabelsUiState(loaded = true)
            }
        }
    }

    /** Adds a label (and files [threadIds] under it); [onDone] gets the verdict on the main thread. */
    fun add(name: String, color: Int, threadIds: Collection<Long> = emptyList(), onDone: (LabelResult) -> Unit = {}) =
        change(onDone) { it.add(name, color, threadIds) }

    fun update(labelId: Long, name: String, color: Int, onDone: (LabelResult) -> Unit = {}) =
        change(onDone) { it.update(labelId, name, color) }

    fun delete(labelId: Long) = change {
        it.delete(labelId)
        LabelResult.Ok
    }

    fun setAssigned(threadIds: Collection<Long>, labelId: Long, assigned: Boolean) = change {
        it.setAssigned(threadIds, labelId, assigned)
        LabelResult.Ok
    }

    private fun change(onDone: (LabelResult) -> Unit = {}, block: suspend (LabelsRepository) -> LabelResult) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { block(LabelsRepository(app.labelsDB)) }.getOrDefault(LabelResult.Error)
            }
            onDone(result)
        }
    }
}
