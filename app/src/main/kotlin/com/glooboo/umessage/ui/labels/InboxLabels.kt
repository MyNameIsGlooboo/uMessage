package com.glooboo.umessage.ui.labels

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.glooboo.umessage.ui.conversations.ConversationRow
import com.glooboo.umessage.ui.main.InboxFilter
import com.glooboo.umessage.ui.main.MainSearchState
import com.glooboo.umessage.ui.main.ShortcutHits
import com.glooboo.umessage.ui.main.rememberShortcutHits

/**
 * I29: what the inbox needs of the labels. [shortcut] is the lookup of the filter that is on, with the conversations of
 * the label while a label is the filter; [chipName] names that label for the chip under the top bar (null when no
 * label is on); [openPicker] opens the label picker for the selected rows.
 */
@Stable
internal class InboxLabels(val shortcut: ShortcutHits, val chipName: String?, val openPicker: () -> Unit)

/**
 * Wraps the shortcut lookup of the inbox ([rememberShortcutHits]) with the labels, shows the picker for the rows in
 * [selected] when it is open, and turns the label filter off when its label is gone.
 */
@Composable
internal fun rememberInboxLabels(
    search: MainSearchState,
    rows: List<ConversationRow>,
    selected: Set<Long>,
    viewModel: LabelsViewModel = viewModel(),
): InboxLabels {
    val labels by viewModel.state.collectAsStateWithLifecycle()
    val base = rememberShortcutHits(search.filter, rows)
    val active = labels.label(search.labelId).takeIf { search.filter == InboxFilter.Label }
    LaunchedEffect(labels.loaded, active, search.filter) {
        if (labels.loaded && search.filter == InboxFilter.Label && active == null) search.filter = InboxFilter.All
    }
    var pickerFor by remember { mutableStateOf<List<Long>?>(null) }
    pickerFor?.let { LabelPickerDialog(it, onDismiss = { pickerFor = null }) }
    val shortcut = active?.let { ShortcutHits(InboxFilter.Label, threads = labels.threadsOf(it.id), loaded = true) }
    return InboxLabels(shortcut ?: base, active?.name) { pickerFor = selected.toList() }
}

/** The name of the label that is the filter, for the chip above typed search results; null when none is on. */
@Composable
internal fun activeLabelName(search: MainSearchState, viewModel: LabelsViewModel = viewModel()): String? {
    val labels by viewModel.state.collectAsStateWithLifecycle()
    return labels.label(search.labelId)?.name?.takeIf { search.filter == InboxFilter.Label }
}
