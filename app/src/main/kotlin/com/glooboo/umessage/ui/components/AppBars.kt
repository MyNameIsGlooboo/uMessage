package com.glooboo.umessage.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MediumTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import com.glooboo.umessage.R
import com.glooboo.umessage.ui.icons.Symbols

/**
 * UI-landscape: the system bars and the display cutout. The screens are edge to edge, so in landscape a notch or a
 * side navigation bar would otherwise sit on top of the rows and switches.
 */
@Composable
internal fun chromeInsets(): WindowInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout)

@Composable
private fun topBarInsets(): WindowInsets = chromeInsets().only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top)

/**
 * The scaffold hands its content the insets as padding, but screens only apply the top and bottom parts of it. This
 * applies the start and end parts here, once for every screen, and passes the rest on.
 */
@Composable
internal fun HorizontalInsetContent(padding: PaddingValues, content: @Composable (PaddingValues) -> Unit) {
    val direction = LocalLayoutDirection.current
    val sides = Modifier.padding(
        start = padding.calculateStartPadding(direction),
        end = padding.calculateEndPadding(direction),
    )
    Box(sides) {
        content(PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding()))
    }
}

/** Pinned scroll behavior that [UMessageScaffold] wires to its content, picked up by the bars inside its top bar. */
@OptIn(ExperimentalMaterial3Api::class)
private val LocalPageScrollBehavior = staticCompositionLocalOf<TopAppBarScrollBehavior?> { null }

/**
 * The one top bar of every secondary screen: titleLarge title, back arrow with a content description,
 * background-coloured so it reads as part of the page. Status bar insets are applied by the bar itself.
 * Inside [UMessageScaffold] it tints itself while the page scrolls under it (M3 pinned behavior).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UMessageTopBar(
    title: String,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) = TopBarImpl(title, onBack, modifier, LocalPageScrollBehavior.current, actions)

/** [UMessageTopBar] that tints itself while the page scrolls underneath (pass the same behavior to the scroll container). */
@ExperimentalMaterial3Api
@Composable
fun UMessageTopBar(
    title: String,
    onBack: (() -> Unit)?,
    scrollBehavior: TopAppBarScrollBehavior,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) = TopBarImpl(title, onBack, modifier, scrollBehavior, actions)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TopBarImpl(
    title: String,
    onBack: (() -> Unit)?,
    modifier: Modifier,
    scrollBehavior: TopAppBarScrollBehavior?,
    actions: @Composable RowScope.() -> Unit,
) {
    TopAppBar(
        modifier = modifier,
        windowInsets = topBarInsets(),
        title = {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { heading() },
            )
        },
        navigationIcon = {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(Symbols.ArrowBack, contentDescription = stringResource(R.string.screens_back))
                }
            }
        },
        actions = actions,
        scrollBehavior = scrollBehavior,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
            scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    )
}

/** Contextual bar shown while rows are selected: close arrow, "N selected" and the actions. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectionTopBar(
    count: Int,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) {
    TopAppBar(
        modifier = modifier,
        windowInsets = topBarInsets(),
        scrollBehavior = LocalPageScrollBehavior.current,
        title = {
            Text(
                pluralStringResource(R.plurals.screens_selected_count, count, count),
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
            )
        },
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(Symbols.Close, contentDescription = stringResource(R.string.screens_clear_selection))
            }
        },
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            navigationIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            actionIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    )
}

/**
 * Page frame for secondary screens. Content receives the insets padding (status bar already consumed by the
 * top bar); scrolling lists should apply it as `contentPadding` so they draw under the navigation bar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UMessageScaffold(
    topBar: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState? = null,
    floatingActionButton: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
        topBar = { CompositionLocalProvider(LocalPageScrollBehavior provides scrollBehavior) { topBar() } },
        snackbarHost = { if (snackbarHostState != null) SnackbarHost(snackbarHostState) },
        floatingActionButton = floatingActionButton,
        contentWindowInsets = chromeInsets(),
        content = { padding -> HorizontalInsetContent(padding, content) },
    )
}

/**
 * Pixel-style settings frame: a [MediumTopAppBar] (headlineSmall title that collapses to titleLarge) driven by
 * an exit-until-collapsed scroll behavior. The scaffold wires `nestedScroll`, so the scrolling [content]
 * (LazyColumn / verticalScroll) just needs to apply the given padding; pad the bottom so it draws under the nav bar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UMessageLargeScaffold(
    title: String,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState? = null,
    actions: @Composable RowScope.() -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
        topBar = { UMessageLargeTopBar(title, onBack, scrollBehavior, actions = actions) },
        snackbarHost = { if (snackbarHostState != null) SnackbarHost(snackbarHostState) },
        floatingActionButton = floatingActionButton,
        contentWindowInsets = chromeInsets(),
        content = { padding -> HorizontalInsetContent(padding, content) },
    )
}

/**
 * The collapsing title bar (medium height) used by [UMessageLargeScaffold]; [UMessageTopBar] stays the bar for other
 * screens.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UMessageLargeTopBar(
    title: String,
    onBack: (() -> Unit)?,
    scrollBehavior: TopAppBarScrollBehavior,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) {
    // Medium (112dp, collapses to 64dp) instead of Large (152dp): about 40dp more content on every settings screen
    // (VS-06).
    MediumTopAppBar(
        modifier = modifier,
        windowInsets = topBarInsets(),
        title = {
            Text(
                title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { heading() },
            )
        },
        navigationIcon = {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(Symbols.ArrowBack, contentDescription = stringResource(R.string.screens_back))
                }
            }
        },
        actions = actions,
        scrollBehavior = scrollBehavior,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
            scrolledContainerColor = MaterialTheme.colorScheme.background,
        ),
    )
}
