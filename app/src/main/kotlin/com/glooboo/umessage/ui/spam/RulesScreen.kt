package com.glooboo.umessage.ui.spam

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.glooboo.umessage.ui.components.CheckboxRow
import com.glooboo.umessage.ui.components.FabListBottomPadding
import com.glooboo.umessage.ui.components.UMessageFab
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.glooboo.umessage.ui.icons.Symbols
import kotlinx.coroutines.launch
import com.glooboo.umessage.R
import com.glooboo.umessage.spam.ListKind
import com.glooboo.umessage.spam.RuleType
import com.glooboo.umessage.spam.SpamRule
import com.glooboo.umessage.ui.components.ConfirmDialog
import com.glooboo.umessage.ui.components.EmptyState
import com.glooboo.umessage.ui.components.InfoChip
import com.glooboo.umessage.ui.components.rememberReduceMotion
import com.glooboo.umessage.ui.theme.UMessageTheme
import androidx.compose.ui.unit.Dp
import com.glooboo.umessage.ui.components.UMessageScaffold
import com.glooboo.umessage.ui.components.SelectionTopBar
import com.glooboo.umessage.ui.components.UMessageTopBar
import com.glooboo.umessage.ui.components.GroupShape
import com.glooboo.umessage.ui.components.displayNumber
import com.glooboo.umessage.ui.components.isolateNumbers

@Immutable
class RulesActions(
    val onBack: () -> Unit = {},
    val onSelectTab: (ListKind) -> Unit = {},
    val onAdd: () -> Unit = {},
    val onDelete: (SpamRule) -> Unit = {},
    /** SE-16: rules are backed up with everything else, on Settings > Backup and restore. */
    val onOpenBackup: () -> Unit = {},
    val onDismissForm: () -> Unit = {},
    /** Numbers chosen from the contacts in the add sheet: one rule per number. */
    val onPickContacts: (List<PickedNumber>) -> Unit = {},
    val onType: (RuleType) -> Unit = {},
    val onPattern: (String) -> Unit = {},
    val onSim: (Int?) -> Unit = {},
    val onNote: (String) -> Unit = {},
    val onSubmit: () -> Unit = {},
    val onConfirmSubmit: () -> Unit = {},
    val onCancelConfirm: () -> Unit = {},
    // SU-09
    val onEdit: (SpamRule) -> Unit = {},
    val onQuery: (String) -> Unit = {},
    val onTypeFilter: (RuleType?) -> Unit = {},
    val onToggleSelected: (Long) -> Unit = {},
    val onSelectAll: () -> Unit = {},
    val onClearSelection: () -> Unit = {},
    val onDeleteSelected: () -> Unit = {},
    // SU-33
    val onOpenSystemBlockList: () -> Unit = {},
    val onOpenMigration: () -> Unit = {},
    val onDismissLegacyBanner: () -> Unit = {},
    val onMigrateNumbers: (Boolean) -> Unit = {},
    val onRemoveFromSystem: (Boolean) -> Unit = {},
    val onConfirmMigration: () -> Unit = {},
    val onCancelMigration: () -> Unit = {},
    // Design review 2
    val onKind: (ListKind) -> Unit = {},
    val onDeleteEditing: () -> Unit = {},
    // Wave 3: starter examples of the empty tabs (ID-53)
    val onExample: (ListKind, RuleType, String) -> Unit = { _, _, _ -> },
)

@Composable
fun RulesRoute(
    onBack: () -> Unit,
    onOpenSystemBlockList: () -> Unit = {},
    onOpenBackup: () -> Unit = {},
    /** Opens the "move older lists" dialog as soon as the lists are read (from the Android block list screen). */
    openMigration: Boolean = false,
) {
    val viewModel: RulesViewModel = viewModel(factory = RulesViewModel.Factory)
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    var migrationOpened by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(openMigration, state.legacy) {
        if (openMigration && !migrationOpened && !state.legacy.isEmpty) {
            migrationOpened = true
            viewModel.openMigration()
        }
    }

    // The phone app can change the system block list while this screen is in the background.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refreshLegacy() }

    RulesEvents(viewModel, snackbar)

    val actions = remember(viewModel, onBack, onOpenBackup) {
        RulesActions(
            onBack = onBack,
            onSelectTab = viewModel::selectTab,
            onAdd = viewModel::openForm,
            onDelete = viewModel::delete,
            onOpenBackup = onOpenBackup,
            onDismissForm = viewModel::dismissForm,
            onPickContacts = viewModel::addFromContacts,
            onType = viewModel::setType,
            onPattern = viewModel::setPattern,
            onSim = viewModel::setSim,
            onNote = viewModel::setNote,
            onSubmit = viewModel::submitForm,
            onConfirmSubmit = viewModel::confirmSubmit,
            onCancelConfirm = viewModel::cancelConfirm,
            onEdit = viewModel::openEdit,
            onQuery = viewModel::setQuery,
            onTypeFilter = viewModel::setTypeFilter,
            onToggleSelected = viewModel::toggleSelected,
            onSelectAll = viewModel::selectAllVisible,
            onClearSelection = viewModel::clearSelection,
            onDeleteSelected = viewModel::deleteSelected,
            onOpenSystemBlockList = onOpenSystemBlockList,
            onOpenMigration = viewModel::openMigration,
            onDismissLegacyBanner = viewModel::dismissLegacyBanner,
            onMigrateNumbers = viewModel::setMigrateNumbers,
            onRemoveFromSystem = viewModel::setRemoveFromSystem,
            onConfirmMigration = viewModel::confirmMigration,
            onCancelMigration = viewModel::cancelMigration,
            onKind = viewModel::setKind,
            onDeleteEditing = viewModel::deleteEditing,
            onExample = viewModel::openExample,
        )
    }
    RulesScreen(state, snackbar, actions)
}

/** Snackbars for the rules screen's one-shot events, with Undo where the action can be taken back. */
@Composable
private fun RulesEvents(viewModel: RulesViewModel, snackbar: SnackbarHostState) {
    val resources = LocalContext.current.resources
    val undoLabel = stringResource(R.string.spam_undo)
    val deletedText = stringResource(R.string.spam_rules_deleted)
    LaunchedEffect(viewModel) {
        viewModel.eventFlow.collect { event ->
            snackbar.currentSnackbarData?.dismiss()
            launch {
                when (event) {
                    is RulesEvent.Deleted -> {
                        val count = event.rules.size
                        val text = if (count == 1) {
                            deletedText
                        } else {
                            resources.getQuantityString(R.plurals.rules_deleted_many, count, count)
                        }
                        val result = snackbar.showUndo(text, undoLabel)
                        if (result == SnackbarResult.ActionPerformed) viewModel.restore(event.rules)
                    }
                    is RulesEvent.Migrated ->
                        snackbar.showSnackbar(migratedText(resources, event), duration = SnackbarDuration.Long)
                    is RulesEvent.AddedFromContacts ->
                        snackbar.showSnackbar(contactsAddedText(resources, event), duration = SnackbarDuration.Long)
                }
            }
        }
    }
}

/** "3 rules added from contacts. Skipped: 1 (already have a rule)." */
internal fun contactsAddedText(resources: android.content.res.Resources, event: RulesEvent.AddedFromContacts): String {
    if (event.added == 0) return resources.getString(R.string.p44_rules_none_added)
    val added = resources.getString(R.string.p44_rules_added, event.added)
    if (event.skipped == 0) return added
    val skipped = resources.getString(R.string.p44_rules_skipped, event.skipped)
    return resources.getString(R.string.ui_two_sentences, added, skipped)
}

/** One snackbar line for a finished move, e.g. "3 words moved into rules. 2 numbers added as rules." */
internal fun migratedText(resources: android.content.res.Resources, event: RulesEvent.Migrated): String {
    val parts = buildList {
        if (event.keywords > 0) add(resources.getQuantityString(R.plurals.rules_migrated_keywords, event.keywords, event.keywords))
        if (event.numbers > 0) add(resources.getQuantityString(R.plurals.rules_migrated_numbers, event.numbers, event.numbers))
        if (event.removedFromSystem > 0) {
            add(resources.getQuantityString(R.plurals.rules_migrated_removed, event.removedFromSystem, event.removedFromSystem))
        }
        if (event.kept > 0) add(resources.getQuantityString(R.plurals.rules_migrated_kept, event.kept, event.kept))
    }
    if (parts.isEmpty()) return resources.getString(R.string.rules_migrated_nothing)
    return parts.reduce { a, b -> resources.getString(R.string.ui_two_sentences, a, b) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RulesScreen(
    state: RulesUiState,
    snackbarHostState: SnackbarHostState,
    actions: RulesActions,
) {
    var testOpen by rememberSaveable { mutableStateOf(false) }
    var helpOpen by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = state.selecting, onBack = actions.onClearSelection)
    // A small bar with the title inline (BR-21 / VS-06): the tabs sit right under it, with no empty title band.
    UMessageScaffold(
        topBar = { RulesTopBar(state, actions, onTest = { testOpen = true }, onHelp = { helpOpen = true }) },
        snackbarHostState = snackbarHostState,
        floatingActionButton = {
            if (!state.selecting) {
                UMessageFab(
                    text = stringResource(R.string.spam_rules_add),
                    icon = Symbols.Add,
                    onClick = actions.onAdd,
                )
            }
        },
    ) { inner ->
        RulesBody(
            state = state,
            actions = actions,
            bottomPadding = inner.calculateBottomPadding(),
            modifier = Modifier.padding(top = inner.calculateTopPadding()).fillMaxSize(),
        )
    }
    state.form?.let { AddRuleSheet(it, state.sims, actions) }
    if (testOpen) {
        TestMessageSheet(
            sims = state.sims,
            rules = state.allowed + state.blocked,
            onCreateRule = { testOpen = false; actions.onAdd() },
            onDismiss = { testOpen = false },
        )
    }
    if (helpOpen) KeywordHelpDialog(onDismiss = { helpOpen = false })
    state.migration?.let { MigrationDialogContent(it, actions) }
}

/** Under the bar: the tabs, the old-lists banner, the search, then the rules (or loading, or an empty state). */
@Composable
private fun RulesBody(
    state: RulesUiState,
    actions: RulesActions,
    bottomPadding: Dp,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        RulesTabs(state, actions)
        if (state.showLegacyBanner && !state.selecting && state.tab == ListKind.BLOCK) LegacyBanner(actions)
        val legacyRows = if (state.tab == ListKind.BLOCK) state.legacy.keywords.size + state.legacy.numbers.size else 0
        if (!state.loading && RulesSearchPolicy.showSearch(state.current.size + legacyRows, state.filtering)) {
            RulesSearch(state, actions)
        }
        RulesContent(state, actions, bottomPadding)
    }
}

/** The rules themselves, or loading, or the empty state (for a search that matched nothing, its own). */
@Composable
private fun RulesContent(state: RulesUiState, actions: RulesActions, bottomPadding: Dp) {
    val nothingShown = state.visible.isEmpty() &&
        (state.tab == ListKind.ALLOW || (state.visibleLegacyKeywords.isEmpty() && state.visibleLegacyNumbers.isEmpty()))
    when {
        state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val label = stringResource(R.string.spam_loading)
            CircularProgressIndicator(Modifier.semantics { contentDescription = label })
        }
        nothingShown && state.filtering -> EmptyState(
            icon = Symbols.Search,
            title = stringResource(R.string.rules_search_empty_title),
            body = stringResource(R.string.rules_search_empty_body),
        )
        nothingShown -> RulesEmpty(state.tab, actions.onExample)
        else -> RulesList(state, actions, bottomPadding)
    }
}

/**
 * Title "Rules" (BR-13). While picking rules the bar shows the count and the bulk actions, and Back ends the
 * selection.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RulesTopBar(state: RulesUiState, actions: RulesActions, onTest: () -> Unit, onHelp: () -> Unit) {
    if (state.selecting) {
        SelectionTopBar(count = state.selected.size, onClose = actions.onClearSelection) {
            if (state.selected.size < state.visible.size) {
                IconButton(onClick = actions.onSelectAll) {
                    Icon(Symbols.SelectAll, contentDescription = stringResource(R.string.screens_select_all))
                }
            }
            IconButton(onClick = actions.onDeleteSelected) {
                Icon(Symbols.Delete, contentDescription = stringResource(R.string.rules_delete_selected))
            }
        }
        return
    }
    var menuOpen by remember { mutableStateOf(false) }
    UMessageTopBar(title = stringResource(R.string.e_rules_title), onBack = actions.onBack) {
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(Symbols.MoreVert, contentDescription = stringResource(R.string.spam_more_options))
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                // SE-16: one place for backups. Rules are part of the Settings backup file.
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.e_rules_menu_backup)) },
                    leadingIcon = { Icon(Symbols.Upload, contentDescription = null) },
                    onClick = { menuOpen = false; actions.onOpenBackup() },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.w3b_test_menu)) },
                    leadingIcon = { Icon(Symbols.Search, contentDescription = null) },
                    onClick = { menuOpen = false; onTest() },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.w7d_help_menu)) },
                    leadingIcon = { Icon(Symbols.Info, contentDescription = null) },
                    onClick = { menuOpen = false; onHelp() },
                )
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.rules2_menu_system)) },
                    leadingIcon = { Icon(Symbols.Block, contentDescription = null) },
                    onClick = { menuOpen = false; actions.onOpenSystemBlockList() },
                )
            }
        }
    }
}

/** Search box and type chips (SU-09). The search reads Persian and Arabic letter forms alike. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RulesSearch(state: RulesUiState, actions: RulesActions) {
    Column(Modifier.padding(horizontal = 16.dp).padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            value = state.query,
            onValueChange = actions.onQuery,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.rules_search_hint)) },
            leadingIcon = { Icon(Symbols.Search, contentDescription = null) },
            trailingIcon = {
                if (state.query.isNotEmpty()) {
                    IconButton(onClick = { actions.onQuery("") }) {
                        Icon(Symbols.Close, contentDescription = stringResource(R.string.rules_search_clear))
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(28.dp),
            // The same filled search container as the Messages search; outlines stay on form inputs (VS-08).
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                focusedBorderColor = Color.Transparent,
                unfocusedBorderColor = Color.Transparent,
            ),
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Search),
        )
        val types = RulesSearchPolicy.chipTypes(state.current, state.typeFilter)
        // Only the types in the list; they wrap onto a second line instead of running off the screen edge.
        if (types.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RuleChoiceChip(
                    selected = state.typeFilter == null,
                    onClick = { actions.onTypeFilter(null) },
                    label = stringResource(R.string.rules_filter_all_types),
                )
                types.forEach { type ->
                    RuleChoiceChip(
                        selected = state.typeFilter == type,
                        onClick = { actions.onTypeFilter(if (state.typeFilter == type) null else type) },
                        label = ruleRowTypeLabel(type),
                    )
                }
            }
        }
    }
}

/** Offer to move the old Fossify lists into rules (SU-33). "Not now" hides it for good; the Blocked tab keeps a Move button. */
@Composable
private fun LegacyBanner(actions: RulesActions) {
    val colors = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 8.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surfaceContainerHigh, contentColor = colors.onSurface),
        shape = RoundedCornerShape(16.dp),
    ) {
        // One short block: title and one line, a filled Review button, and a dismissal that is remembered.
        Row(
            Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    stringResource(R.string.rules2_legacy_banner_title),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.semantics { heading() },
                )
                Text(stringResource(R.string.rules2_legacy_banner_body), style = MaterialTheme.typography.bodySmall)
            }
            FilledTonalButton(onClick = actions.onOpenMigration) { Text(stringResource(R.string.rules2_legacy_review)) }
            IconButton(onClick = actions.onDismissLegacyBanner) {
                Icon(Symbols.Close, contentDescription = stringResource(R.string.rules_legacy_banner_not_now))
            }
        }
    }
}

@Composable
private fun RulesTabs(state: RulesUiState, actions: RulesActions) {
    val kinds = listOf(ListKind.ALLOW, ListKind.BLOCK)
    PrimaryTabRow(
        selectedTabIndex = kinds.indexOf(state.tab),
        containerColor = MaterialTheme.colorScheme.background,
    ) {
        kinds.forEach { kind ->
            val name = stringResource(
                if (kind == ListKind.ALLOW) R.string.rules2_tab_allowed else R.string.rules2_tab_blocked,
            )
            // While searching, the counts say how many rules match.
            val count = when {
                kind == ListKind.ALLOW -> if (state.filtering) state.visibleAllowed.size else state.allowed.size
                else -> if (state.filtering) state.visibleBlocked.size else state.blocked.size
            }
            Tab(
                selected = state.tab == kind,
                onClick = { actions.onSelectTab(kind) },
                modifier = Modifier.heightIn(min = 48.dp),
                text = { Text(stringResource(R.string.ui_tab_with_count, name, count)) },
                // M3: only the selected tab uses the primary colour.
                selectedContentColor = MaterialTheme.colorScheme.primary,
                unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** A starter rule of an empty tab (ID-53): its button label, and the rule it opens the add sheet with. */
private data class RuleExample(
    val type: RuleType,
    @androidx.annotation.StringRes val label: Int,
    val value: String = "",
)

/** Concrete starters: a word Persian promotions end with, ad short codes, and the senders people allow most. */
private val blockExamples = listOf(
    RuleExample(RuleType.KEYWORD, R.string.e_example_block_cancel_word, RuleExamples.CANCEL_WORD),
    RuleExample(RuleType.PREFIX, R.string.e_example_block_ad_prefix, RuleExamples.AD_PREFIX),
    RuleExample(RuleType.SENDER_ID, R.string.w3b_example_block_sender),
)
private val allowExamples = listOf(
    RuleExample(RuleType.SENDER_ID, R.string.w3b_example_allow_sender),
    RuleExample(RuleType.NUMBER, R.string.e_example_allow_number),
)

@Composable
private fun RulesEmpty(tab: ListKind, onExample: (ListKind, RuleType, String) -> Unit) {
    val allow = tab == ListKind.ALLOW
    EmptyState(
        iconRes = if (allow) R.drawable.ic_spam_shield_check else R.drawable.ic_spam_shield,
        title = stringResource(if (allow) R.string.rules2_empty_allowed_title else R.string.rules2_empty_blocked_title),
        body = stringResource(if (allow) R.string.rules2_empty_allowed_body else R.string.rules2_empty_blocked_body),
        action = {
            // Tappable starters (ID-53): each opens the add sheet already set to that rule, filled in when concrete.
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.w3b_examples_title),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                (if (allow) allowExamples else blockExamples).forEach { example ->
                    OutlinedButton(
                        onClick = { onExample(tab, example.type, example.value) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) {
                        Text(stringResource(example.label), textAlign = TextAlign.Center)
                    }
                }
            }
        },
    )
}

@Composable
private fun RulesList(state: RulesUiState, actions: RulesActions, bottomInset: Dp) {
    val reduceMotion = rememberReduceMotion()
    // Block rules that an allow rule always wins over (ID-42).
    val overridden = remember(state.allowed, state.blocked) {
        RuleConflicts.overriddenBlockIds(state.allowed + state.blocked)
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = 8.dp,
            bottom = FabListBottomPadding + bottomInset,
        ),
        verticalArrangement = Arrangement.spacedBy(GroupShape.GAP_DP.dp),
    ) {
        val rules = state.visible
        itemsIndexed(rules, key = { _, rule -> rule.id }) { index, rule ->
            RuleRow(
                rule = rule,
                shape = groupShape(index, rules.size),
                sims = state.sims,
                selected = rule.id in state.selected,
                selecting = state.selecting,
                actions = actions,
                overridden = rule.id in overridden,
                modifier = Modifier.animateItem(
                    fadeInSpec = if (reduceMotion) null else tween(150),
                    placementSpec = if (reduceMotion) snap() else spring(),
                    fadeOutSpec = if (reduceMotion) null else tween(150),
                ),
            )
        }
        // SU-33: the old lists, read-only, so every block is visible in one place.
        if (state.tab == ListKind.BLOCK && !state.selecting) {
            val keywords = state.visibleLegacyKeywords
            if (keywords.isNotEmpty()) {
                item(key = "legacy-keywords-header") {
                    LegacyHeader(
                        title = stringResource(R.string.rules_legacy_section_keywords),
                        body = stringResource(R.string.rules_legacy_section_keywords_body),
                        buttonLabel = stringResource(R.string.rules_legacy_move),
                        onButton = actions.onOpenMigration,
                    )
                }
                itemsIndexed(keywords, key = { _, word -> "legacy-keyword-$word" }) { index, word ->
                    LegacyRow(word, RuleType.KEYWORD, stringResource(R.string.rules_legacy_chip_old_list), groupShape(index, keywords.size))
                }
            }
            val numbers = state.visibleLegacyNumbers
            if (numbers.isNotEmpty()) {
                item(key = "legacy-numbers-header") {
                    LegacyHeader(
                        title = stringResource(R.string.rules2_system_header),
                        body = stringResource(R.string.rules2_system_body),
                        buttonLabel = stringResource(R.string.rules2_system_move),
                        onButton = actions.onOpenMigration,
                        secondLabel = stringResource(R.string.rules2_system_manage),
                        onSecond = actions.onOpenSystemBlockList,
                        warning = true,
                    )
                }
                itemsIndexed(numbers, key = { _, item -> "legacy-number-${item.id}" }) { index, item ->
                    LegacyRow(
                        value = legacyNumberText(item),
                        type = if (item.isPattern) RuleType.PREFIX else RuleType.NUMBER,
                        chip = stringResource(R.string.rules_legacy_chip_system),
                        shape = groupShape(index, numbers.size),
                    )
                }
            }
        }
    }
}

/** A number of the Android block list, written like every other number on screen, after its contact's name. */
@Composable
private fun legacyNumberText(item: BlockedNumberItem): String {
    val number = isolateNumbers(displayNumber(item.number))
    return item.contactName?.let { "$it, $number" } ?: number
}

private fun groupShape(index: Int, size: Int): Shape {
    val corners = GroupShape.corners(index, size)
    return RoundedCornerShape(
        topStart = corners.topDp.dp, topEnd = corners.topDp.dp,
        bottomEnd = corners.bottomDp.dp, bottomStart = corners.bottomDp.dp,
    )
}

@Composable
private fun LegacyHeader(
    title: String,
    body: String,
    buttonLabel: String,
    onButton: () -> Unit,
    secondLabel: String? = null,
    onSecond: () -> Unit = {},
    /** The entries are invisible to uMessage (system list): the explanation reads as a warning, not as small print. */
    warning: Boolean = false,
) {
    Column(Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.semantics { heading() },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (warning) {
                Icon(
                    Symbols.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp),
                )
            }
            Text(
                body,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = if (warning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = onButton) { Text(buttonLabel) }
            if (secondLabel != null) OutlinedButton(onClick = onSecond) { Text(secondLabel) }
        }
    }
}

/** An entry of an old list: shown like a rule, without edit or delete, and marked with the list it is on. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LegacyRow(value: String, type: RuleType, chip: String, shape: Shape) {
    val colors = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
        colors = CardDefaults.cardColors(containerColor = colors.surfaceContainerLow),
        shape = shape,
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(colors.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                // The icon names the type, as on a uMessage rule's row; no chip says it again (BR-25).
                Icon(
                    ruleTypeIcon(type),
                    contentDescription = ruleTypeLabel(type),
                    tint = colors.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(value, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    InfoChip(chip, icon = Symbols.Block)
                }
            }
        }
    }
}

@Composable
fun ruleTypeLabel(type: RuleType): String = stringResource(
    when (type) {
        RuleType.NUMBER -> R.string.spam_type_number
        RuleType.PREFIX -> R.string.spam_type_prefix
        RuleType.SENDER_ID -> R.string.spam_type_sender_id
        RuleType.KEYWORD -> R.string.spam_type_keyword
        RuleType.REGEX -> R.string.spam_type_regex
    },
)

private fun ruleTypeIcon(type: RuleType): ImageVector = when (type) {
    RuleType.NUMBER -> Symbols.Call
    RuleType.PREFIX -> Symbols.Dialpad
    RuleType.SENDER_ID -> Symbols.Sms
    RuleType.KEYWORD -> Symbols.Tag
    RuleType.REGEX -> Symbols.Code
}

/**
 * The SIM a rule is for, or null when that says nothing: both SIMs is the normal case, and with one SIM (or none
 * we can read) only a rule tied to a SIM that is no longer in the phone is marked, because it no longer applies.
 */
@Composable
private fun ruleSimLabel(rule: SpamRule, sims: List<SimInfo>): String? {
    val id = rule.subscriptionId ?: return null
    val sim = sims.firstOrNull { it.subscriptionId == id }
    return when {
        sim != null && sims.size >= 2 -> stringResource(R.string.spam_sim_label, sim.slot)
        sim != null -> null
        sims.size == 1 -> stringResource(R.string.spam_sim_missing)
        else -> stringResource(R.string.spam_sim_generic)
    }
}

/**
 * A rule's note as the list shows it: one the app wrote in the current language (bug 4), one the user typed in
 * quotes, so the two are told apart (BR-25).
 */
@Composable
private fun ruleNoteText(note: String): String? {
    if (note.isBlank()) return null
    val code = RuleNotes.codeOf(note)
    val text = code?.let(RuleNotes::textRes)
    return if (text != null) stringResource(text) else stringResource(R.string.e_note_quoted, note.trim())
}

/** A rule: tap to edit it, long-press to start picking several (SU-09). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RuleRow(
    rule: SpamRule,
    shape: Shape,
    sims: List<SimInfo>,
    selected: Boolean,
    selecting: Boolean,
    actions: RulesActions,
    modifier: Modifier = Modifier,
    overridden: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    val deleteCd = stringResource(R.string.spam_rules_delete_rule, rule.pattern)
    val editLabel = stringResource(R.string.rules_edit_rule, rule.pattern)
    val selectLabel = stringResource(R.string.spam_select)
    val selectedText = stringResource(if (selected) R.string.spam_state_selected else R.string.spam_state_not_selected)
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .combinedClickable(
                onClickLabel = if (selecting) selectLabel else editLabel,
                onLongClickLabel = selectLabel,
                onClick = { if (selecting) actions.onToggleSelected(rule.id) else actions.onEdit(rule) },
                onLongClick = { actions.onToggleSelected(rule.id) },
            )
            .semantics {
                if (selecting) {
                    stateDescription = selectedText
                    this.selected = selected
                } else {
                    // The trash icon moved into the editor; screen readers get Delete as an action on the row.
                    customActions = listOf(CustomAccessibilityAction(deleteCd) { actions.onDelete(rule); true })
                }
            },
        colors = CardDefaults.cardColors(
            containerColor = if (selected) colors.primaryContainer else colors.surfaceContainer,
            contentColor = if (selected) colors.onPrimaryContainer else colors.onSurface,
        ),
        shape = shape,
    ) {
        Row(
            Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            RuleTypeBadge(rule.type, selected)
            RuleRowText(rule, sims, overridden, Modifier.weight(1f))
            Box(Modifier.size(12.dp))
        }
    }
}

/** The type is said once, by this icon (BR-25); screen readers hear it as the icon's description. */
@Composable
private fun RuleTypeBadge(type: RuleType, selected: Boolean) {
    val colors = MaterialTheme.colorScheme
    Box(
        Modifier.size(40.dp).clip(CircleShape).background(if (selected) colors.primary else colors.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (selected) Symbols.Check else ruleTypeIcon(type),
            contentDescription = ruleTypeLabel(type),
            tint = if (selected) colors.onPrimary else colors.onPrimaryContainer,
            modifier = Modifier.size(20.dp),
        )
    }
}

/** Value, then the SIM when it is not both, the note, and the "an allow rule wins" mark. */
@Composable
private fun RuleRowText(rule: SpamRule, sims: List<SimInfo>, overridden: Boolean, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            if (rule.type == RuleType.NUMBER) isolateNumbers(displayNumber(rule.pattern)) else rule.pattern,
            style = MaterialTheme.typography.titleMedium,
            fontFamily = if (rule.type == RuleType.REGEX) FontFamily.Monospace else null,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        ruleSimLabel(rule, sims)?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // The type in words, then the note when there is one ("Word in message · “gym”").
        val typeLabel = ruleRowTypeLabel(rule.type)
        Text(
            ruleNoteText(rule.note)?.let { stringResource(R.string.ux8d_row_type_note, typeLabel, it) } ?: typeLabel,
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (overridden) {
            InfoChip(
                stringResource(R.string.w3b_overridden_chip),
                container = colors.secondaryContainer,
                content = colors.onSecondaryContainer,
                icon = Symbols.Warning,
            )
        }
    }
}

@Composable
private fun typeHelp(kind: ListKind, type: RuleType): String = stringResource(
    when (type) {
        RuleType.NUMBER -> R.string.rules2_type_number_help
        RuleType.PREFIX -> R.string.rules2_type_prefix_help
        RuleType.SENDER_ID -> R.string.rules2_type_sender_id_help
        RuleType.KEYWORD -> if (kind == ListKind.ALLOW) R.string.spam_type_keyword_allow_help else R.string.spam_type_keyword_help
        RuleType.REGEX -> R.string.rules2_type_regex_help
    },
)

@Composable
private fun typeHint(type: RuleType): String = stringResource(
    when (type) {
        RuleType.NUMBER -> R.string.rules2_type_number_hint
        RuleType.PREFIX -> R.string.rules2_type_prefix_hint
        RuleType.SENDER_ID -> R.string.spam_type_sender_id_hint
        RuleType.KEYWORD -> R.string.spam_type_keyword_hint
        RuleType.REGEX -> R.string.spam_type_regex_hint
    },
)

/** The message for a failed validation; shared with the forward editor. */
@Composable
internal fun errorText(error: RuleValidation.Invalid): String = when (error.error) {
    RuleError.EMPTY -> stringResource(R.string.spam_err_empty)
    RuleError.NUMBER -> stringResource(R.string.spam_err_number)
    RuleError.PREFIX -> stringResource(R.string.spam_err_prefix)
    RuleError.SENDER_ID -> error.detail?.let { stringResource(R.string.rules2_err_sender_id_char, it) }
        ?: stringResource(R.string.rules2_err_sender_id)
    RuleError.KEYWORD_SHORT -> stringResource(R.string.spam_err_keyword_short)
    RuleError.TOO_LONG -> stringResource(R.string.spam_err_too_long)
    RuleError.MULTILINE -> stringResource(R.string.spam_err_multiline)
    RuleError.DUPLICATE -> stringResource(R.string.spam_err_duplicate)
    RuleError.ALLOW_KEYWORD_SHORT -> stringResource(R.string.spam_err_allow_keyword_short)
    else -> regexErrorText(error)
}

/** The pattern (regex) errors of [errorText]. */
@Composable
private fun regexErrorText(error: RuleValidation.Invalid): String = when (error.error) {
    RuleError.REGEX_INVALID -> error.detail?.let { stringResource(R.string.spam_err_regex_invalid, it) }
        ?: stringResource(R.string.spam_err_regex_invalid_plain)
    RuleError.REGEX_MATCHES_EMPTY -> stringResource(R.string.spam_err_regex_empty_match)
    RuleError.REGEX_UNSAFE -> stringResource(R.string.spam_err_regex_unsafe)
    RuleError.REGEX_MATCHES_ALL -> stringResource(R.string.spam_err_regex_matches_all)
    else -> stringResource(R.string.spam_err_regex_invalid_plain)
}

@Composable
private fun warningText(warning: RuleWarning): String = stringResource(
    when (warning) {
        RuleWarning.ALLOW_SKIPS_BLOCKS -> R.string.spam_warn_allow_skips_blocks
        RuleWarning.COMMON_WORD -> R.string.spam_warn_common_word
        RuleWarning.BROAD -> R.string.spam_warn_broad
        RuleWarning.ALLOWED_CONFLICT -> R.string.spam_warn_allowed_conflict
    },
)

/** Warnings under the value field: they do not block saving, but saving asks to confirm them. */
@Composable
private fun RuleWarnings(warnings: List<RuleWarning>) {
    val colors = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        colors = CardDefaults.cardColors(containerColor = colors.errorContainer, contentColor = colors.onErrorContainer),
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Symbols.Warning, contentDescription = null, modifier = Modifier.size(20.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                warnings.forEach { Text(warningText(it), style = MaterialTheme.typography.bodyMedium) }
            }
        }
    }
}

/** Asks before saving a rule with warnings. A block of an allowed value offers to remove the allow rule. */
@Composable
private fun ConfirmRuleDialog(form: RuleForm, actions: RulesActions) {
    val conflict = RuleWarning.ALLOWED_CONFLICT in form.warnings
    val message = form.warnings.map { warningText(it) }.joinToString("\n\n")
    ConfirmDialog(
        title = stringResource(R.string.spam_confirm_title),
        message = message,
        confirmLabel = stringResource(if (conflict) R.string.spam_confirm_remove_allowed else R.string.spam_confirm_save),
        onConfirm = actions.onConfirmSubmit,
        onDismiss = actions.onCancelConfirm,
        dismissLabel = stringResource(R.string.spam_cancel),
        destructive = form.kind == ListKind.ALLOW,
        content = if (conflict) {
            {
                Text(
                    stringResource(R.string.spam_confirm_remove_allowed_detail),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        } else {
            null
        },
    )
}

/** How long a rule's Undo stays on screen: [SnackbarDuration.Long], about the Blocked screen's window. */
internal const val RULES_UNDO_MS = 10_000L

/**
 * Shows an Undo snackbar with [SnackbarDuration.Long] (10 s, longer when the system asks for more time for
 * accessibility), like the Blocked screen's Undo. A newer snackbar still replaces it at once.
 */
internal suspend fun SnackbarHostState.showUndo(message: String, actionLabel: String): SnackbarResult =
    showSnackbar(message, actionLabel = actionLabel, duration = SnackbarDuration.Long)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AddRuleSheet(form: RuleForm, sims: List<SimInfo>, actions: RulesActions) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var pickingContacts by rememberSaveable { mutableStateOf(false) }
    val error = form.visibleError
    // The sheet stops below the status bar, and Cancel / Save stay in a bar under the scrolling form.
    val maxHeight = LocalConfiguration.current.screenHeightDp.dp * SHEET_HEIGHT_FRACTION
    ModalBottomSheet(onDismissRequest = actions.onDismissForm, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().heightIn(max = maxHeight).navigationBarsPadding().imePadding()) {
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    stringResource(
                        when {
                            form.isEdit && form.kind == ListKind.ALLOW -> R.string.rules_sheet_title_edit_allow
                            form.isEdit -> R.string.rules_sheet_title_edit_block
                            form.kind == ListKind.ALLOW -> R.string.ux8d_sheet_title_allow
                            else -> R.string.ux8d_sheet_title_block
                        },
                    ),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.semantics { heading() },
                )

                RuleMatchPicker(form.type, actions.onType)
                RuleKindChoice(form.kind, actions.onKind)
                if (form.type == RuleType.REGEX) PatternBlockChips(form.pattern, actions.onPattern)

                val clearCd = stringResource(R.string.spam_sheet_clear_value)
                OutlinedTextField(
                    value = form.pattern,
                    onValueChange = actions.onPattern,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(valueLabel(form.type))) },
                    placeholder = { Text(typeHint(form.type)) },
                    singleLine = true,
                    isError = error != null,
                    supportingText = { Text(if (error != null) errorText(error) else typeHelp(form.kind, form.type)) },
                    textStyle = MaterialTheme.typography.bodyLarge.let {
                        if (form.type == RuleType.REGEX) it.copy(fontFamily = FontFamily.Monospace) else it
                    },
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                        keyboardType = if (form.type.isSender && form.type != RuleType.SENDER_ID) {
                            KeyboardType.Phone
                        } else {
                            KeyboardType.Text
                        },
                    ),
                    trailingIcon = {
                        if (form.pattern.isNotEmpty()) {
                            IconButton(onClick = { actions.onPattern("") }) {
                                Icon(Symbols.Close, contentDescription = clearCd)
                            }
                        }
                    },
                )

                // Several contacts at once: one rule per number, in the list chosen above.
                if (form.type == RuleType.NUMBER && !form.isEdit) {
                    ChooseFromContactsButton(onChoose = { pickingContacts = true })
                }

                if (form.type == RuleType.KEYWORD) WholeWordSwitch(form.pattern, actions.onPattern)

                // Try a keyword or pattern on a sample message before it is saved.
                RulePatternTest(form.kind, form.type, form.pattern, valid = form.validation is RuleValidation.Valid)

                RuleSheetExtras(form, sims, actions)

                if (error == null && form.warnings.isNotEmpty()) RuleWarnings(form.warnings)

                if (form.saveFailed) {
                    Text(
                        stringResource(R.string.spam_sheet_save_failed),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
                    )
                }

                // Deleting lives here, away from the save button, and is undoable from the snackbar.
                if (form.isEdit) {
                    TextButton(onClick = actions.onDeleteEditing, modifier = Modifier.heightIn(min = 48.dp)) {
                        Icon(
                            Symbols.Delete,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            stringResource(R.string.rules2_sheet_delete),
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
            }
            RuleSheetBar(
                saveLabel = stringResource(if (form.isEdit) R.string.rules_sheet_save else R.string.spam_sheet_add),
                enabled = form.canSubmit,
                hint = when {
                    form.saving -> null
                    form.pattern.isBlank() -> stringResource(R.string.rules2_sheet_hint_empty)
                    error != null -> stringResource(R.string.rules2_sheet_hint_fix)
                    else -> null
                },
                onCancel = actions.onDismissForm,
                onSave = actions.onSubmit,
            )
        }
    }
    if (pickingContacts) {
        ContactPickerSheet(
            multi = true,
            onPick = { picks ->
                pickingContacts = false
                actions.onPickContacts(picks)
            },
            onDismiss = { pickingContacts = false },
        )
    }
    if (form.confirming) ConfirmRuleDialog(form, actions)
}

/** Sender types first, then the ones about the message text: the same two groups as the forward editor (BR-44). */
private val RuleType.isSender: Boolean
    get() = this == RuleType.NUMBER || this == RuleType.PREFIX || this == RuleType.SENDER_ID

/** The value field's label, in the forward editor's words ("Sender", "Text to look for"). */
private fun valueLabel(type: RuleType): Int = when (type) {
    RuleType.NUMBER -> R.string.e_value_number
    RuleType.PREFIX -> R.string.e_value_prefix
    RuleType.SENDER_ID -> R.string.e_value_sender
    RuleType.KEYWORD -> R.string.e_value_keyword
    RuleType.REGEX -> R.string.e_value_pattern
}

/** Share of the screen height a rule sheet may take, so the status bar stays visible above it. */
internal const val SHEET_HEIGHT_FRACTION = 0.92f

/**
 * The one-time move of the old lists (SU-33). Words always move (that is what retires the old list). Numbers move only
 * when ticked, and leave the system list only when that second box is ticked too, because that list also blocks calls.
 * The second box is indented and says why it is off until the first is ticked; the button counts what it will move and
 * stays off while nothing is selected.
 */
@Composable
fun MigrationDialogContent(dialog: MigrationDialog, actions: RulesActions) {
    val resources = LocalResources.current
    val plan = dialog.plan
    val movingCount = plan.keywordRules.size + if (dialog.importNumbers) plan.movableNumbers.size else 0
    val canMove = movingCount > 0 && !dialog.running
    // Only numbers to move (the usual case from the Android block list): the title says how many (SE-34).
    val numbersOnly = !plan.hasKeywordWork && plan.hasNumberWork
    AlertDialog(
        onDismissRequest = actions.onCancelMigration,
        title = {
            Text(
                if (numbersOnly) {
                    val count = plan.movableNumbers.size
                    resources.getQuantityString(R.plurals.e_migrate_title_numbers, count, count)
                } else {
                    stringResource(R.string.rules2_migrate_title)
                },
            )
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MigrationKeywordPart(plan)
                if (plan.hasNumberWork) MigrationNumberPart(dialog, actions)
            }
        },
        confirmButton = {
            if (plan.isEmpty) {
                TextButton(onClick = actions.onCancelMigration) { Text(stringResource(R.string.rules_migrate_confirm)) }
            } else {
                TextButton(onClick = actions.onConfirmMigration, enabled = canMove) {
                    val label = if (movingCount > 0) {
                        resources.getQuantityString(R.plurals.rules2_migrate_confirm_count, movingCount, movingCount)
                    } else {
                        stringResource(R.string.rules2_migrate_nothing_selected)
                    }
                    Text(label)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = actions.onCancelMigration) { Text(stringResource(R.string.spam_cancel)) }
        },
    )
}

/** The words part of the move dialog, or the "nothing to move" line. */
@Composable
private fun MigrationKeywordPart(plan: LegacyMigrationPlan) {
    val resources = LocalResources.current
    val bodyStyle = MaterialTheme.typography.bodyMedium
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
            plan.isEmpty -> Text(stringResource(R.string.rules_migrate_nothing), style = bodyStyle)
            plan.hasKeywordWork -> {
                val count = plan.keywordRules.size
                Text(resources.getQuantityString(R.plurals.rules_migrate_keywords, count, count), style = bodyStyle)
                Text(stringResource(R.string.rules2_migrate_point_blocked), style = bodyStyle, color = muted)
            }
        }
        if (plan.keptKeywords.isNotEmpty()) {
            val kept = plan.keptKeywords.size
            Text(
                resources.getQuantityString(R.plurals.rules_migrate_keywords_kept, kept, kept),
                style = bodyStyle,
                color = muted,
            )
        }
    }
}

/**
 * The numbers part: two short points instead of a paragraph, the two boxes, and the warning about calls right under
 * the box it belongs to (SE-34).
 */
@Composable
private fun MigrationNumberPart(dialog: MigrationDialog, actions: RulesActions) {
    val resources = LocalResources.current
    val plan = dialog.plan
    val bodyStyle = MaterialTheme.typography.bodyMedium
    val count = plan.movableNumbers.size
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            BulletPoint(stringResource(R.string.e_migrate_point_blocked), style = bodyStyle)
            BulletPoint(stringResource(R.string.e_migrate_point_android), style = bodyStyle)
        }
        CheckboxRow(
            label = resources.getQuantityString(R.plurals.rules_migrate_numbers, count, count),
            checked = dialog.importNumbers,
            onCheckedChange = actions.onMigrateNumbers,
            enabled = !dialog.running,
            labelStyle = bodyStyle,
        )
        CheckboxRow(
            label = stringResource(R.string.rules_migrate_remove),
            checked = dialog.removeFromSystem,
            onCheckedChange = actions.onRemoveFromSystem,
            enabled = dialog.importNumbers && !dialog.running,
            modifier = Modifier.padding(start = 24.dp),
            labelStyle = bodyStyle,
            // The warning about calls sits under the box it is about, and is there before the box can be ticked.
            supporting = if (dialog.importNumbers) {
                stringResource(R.string.e_migrate_calls_note)
            } else {
                stringResource(R.string.rules2_migrate_remove_needs_first) + " " +
                    stringResource(R.string.e_migrate_calls_note)
            },
        )
        if (plan.keptNumbers.isNotEmpty()) {
            val kept = plan.keptNumbers.size
            Text(
                resources.getQuantityString(R.plurals.rules_migrate_numbers_kept, kept, kept),
                style = bodyStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * One point of a bulleted list with a hanging indent: the bullet sits in its own fixed-width column, top-aligned and in
 * the same style as the text, so it lines up with the first line and wrapped lines start under the text, not under the
 * bullet (owner, review 2). Row follows the layout direction, so in Persian the bullet is on the right. A bullet
 * already written into the string is dropped so it is not drawn twice.
 */
@Composable
private fun BulletPoint(text: String, style: TextStyle, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text("•", style = style, modifier = Modifier.width(16.dp))
        Text(text.trimStart('•', ' ', ' '), style = style, modifier = Modifier.weight(1f))
    }
}

// region Previews

private val previewSims = listOf(SimInfo(1, 1, "Carrier A"), SimInfo(2, 2, "Carrier B"))

private fun previewState(tab: ListKind = ListKind.BLOCK, empty: Boolean = false, form: RuleForm? = null): RulesUiState {
    val rules = if (empty) emptyList() else SampleData.rules()
    return RulesUiState(
        loading = false,
        tab = tab,
        allowed = rules.filter { it.kind == ListKind.ALLOW },
        blocked = rules.filter { it.kind == ListKind.BLOCK },
        sims = previewSims,
        form = form,
    )
}

@Composable
private fun PreviewHost(state: RulesUiState) = UMessageTheme {
    RulesScreen(state, remember { SnackbarHostState() }, RulesActions())
}

@Preview(name = "Rules - blocked", showBackground = true, widthDp = 360, heightDp = 740)
@Composable
private fun RulesBlockedPreview() = PreviewHost(previewState())

@Preview(name = "Rules - allowed", showBackground = true, widthDp = 360, heightDp = 740)
@Composable
private fun RulesAllowedPreview() = PreviewHost(previewState(tab = ListKind.ALLOW))

@Preview(name = "Rules - empty", showBackground = true, widthDp = 360, heightDp = 740)
@Composable
private fun RulesEmptyPreview() = PreviewHost(previewState(empty = true))

@Preview(name = "Rules - add sheet error", showBackground = true, widthDp = 360, heightDp = 740)
@Composable
private fun RulesSheetPreview() {
    val pattern = "win(\\s+"
    PreviewHost(
        previewState(
            form = RuleForm(
                kind = ListKind.BLOCK,
                type = RuleType.REGEX,
                pattern = pattern,
                validation = RuleValidator.validate(ListKind.BLOCK, RuleType.REGEX, pattern),
            ),
        ),
    )
}

@Preview(
    name = "Rules - dark", showBackground = true, widthDp = 360, heightDp = 740,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun RulesDarkPreview() = PreviewHost(previewState())

// endregion
