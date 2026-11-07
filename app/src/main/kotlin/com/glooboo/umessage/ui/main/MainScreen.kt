package com.glooboo.umessage.ui.main

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel as composeViewModel
import com.glooboo.umessage.R
import com.glooboo.umessage.helpers.W3cPrefs
import com.glooboo.umessage.models.SearchResult
import com.glooboo.umessage.ui.components.rememberReduceMotion
import com.glooboo.umessage.ui.conversations.ArchivedRoute
import com.glooboo.umessage.ui.conversations.ArchivedViewModel
import com.glooboo.umessage.ui.conversations.ConversationRow
import com.glooboo.umessage.ui.conversations.ConversationsCallbacks
import com.glooboo.umessage.ui.conversations.ConversationsRoute
import com.glooboo.umessage.ui.conversations.ConversationsSelectionBar
import com.glooboo.umessage.ui.conversations.ConversationsViewModel
import com.glooboo.umessage.ui.conversations.HiddenChatsLine
import com.glooboo.umessage.spam.ForwardingAvailability
import com.glooboo.umessage.ui.forwarded.ForwardedRoute
import com.glooboo.umessage.ui.icons.Symbols
import com.glooboo.umessage.ui.labels.rememberInboxLabels
import com.glooboo.umessage.ui.lock.BlockedLockGate
import com.glooboo.umessage.ui.scheduled.rememberScheduledCount
import com.glooboo.umessage.ui.spam.BlockedRoute
import com.glooboo.umessage.ui.spam.ContactsOffBanner

/** CL-13, N11: the inbox filters; the search page's tiles turn one on ([SearchFilterTile]). */
enum class InboxFilter { All, Unread, Codes, Transactions, Offers, Unknown, Starred, Images, Links, Label, Sim }

/** The top-level destinations in the bottom navigation bar; which of them show is decided by [MainTabPolicy]. */
enum class MainTab(val labelRes: Int, val icon: ImageVector) {
    Messages(R.string.main_tab_messages, Symbols.ChatBubble),
    Blocked(R.string.main_tab_blocked, Symbols.Shield),
    Forwarded(R.string.main_tab_forwarded, Symbols.Forward),
    Archived(R.string.main_tab_archived, Symbols.Archive),
}

/** Screens the main screen opens on top of itself. */
class MainNavigation(
    val onSettings: () -> Unit = {},
    val onSpamRules: () -> Unit = {},
    /** "Manage rules" in the Forwarded tab: the rules screen on its Forward tab. */
    val onManageForwardRules: () -> Unit = onSpamRules,
    val onSpamSettings: () -> Unit = {},
    val onOpenArchived: (ConversationRow) -> Unit = {},
    val onOpenThread: (threadId: Long, title: String) -> Unit = { _, _ -> },
    /** The list of scheduled messages (I25). */
    val onScheduled: () -> Unit = {},
    /** The recycle bin's conversations (U04). */
    val onRecycleBin: () -> Unit = {},
)

/**
 * The app's home: an M3 navigation bar with Messages, Blocked, Forwarded and Archived. Forwarded shows only once a
 * forward rule exists and the user asked for it, Archived whenever archiving is available, even with an empty
 * archive (see [MainTabPolicy]).
 * Each tab keeps its own state (scroll position, selection) while another tab is shown. Back on any other tab returns
 * to Messages first, as M3 navigation asks; the bar hides while the search is open and is dimmed while rows are
 * selected.
 */
// onOpenTabHandled is the activity's existing callback name; the Messages tab hands the conversations view model on to
// its list, which collects it itself.
@Suppress("ParameterNaming", "ViewModelForwarding")
@Composable
fun MainScreen(
    viewModel: ConversationsViewModel,
    callbacks: ConversationsCallbacks,
    search: MainSearchState,
    navigation: MainNavigation,
    archiveAvailable: Boolean,
    onQueryChange: (String) -> Unit,
    onOpenResult: (SearchResult) -> Unit,
    banner: (@Composable () -> Unit)? = null,
    /** A tab to open (a [com.glooboo.umessage.helpers.MainTabLinks] value), for example from Settings. */
    openTab: String? = null,
    onOpenTabHandled: () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val tabsViewModel: MainTabsViewModel = composeViewModel()
    val tabsState by tabsViewModel.state.collectAsStateWithLifecycle()
    // Shared with the Archived tab (same view model): its list is also the archived count for the tab's new-chat dot.
    val archivedViewModel: ArchivedViewModel = composeViewModel()
    val archived by archivedViewModel.state.collectAsStateWithLifecycle()
    LifecycleResumeEffect(archivedViewModel) {
        archivedViewModel.load()
        onPauseOrDispose { }
    }

    var selectedTab by rememberSaveable { mutableStateOf(MainTab.Messages) }
    // Forwarded opened on purpose although switched off (Settings > Forwarding history): it stays until left.
    var forwardedOpened by rememberSaveable { mutableStateOf(false) }
    val openTabHandled by rememberUpdatedState(onOpenTabHandled)
    val forwardingAvailable = ForwardingAvailability.isAvailable(LocalContext.current)
    LaunchedEffect(openTab) {
        // A link to the Forwarded tab (an old notification, an intent) goes nowhere in the Google Play build.
        MainTabPolicy.fromLink(openTab)?.takeIf { forwardingAvailable || it != MainTab.Forwarded }?.let { target ->
            selectedTab = target
            forwardedOpened = target == MainTab.Forwarded
            openTabHandled()
        }
    }
    val inputs =
        MainTabInputs(
            archiveAvailable, archived.rows.size, tabsState.forwardRuleCount, tabsState.showForwarded,
            tabsState.failedForwards,
        )
    // Until the rules were read, a saved Forwarded selection must not be thrown back to Messages.
    val tabs = MainTabPolicy.visibleTabs(inputs, selectedTab, forwardedOpened || !tabsState.loaded)
    val tab = MainTabPolicy.effectiveTab(tabs, selectedTab)
    // A tab that is gone (last rule deleted, tab switched off) must not come back by itself later.
    LaunchedEffect(tab, selectedTab) { if (tab != selectedTab) selectedTab = tab }
    LaunchedEffect(tab) { if (tab != MainTab.Forwarded) forwardedOpened = false }
    // ID-10: looking at the Blocked tab ticks a step of the first-day checklist.
    val appContext = LocalContext.current
    LaunchedEffect(tab) { if (tab == MainTab.Blocked) W3cPrefs.get(appContext).blockedTabVisited = true }

    val archivedDot = rememberArchivedDot(archived.rows.size, archived.loading, tab)
    val tabStates = rememberSaveableStateHolder()

    BackHandler(enabled = tab != MainTab.Messages) { selectedTab = MainTab.Messages }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            MainBottomBar(searchActive = search.active, banner = banner) {
                MainNavigationBar(
                    tabs = tabs,
                    selected = tab,
                    newBlocked = state.newBlockedCount,
                    forwardedFailed = tabsState.failedUnseen > 0,
                    forwardedPaused = tabsState.forwardPaused,
                    archivedNew = archivedDot,
                    // Rows are selected on Messages: the other tabs would only drop the selection (CL-22).
                    enabled = !(state.selecting && tab == MainTab.Messages),
                ) { next ->
                    if (next != tab) {
                        callbacks.onClearSelection()
                        selectedTab = next
                    }
                }
            }
        },
    ) { padding ->
        // The bar already covers the system navigation bar, so the tabs must not add that inset again.
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            tabStates.SaveableStateProvider(tab.name) {
                when (tab) {
                    MainTab.Messages -> MessagesTab(
                        viewModel, callbacks, search, navigation, onQueryChange, onOpenResult,
                        forwardRuleCount = tabsState.forwardRuleCount,
                        menuInputs = InboxMenuInputs(
                            archiveAvailable, archived.rows.size, rememberRecycleBinOn(), rememberScheduledCount(),
                            archivedTabShown = tabs.contains(MainTab.Archived),
                        ),
                        onOpenBlockedTab = { callbacks.onClearSelection(); selectedTab = MainTab.Blocked },
                        onOpenArchivedTab = { callbacks.onClearSelection(); selectedTab = MainTab.Archived },
                    )
                    MainTab.Blocked -> BlockedLockGate {
                        BlockedSeenEffects(state.newBlockedCount, viewModel::markBlockedSeen)
                        BlockedRoute(
                            onBack = null,
                            onOpenRules = navigation.onSpamRules,
                            onOpenSettings = navigation.onSpamSettings,
                        )
                    }
                    MainTab.Forwarded -> ForwardedRoute(
                        onManageRules = navigation.onManageForwardRules,
                        onOpenThread = navigation.onOpenThread,
                    )
                    MainTab.Archived -> ArchivedRoute(
                        onOpen = navigation.onOpenArchived,
                        onBackToMessages = { selectedTab = MainTab.Messages },
                    )
                }
            }
        }
    }
}

/** The chip that says which inbox filter is on, sliding in under the top bar (or appearing with reduce motion). */
@Composable
private fun ActiveFilterBar(
    visible: Boolean,
    filter: InboxFilter,
    reduceMotion: Boolean,
    onClear: () -> Unit,
    labelName: String? = null,
) {
    AnimatedVisibility(
        visible = visible,
        enter = if (reduceMotion) EnterTransition.None else expandVertically() + fadeIn(),
        exit = if (reduceMotion) ExitTransition.None else shrinkVertically() + fadeOut(),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            ActiveFilterChip(filter, onClear, labelName = labelName)
        }
    }
}

/** Selecting rows closes the search, and "select all" must not leave a filter on that hides some selected rows. */
@Composable
private fun SelectionSearchEffects(selecting: Boolean, allSelected: Boolean, search: MainSearchState) {
    LaunchedEffect(selecting, allSelected) {
        if (selecting && allSelected) search.filter = InboxFilter.All
    }
    LaunchedEffect(selecting) {
        if (selecting && search.active) search.close()
    }
}

private fun openInboxMenuItem(
    item: InboxMenuItem,
    search: MainSearchState,
    navigation: MainNavigation,
    onOpenArchivedTab: () -> Unit,
) {
    when (item) {
        InboxMenuItem.Archived -> onOpenArchivedTab()
        InboxMenuItem.RecycleBin -> navigation.onRecycleBin()
        InboxMenuItem.Scheduled -> navigation.onScheduled()
        InboxMenuItem.Starred -> search.filter = InboxFilter.Starred
        InboxMenuItem.Settings -> navigation.onSettings()
    }
}

/**
 * The Messages tab: the top bar (mark, name, search, menu) on top, the conversation list below, and the
 * selection bar replacing the top bar while rows are selected. The search icon opens the full-screen search page over
 * the list; the list stays composed underneath so its scroll position and snackbars survive a search.
 */
// The tab hands the conversations view model on to its list, which collects it itself.
@Suppress("ViewModelForwarding")
@Composable
private fun MessagesTab(
    viewModel: ConversationsViewModel,
    callbacks: ConversationsCallbacks,
    search: MainSearchState,
    navigation: MainNavigation,
    onQueryChange: (String) -> Unit,
    onOpenResult: (SearchResult) -> Unit,
    forwardRuleCount: Int = 0,
    menuInputs: InboxMenuInputs = InboxMenuInputs(false, 0, false, 0),
    onOpenBlockedTab: () -> Unit = {},
    onOpenArchivedTab: () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val selecting = state.selecting
    val simTiles = rememberSimTiles()
    // CL-13, N11: All, Unread, Codes, Transactions or Offers, picked with a tile on the search page. Needs no new
    // data, so it filters the rows already loaded.
    val filter = search.filter
    // I28: Unknown, Starred, Images and Links look their conversations up, and list messages for the last three.
    val labels = rememberInboxLabels(search, state.rows, state.selected)
    // A SIM tile looks its conversations up by the SIM; the others come with the labels' lookup.
    val shortcut = rememberSimThreads(search) ?: labels.shortcut
    val simName = simTiles.firstOrNull { it.subscriptionId == search.simId }?.label
        .takeIf { filter == InboxFilter.Sim }
    SelectionSearchEffects(selecting, state.selection.allSelected, search)
    // Leaving search parks focus on a hidden spot instead of clearing it: cleared focus is handed back to the first
    // focusable item, which took 3-4 Back presses to leave.
    val keyboard = LocalSoftwareKeyboardController.current
    val focusSink = remember { FocusRequester() }
    val leaveSearch = {
        keyboard?.hide()
        focusSink.requestFocus()
        search.close()
    }
    BackHandler(enabled = search.active) {
        if (search.hasTileOn()) search.backToTiles(onQueryChange) else leaveSearch()
    }
    // Enabled only when there is something to undo, so the system predictive back-to-home plays otherwise.
    BackHandler(enabled = selecting) { callbacks.onClearSelection() }

    // B10: the card is the list's first item, so it scrolls away instead of holding half the screen.
    // U03: one at a time.
    val header: @Composable () -> Unit = {
        if (!selecting) {
            InboxCardSlot(
                newBlocked = state.newBlockedCount,
                forwardRuleCount = forwardRuleCount,
                onOpenBlockedTab = onOpenBlockedTab,
                onOpenForwarding = navigation.onManageForwardRules,
            )
        }
    }
    // B06: only on the plain list; a filter or the selection bar is not the place to open the hidden chats.
    val showHiddenLine = state.hiddenChats > 0 && filter == InboxFilter.All && !selecting
    val hiddenChatsLine: (@Composable () -> Unit)? = if (showHiddenLine) {
        { HiddenChatsLine(onChange = viewModel::refresh, unlocked = state.hiddenUnlocked) }
    } else {
        null
    }
    val reduceMotion = rememberReduceMotion()

    Box(Modifier.fillMaxSize()) {
        val topColor = rememberInboxTopColor()
        Column(Modifier.fillMaxSize().background(topColor)) {
            if (selecting) {
                ConversationsSelectionBar(
                    selection = state.selection,
                    callbacks = callbacks,
                    onLabels = labels.openPicker,
                )
            } else {
                MainTopBar(
                    onSearch = { search.active = true },
                    menu = InboxMenuPolicy.entries(menuInputs),
                    recycleBinCount = rememberRecycleBinCount(menuInputs.recycleBinOn, state.rows.size),
                    onMenu = { item -> openInboxMenuItem(item, search, navigation, onOpenArchivedTab) },
                )
                // A warning to act on stays in view.
                NotificationsOffBanner()
                ContactsOffBanner()
            }
            // One chip under the bar while a filter is on; it sits above the list, not in it, so it never scrolls off.
            ActiveFilterBar(
                visible = InboxFilterPolicy.chipVisible(filter, selecting),
                filter = filter,
                reduceMotion = reduceMotion,
                onClear = { search.filter = InboxFilter.All },
                labelName = labels.chipName ?: simName,
            )
            Box(Modifier.weight(1f).fillMaxWidth().inboxCard()) {
                ConversationsRoute(
                    viewModel, callbacks, filter = filter, filterThreads = shortcut.threads, header = header,
                    footer = hiddenChatsLine,
                )
            }
        }
        // Holds focus only; screen readers skip it.
        Box(Modifier.semantics { hideFromAccessibility() }.focusRequester(focusSink).focusable())
        AnimatedVisibility(
            visible = search.active,
            enter = if (reduceMotion) EnterTransition.None else fadeIn(),
            exit = if (reduceMotion) ExitTransition.None else fadeOut(),
        ) {
            MainSearchPage(
                search = search,
                rows = state.rows,
                shortcut = shortcut,
                onQueryChange = onQueryChange,
                onOpenResult = onOpenResult,
                onLeave = leaveSearch,
                simTiles = simTiles,
            )
        }
    }
}
