package com.glooboo.umessage.ui.spam

import org.fossify.commons.extensions.appLockManager
import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.glooboo.umessage.R
import com.glooboo.umessage.spam.UnknownSenderMode
import com.glooboo.umessage.ui.components.EmptyState
import com.glooboo.umessage.ui.components.FabListBottomPadding
import com.glooboo.umessage.ui.components.UMessageFab
import com.glooboo.umessage.ui.components.SectionHeader
import com.glooboo.umessage.ui.components.SelectionTopBar
import com.glooboo.umessage.ui.components.SenderAvatar
import com.glooboo.umessage.ui.components.UMessageScaffold
import com.glooboo.umessage.ui.components.UMessageTopBar
import com.glooboo.umessage.ui.components.SwitchRow
import com.glooboo.umessage.ui.components.rememberReduceMotion
import com.glooboo.umessage.ui.components.displayNumber
import com.glooboo.umessage.ui.components.isolateNumbers
import com.glooboo.umessage.ui.icons.Symbols
import com.glooboo.umessage.ui.theme.UMessageTheme
import kotlinx.coroutines.launch

@Immutable
class BlockedNumbersActions(
    val onBack: () -> Unit = {},
    val onAdd: () -> Unit = {},
    val onEdit: (BlockedNumberItem) -> Unit = {},
    val onToggleSelected: (Long) -> Unit = {},
    val onClearSelection: () -> Unit = {},
    val onSelectAll: () -> Unit = {},
    val onUnblock: (Set<Long>) -> Unit = {},
    val onCopy: (BlockedNumberItem) -> Unit = {},
    val onBlockUnknown: (Boolean) -> Unit = {},
    val onImport: () -> Unit = {},
    val onExport: () -> Unit = {},
    val onMoveToRules: () -> Unit = {},
    val onSubmit: (String) -> Unit = {},
    val onDismissDialog: () -> Unit = {},
    /** One number into uMessage rules (SE-31). */
    val onMoveOne: (BlockedNumberItem) -> Unit = {},
    /** The add dialog's short-code way out: a uMessage rule instead of an Android list entry (BR-28). */
    val onAddAsRule: (String) -> Unit = {},
)

/** Stateful entry point: ViewModel, file pickers, contacts permission, clipboard and snackbars. */
@Composable
fun BlockedNumbersRoute(
    onBack: () -> Unit,
    // Bug 9: "Move N numbers to rules" asks here, over this list, instead of opening the Rules screen first.
    rulesViewModel: RulesViewModel = viewModel(factory = RulesViewModel.Factory),
) {
    val viewModel: BlockedNumbersViewModel = viewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val rulesState by rulesViewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val undoLabel = stringResource(R.string.spam_undo)
    val copiedText = stringResource(R.string.blocknum_copied)
    val contactsDenied = stringResource(R.string.blocknum_contacts_denied)

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::importFrom)
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        uri?.let(viewModel::exportTo)
    }
    // "Block unknown numbers" needs contacts to tell known from unknown; the setting stays on either way.
    val contactsPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) scope.launch { snackbar.showSnackbar(contactsDenied) }
    }

    LaunchedEffect(rulesViewModel) {
        rulesViewModel.eventFlow.collect { event ->
            if (event is RulesEvent.Migrated) {
                viewModel.refresh()
                snackbar.currentSnackbarData?.dismiss()
                launch { snackbar.showSnackbar(migratedText(resources, event), duration = SnackbarDuration.Long) }
            }
        }
    }
    LaunchedEffect(viewModel) {
        viewModel.eventFlow.collect { event ->
            snackbar.currentSnackbarData?.dismiss()
            launch {
                showBlockedNumbersEvent(
                    snackbar, resources, undoLabel, event,
                    onUndoMove = viewModel::undoMove,
                    onUndoUnblock = viewModel::undoUnblock,
                )
            }
        }
    }

    val actions = remember(viewModel, rulesViewModel, onBack) {
        BlockedNumbersActions(
            onBack = onBack,
            onAdd = viewModel::openAdd,
            onEdit = viewModel::openEdit,
            onToggleSelected = viewModel::toggleSelected,
            onClearSelection = viewModel::clearSelection,
            onSelectAll = viewModel::selectAll,
            onUnblock = viewModel::unblock,
            onCopy = { item ->
                val clipboard = context.getSystemService(ClipboardManager::class.java)
                clipboard?.setPrimaryClip(ClipData.newPlainText(item.number, item.number))
                viewModel.clearSelection()
                // Android 13+ confirms copies itself; a second message would be noise.
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) scope.launch { snackbar.showSnackbar(copiedText) }
            },
            onBlockUnknown = { enabled ->
                viewModel.setBlockUnknown(enabled)
                val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
                    PackageManager.PERMISSION_GRANTED
                if (enabled && !granted) contactsPermission.launch(Manifest.permission.READ_CONTACTS)
            },
            // The app's own file picker is not "leaving the app": no second PIN on return.
            onImport = {
                context.appLockManager.allowOwnTrip()
                importLauncher.launch(arrayOf("text/plain", "application/octet-stream"))
            },
            onExport = {
                context.appLockManager.allowOwnTrip()
                exportLauncher.launch("blocked-numbers.txt")
            },
            onMoveToRules = rulesViewModel::openMigrationFresh,
            onSubmit = viewModel::submit,
            onDismissDialog = viewModel::dismissDialog,
            onMoveOne = viewModel::moveToRules,
            onAddAsRule = viewModel::addAsRule,
        )
    }
    // Only the numbers a new uMessage rule would cover: ones already matched by a rule are not offered again.
    val moveCount = remember(state.numbers, rulesState.loading, rulesState.allowed, rulesState.blocked) {
        if (rulesState.loading) {
            0
        } else {
            LegacyMigration.movableCount(state.numbers, rulesState.allowed + rulesState.blocked)
        }
    }
    BlockedNumbersScreen(state.copy(moveCount = moveCount), snackbar, actions)
    rulesState.migration?.let { MigrationDialogContent(it, rememberMigrationActions(rulesViewModel)) }
}

/** The move dialog's callbacks, the same ones the Rules screen gives it. */
@Composable
private fun rememberMigrationActions(rulesViewModel: RulesViewModel) = remember(rulesViewModel) {
    RulesActions(
        onMigrateNumbers = rulesViewModel::setMigrateNumbers,
        onRemoveFromSystem = rulesViewModel::setRemoveFromSystem,
        onConfirmMigration = rulesViewModel::confirmMigration,
        onCancelMigration = rulesViewModel::cancelMigration,
    )
}

/** Shows one event of the list as a snackbar, with Undo for a move or an unblock. */
private suspend fun showBlockedNumbersEvent(
    snackbar: SnackbarHostState,
    resources: android.content.res.Resources,
    undoLabel: String,
    event: BlockedNumbersEvent,
    onUndoMove: (String, Long) -> Unit,
    onUndoUnblock: (List<String>) -> Unit,
) {
    when (event) {
        is BlockedNumbersEvent.MovedToRules -> {
            val result = snackbar.showSnackbar(
                message = resources.getString(R.string.e_bn_moved, event.number),
                actionLabel = undoLabel,
                duration = SnackbarDuration.Long,
            )
            if (result == SnackbarResult.ActionPerformed) onUndoMove(event.number, event.ruleId)
        }
        is BlockedNumbersEvent.Unblocked -> {
            val count = event.numbers.size
            val result = snackbar.showSnackbar(
                message = resources.getQuantityString(R.plurals.blocknum_unblocked, count, count),
                actionLabel = undoLabel,
                duration = SnackbarDuration.Long,
            )
            if (result == SnackbarResult.ActionPerformed) onUndoUnblock(event.numbers)
        }
        else -> snackbar.showSnackbar(blockedNumbersEventText(resources, event))
    }
}

/** The line of an event that has no Undo. */
private fun blockedNumbersEventText(resources: android.content.res.Resources, event: BlockedNumbersEvent): String =
    when (event) {
        is BlockedNumbersEvent.Imported -> {
            val added = resources.getQuantityString(R.plurals.blocknum_imported, event.count, event.count)
            if (event.skipped > 0) {
                resources.getString(
                    R.string.ui_two_sentences,
                    added,
                    resources.getQuantityString(R.plurals.blocknum_import_skipped, event.skipped, event.skipped),
                )
            } else {
                added
            }
        }
        BlockedNumbersEvent.ImportEmpty -> resources.getString(R.string.blocknum_import_empty)
        BlockedNumbersEvent.ImportFailed -> resources.getString(R.string.blocknum_import_failed)
        is BlockedNumbersEvent.Exported ->
            resources.getQuantityString(R.plurals.blocknum_exported, event.count, event.count)
        BlockedNumbersEvent.ExportEmpty -> resources.getString(R.string.blocknum_export_empty)
        BlockedNumbersEvent.ExportFailed -> resources.getString(R.string.blocknum_export_failed)
        is BlockedNumbersEvent.AddedAsRule -> resources.getString(R.string.e_bn_added_as_rule, event.number)
        // Shown with Undo by showBlockedNumbersEvent.
        BlockedNumbersEvent.Failed, is BlockedNumbersEvent.MovedToRules, is BlockedNumbersEvent.Unblocked ->
            resources.getString(R.string.blocknum_failed)
    }

@Composable
fun BlockedNumbersScreen(
    state: BlockedNumbersUiState,
    snackbarHostState: SnackbarHostState,
    actions: BlockedNumbersActions,
) {
    BackHandler(enabled = state.selecting, onBack = actions.onClearSelection)
    UMessageScaffold(
        topBar = { BlockedNumbersTopBar(state, actions) },
        snackbarHostState = snackbarHostState,
        floatingActionButton = {
            if (state.available && !state.selecting && !state.loading) {
                UMessageFab(
                    text = stringResource(R.string.blocknum_add),
                    icon = Symbols.Add,
                    onClick = actions.onAdd,
                )
            }
        },
    ) { inner ->
        if (state.loading) {
            Box(Modifier.fillMaxSize().padding(top = inner.calculateTopPadding()), contentAlignment = Alignment.Center) {
                val label = stringResource(R.string.spam_loading)
                CircularProgressIndicator(Modifier.semantics { contentDescription = label })
            }
        } else {
            BlockedNumbersList(
                state,
                actions,
                Modifier.padding(top = inner.calculateTopPadding()),
                inner.calculateBottomPadding(),
            )
        }
    }
    state.dialog?.let { dialog -> NumberDialog(dialog, actions) }
}

@Composable
private fun BlockedNumbersTopBar(state: BlockedNumbersUiState, actions: BlockedNumbersActions) {
    if (state.selecting) {
        val selectedItems = state.numbers.filter { it.id in state.selected }
        SelectionTopBar(count = state.selected.size, onClose = actions.onClearSelection) {
            if (state.selected.size < state.numbers.size) {
                IconButton(onClick = actions.onSelectAll) {
                    Icon(Symbols.SelectAll, contentDescription = stringResource(R.string.screens_select_all))
                }
            }
            if (selectedItems.size == 1) {
                IconButton(onClick = { actions.onCopy(selectedItems.first()) }) {
                    Icon(Symbols.ContentCopy, contentDescription = stringResource(R.string.blocknum_copy))
                }
            }
            IconButton(onClick = { actions.onUnblock(state.selected) }) {
                Icon(UnblockIcon, contentDescription = stringResource(R.string.blocknum_unblock))
            }
        }
    } else {
        var menuOpen by remember { mutableStateOf(false) }
        UMessageTopBar(title = stringResource(R.string.rules2_bn_title), onBack = actions.onBack) {
            if (state.available) {
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Symbols.MoreVert, contentDescription = stringResource(R.string.spam_more_options))
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.blocknum_import)) },
                            leadingIcon = { Icon(Symbols.Download, contentDescription = null) },
                            onClick = { menuOpen = false; actions.onImport() },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.blocknum_export)) },
                            leadingIcon = { Icon(Symbols.Upload, contentDescription = null) },
                            enabled = state.numbers.isNotEmpty(),
                            onClick = { menuOpen = false; actions.onExport() },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BlockedNumbersList(
    state: BlockedNumbersUiState,
    actions: BlockedNumbersActions,
    modifier: Modifier,
    bottomInset: Dp,
) {
    val reduceMotion = rememberReduceMotion()
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        // Room for the extended FAB below the last row.
        contentPadding = PaddingValues(bottom = FabListBottomPadding + bottomInset),
    ) {
        // U51: the empty state already says whose list this is, so the note shows only once there are numbers.
        if (state.available && state.numbers.isNotEmpty()) {
            item(key = "about") { AndroidListNote(state.moveCount, actions.onMoveToRules) }
        }
        // Only matters in Off mode, where it turns unknown-number filtering back on (as Strict); Strict and Smart
        // ignore it, so it is not shown then at all (SE-28 / VS-30).
        if (state.mode == UnknownSenderMode.OFF) {
            item(key = "unknown") {
                SwitchRow(
                    title = stringResource(R.string.blocknum_unknown_title),
                    body = stringResource(R.string.e_bn_unknown_body),
                    checked = state.blockUnknown,
                    onCheckedChange = actions.onBlockUnknown,
                )
            }
            item(key = "divider") {
                HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
        when {
            !state.available -> item(key = "unavailable") {
                EmptyState(
                    icon = Symbols.Block,
                    title = stringResource(R.string.blocknum_unavailable_title),
                    body = stringResource(R.string.blocknum_unavailable_body),
                )
            }
            state.numbers.isEmpty() -> item(key = "empty") {
                EmptyState(
                    icon = Symbols.Block,
                    title = stringResource(R.string.blocknum_empty_title),
                    // No button here: the "Add number" FAB is always shown, so a second one would duplicate it.
                    body = stringResource(R.string.blocknum_empty_body),
                )
            }
            else -> {
                item(key = "header") { SectionHeader(stringResource(R.string.blocknum_section_numbers)) }
                items(state.numbers, key = { it.id }) { item ->
                    BlockedNumberRow(
                        item = item,
                        selected = item.id in state.selected,
                        selecting = state.selecting,
                        actions = actions,
                        modifier = Modifier.animateItem(
                            fadeInSpec = if (reduceMotion) null else tween(150),
                            placementSpec = if (reduceMotion) snap() else spring(),
                            fadeOutSpec = if (reduceMotion) null else tween(150),
                        ),
                    )
                }
            }
        }
    }
}

/** Says whose list this is, and offers the move into uMessage rules as the first action. */
@Composable
private fun AndroidListNote(count: Int, onMove: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surfaceContainerHigh, contentColor = colors.onSurface),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Symbols.Info, contentDescription = null, modifier = Modifier.size(20.dp))
                Text(stringResource(R.string.rules2_bn_info), style = MaterialTheme.typography.bodyMedium)
            }
            if (count > 0) {
                FilledTonalButton(onClick = onMove) {
                    Text(LocalResources.current.getQuantityString(R.plurals.rules2_bn_move, count, count))
                }
            }
        }
    }
}

/** Two-line M3 list item: contact name or number, the number or "Wildcard pattern" below, overflow menu at the end. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BlockedNumberRow(
    item: BlockedNumberItem,
    selected: Boolean,
    selecting: Boolean,
    actions: BlockedNumbersActions,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    // A wildcard is not a bare number, so displayNumber leaves it as typed.
    val shownNumber = isolateNumbers(displayNumber(item.number))
    val title = item.contactName ?: shownNumber
    // Every row says something (SE-30): the number under a contact, what a wildcard covers, or what the entry does.
    val supporting = when {
        item.contactName != null -> shownNumber
        item.isPattern -> stringResource(R.string.blocknum_pattern)
        BlockedNumberFormat.isShortCode(item.number) -> stringResource(R.string.e_bn_row_short_code)
        else -> stringResource(R.string.e_bn_row_calls_texts)
    }
    val selectedText = stringResource(if (selected) R.string.spam_state_selected else R.string.spam_state_not_selected)
    val selectLabel = stringResource(R.string.spam_select)
    val editLabel = stringResource(R.string.blocknum_edit_for, title)
    val copyLabel = stringResource(R.string.blocknum_copy)
    val unblockLabel = stringResource(R.string.blocknum_unblock_for, title)
    val moveLabel = stringResource(R.string.e_bn_move_one)
    val background by animateColorAsState(
        if (selected) colors.primaryContainer else colors.background,
        label = "numberRowBackground",
    )

    ListItem(
        modifier = modifier
            .background(background)
            .combinedClickable(
                onClickLabel = if (selecting) selectLabel else editLabel,
                onLongClickLabel = selectLabel,
                onClick = { if (selecting) actions.onToggleSelected(item.id) else actions.onEdit(item) },
                onLongClick = { actions.onToggleSelected(item.id) },
            )
            .semantics(mergeDescendants = true) {
                stateDescription = selectedText
                this.selected = selected
                if (!selecting) {
                    customActions = listOf(
                        CustomAccessibilityAction(copyLabel) { actions.onCopy(item); true },
                        CustomAccessibilityAction(moveLabel) { actions.onMoveOne(item); true },
                        CustomAccessibilityAction(unblockLabel) { actions.onUnblock(setOf(item.id)); true },
                    )
                }
            },
        colors = if (selected) {
            ListItemDefaults.colors(
                containerColor = Color.Transparent,
                headlineColor = colors.onPrimaryContainer,
                supportingColor = colors.onPrimaryContainer,
                trailingIconColor = colors.onPrimaryContainer,
            )
        } else {
            ListItemDefaults.colors(containerColor = Color.Transparent)
        },
        leadingContent = { SenderAvatar(label = title, selected = selected) },
        headlineContent = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(supporting, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        trailingContent = if (selecting) null else {
            { BlockedNumberRowActions(item, title, unblockLabel, copyLabel, actions) }
        },
    )
}

/**
 * Unblock as a visible button, since it is the main thing done on this list (SE-30); Undo follows in the snackbar.
 * The row menu repeats Unblock and holds Copy and the move into uMessage rules.
 */
@Composable
private fun BlockedNumberRowActions(
    item: BlockedNumberItem,
    title: String,
    unblockLabel: String,
    copyLabel: String,
    actions: BlockedNumbersActions,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row {
        IconButton(onClick = { actions.onUnblock(setOf(item.id)) }) {
            Icon(UnblockIcon, contentDescription = unblockLabel)
        }
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(Symbols.MoreVert, contentDescription = stringResource(R.string.blocknum_more_for, title))
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                // Owner notes r3: Unblock is in the menu too, not only behind the icon and long-press selection.
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.blocknum_unblock)) },
                    leadingIcon = { Icon(UnblockIcon, contentDescription = null) },
                    onClick = { menuOpen = false; actions.onUnblock(setOf(item.id)) },
                )
                DropdownMenuItem(
                    text = { Text(copyLabel) },
                    leadingIcon = { Icon(Symbols.ContentCopy, contentDescription = null) },
                    onClick = { menuOpen = false; actions.onCopy(item) },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.e_bn_move_one)) },
                    leadingIcon = { Icon(Symbols.Rule, contentDescription = null) },
                    onClick = { menuOpen = false; actions.onMoveOne(item) },
                )
            }
        }
    }
}

/**
 * Add or edit a number on the Android list. The example is a local number (bug 15). A short code gets a warning,
 * because Android would throw its texts away unseen, and offers a uMessage rule instead (BR-28).
 */
@Composable
private fun NumberDialog(dialog: BlockedNumberDialog, actions: BlockedNumbersActions) {
    var value by rememberSaveable { mutableStateOf(dialog.original?.number.orEmpty()) }
    var showError by rememberSaveable { mutableStateOf(false) }
    val number = BlockedNumberFormat.normalizeInput(value)
    val error = when {
        number.isEmpty() -> stringResource(R.string.blocknum_err_empty)
        !BlockedNumberFormat.isValidEntry(number) -> stringResource(R.string.blocknum_err_invalid)
        else -> null
    }
    val shortCode = error == null && BlockedNumberFormat.isShortCode(number)
    val submit = { if (error == null) actions.onSubmit(value) else showError = true }
    AlertDialog(
        onDismissRequest = actions.onDismissDialog,
        title = {
            val adding = dialog.original == null
            Text(stringResource(if (adding) R.string.blocknum_add_title else R.string.blocknum_edit_title))
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it; showError = false },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.blocknum_field_label)) },
                    singleLine = true,
                    isError = showError && error != null,
                    supportingText = {
                        Text(if (showError && error != null) error else stringResource(R.string.e_bn_field_help))
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                )
                if (shortCode) ShortCodeWarning(onAddAsRule = { actions.onAddAsRule(value) })
            }
        },
        confirmButton = { TextButton(onClick = submit) { Text(stringResource(R.string.screens_ok)) } },
        dismissButton = {
            TextButton(onClick = actions.onDismissDialog) { Text(stringResource(R.string.screens_cancel)) }
        },
    )
}

/** The short-code warning of the add dialog, with its way out. */
@Composable
internal fun ShortCodeWarning(onAddAsRule: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Symbols.Warning, contentDescription = null, tint = colors.error, modifier = Modifier.size(18.dp))
            Text(
                stringResource(R.string.e_bn_short_code_warning),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.error,
            )
        }
        FilledTonalButton(onClick = onAddAsRule) { Text(stringResource(R.string.e_bn_add_as_rule)) }
    }
}

/** Material Symbols "do_not_disturb_on" (a ring with a bar): take a number off the block list (SE-31). */
private val UnblockIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "Unblock",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 960f,
        viewportHeight = 960f,
    ).apply {
        addPath(
            pathData = PathParser().parsePathString(UNBLOCK_PATH).toNodes(),
            fill = SolidColor(Color.Black),
            pathFillType = PathFillType.EvenOdd,
        )
    }.build()
}

private const val UNBLOCK_PATH =
    "M480,880Q397,880 324,848.5Q251,817 197,763Q143,709 111.5,636Q80,563 80,480Q80,397 111.5,324Q143,251 197,197" +
        "Q251,143 324,111.5Q397,80 480,80Q563,80 636,111.5Q709,143 763,197Q817,251 848.5,324Q880,397 880,480" +
        "Q880,563 848.5,636Q817,709 763,763Q709,817 636,848.5Q563,880 480,880Z" +
        "M480,800Q614,800 707,707Q800,614 800,480Q800,346 707,253Q614,160 480,160Q346,160 253,253Q160,346 160,480" +
        "Q160,614 253,707Q346,800 480,800Z" +
        "M280,520L680,520L680,440L280,440Z"

// region Previews

private val previewNumbers = listOf(
    BlockedNumberItem(1, "+14155550100", "Alex Doe"),
    BlockedNumberItem(2, "+14155550133"),
    BlockedNumberItem(3, "+91140*"),
    BlockedNumberItem(4, "+15550001234"),
)

@Composable
private fun NumbersPreviewHost(state: BlockedNumbersUiState, rtl: Boolean = false) = UMessageTheme {
    val direction = if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr
    CompositionLocalProvider(LocalLayoutDirection provides direction) {
        BlockedNumbersScreen(state, remember { SnackbarHostState() }, BlockedNumbersActions())
    }
}

@Preview(name = "Blocked numbers", showBackground = true, widthDp = 360, heightDp = 720)
@Composable
private fun BlockedNumbersPreview() = NumbersPreviewHost(BlockedNumbersUiState(loading = false, numbers = previewNumbers, blockUnknown = true))

@Preview(
    name = "Blocked numbers - dark", showBackground = true, widthDp = 360, heightDp = 720,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun BlockedNumbersDarkPreview() =
    NumbersPreviewHost(BlockedNumbersUiState(loading = false, numbers = previewNumbers, blockUnknown = true))

@Preview(name = "Blocked numbers - RTL", showBackground = true, widthDp = 360, heightDp = 720, locale = "fa")
@Composable
private fun BlockedNumbersRtlPreview() =
    NumbersPreviewHost(BlockedNumbersUiState(loading = false, numbers = previewNumbers), rtl = true)

@Preview(name = "Blocked numbers - selecting", showBackground = true, widthDp = 360, heightDp = 720)
@Composable
private fun BlockedNumbersSelectingPreview() =
    NumbersPreviewHost(BlockedNumbersUiState(loading = false, numbers = previewNumbers, selected = setOf(2L)))

@Preview(name = "Blocked numbers - empty", showBackground = true, widthDp = 360, heightDp = 720, fontScale = 1.3f)
@Composable
private fun BlockedNumbersEmptyPreview() = NumbersPreviewHost(BlockedNumbersUiState(loading = false))

@Preview(name = "Blocked numbers - unavailable", showBackground = true, widthDp = 360, heightDp = 720)
@Composable
private fun BlockedNumbersUnavailablePreview() = NumbersPreviewHost(BlockedNumbersUiState(loading = false, available = false))

// endregion
