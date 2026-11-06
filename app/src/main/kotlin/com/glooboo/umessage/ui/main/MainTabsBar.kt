package com.glooboo.umessage.ui.main

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.glooboo.umessage.R
import com.glooboo.umessage.ui.components.ICON_CONTRAST
import com.glooboo.umessage.ui.components.localizeDigits
import com.glooboo.umessage.ui.components.localizedNumber
import com.glooboo.umessage.ui.components.readableOn
import com.glooboo.umessage.ui.components.uiLocale

/* The bottom navigation bar of the main screen, its badges and the effects that clear them. */

private const val MAX_BADGE = 99

/** What a tab shows on its icon: a count, a dot (red when [error]) or nothing. */
data class TabIndicator(val count: Int = 0, val dot: Boolean = false, val error: Boolean = false) {
    val none: Boolean get() = count == 0 && !dot
}

/**
 * The badge on [tab]'s icon. The tab being viewed never shows one: what it would point at is already on screen, and
 * the Blocked count is marked seen while the tab is open. Blocked shows a count, Forwarded a red dot while a forward
 * failed unseen (P2-08) or a plain dot while forwarding is paused, Archived a dot after something was archived.
 */
fun tabIndicator(
    tab: MainTab,
    selected: MainTab,
    newBlocked: Int,
    forwardedFailed: Boolean,
    forwardedPaused: Boolean,
    archivedNew: Boolean,
): TabIndicator = when {
    tab == selected -> TabIndicator()
    tab == MainTab.Blocked && newBlocked > 0 -> TabIndicator(count = newBlocked)
    tab == MainTab.Forwarded && forwardedFailed -> TabIndicator(dot = true, error = true)
    tab == MainTab.Forwarded && forwardedPaused -> TabIndicator(dot = true)
    tab == MainTab.Archived && archivedNew -> TabIndicator(dot = true)
    else -> TabIndicator()
}

@Composable
internal fun MainNavigationBar(
    tabs: List<MainTab>,
    selected: MainTab,
    newBlocked: Int,
    forwardedFailed: Boolean,
    forwardedPaused: Boolean,
    archivedNew: Boolean,
    enabled: Boolean,
    onSelect: (MainTab) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val bar = NavigationBarDefaults.containerColor
    val indicator = colors.secondaryContainer
    // VS-02, VS-35: the label sits on the bar, not on the pill, so it takes the bar's on-colour. Only the icon sits on
    // the pill. Raised-contrast Wallpaper schemes make the pill mid-tone with a black or white on-colour, and that
    // on-colour vanished on the bar; every pairing is checked against what it is actually drawn on.
    val itemColors = NavigationBarItemDefaults.colors(
        selectedIconColor = readableOn(indicator, colors.onSecondaryContainer, ICON_CONTRAST),
        selectedTextColor = readableOn(bar, colors.onSurface),
        indicatorColor = indicator,
        unselectedIconColor = readableOn(bar, colors.onSurfaceVariant, ICON_CONTRAST),
        unselectedTextColor = readableOn(bar, colors.onSurfaceVariant),
    )
    NavigationBar(containerColor = bar) {
        tabs.forEach { tab ->
            val label = stringResource(tab.labelRes)
            val shown = tabIndicator(tab, selected, newBlocked, forwardedFailed, forwardedPaused, archivedNew)
            val description = tabDescription(tab, shown, label)
            val isSelected = tab == selected
            NavigationBarItem(
                selected = isSelected,
                onClick = { onSelect(tab) },
                enabled = enabled,
                icon = {
                    BadgedBox(
                        badge = { TabBadge(shown) },
                    ) {
                        Icon(tab.icon, contentDescription = null)
                    }
                },
                label = {
                    Text(
                        label,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontWeight = if (isSelected) FontWeight.SemiBold else null,
                    )
                },
                // VS-02: the active tab is told apart by the pill and the weight of its label.
                colors = itemColors,
                modifier = Modifier.semantics { contentDescription = description },
            )
        }
    }
}

/**
 * A warning (such as "not the default SMS app") sits above the tabs, or alone above the system bar while the search
 * hides them.
 */
@Composable
internal fun MainBottomBar(searchActive: Boolean, banner: (@Composable () -> Unit)?, bar: @Composable () -> Unit) {
    Column {
        if (searchActive) {
            banner?.let { Box(Modifier.navigationBarsPadding().imePadding()) { it() } }
        } else {
            banner?.invoke()
            bar()
        }
    }
}

/** A dot on Archived when something was archived while another tab was open, until the tab is opened. */
@Composable
internal fun rememberArchivedDot(archivedCount: Int, loading: Boolean, tab: MainTab): Boolean {
    var archivedDot by rememberSaveable { mutableStateOf(false) }
    var previousArchived by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(archivedCount, loading) {
        if (!loading) {
            val previous = previousArchived
            if (previous != null && archivedCount > previous && tab != MainTab.Archived) archivedDot = true
            previousArchived = archivedCount
        }
    }
    LaunchedEffect(tab) { if (tab == MainTab.Archived) archivedDot = false }
    return archivedDot
}

/**
 * While the Blocked tab is open: seen when it opens, at once when a message is held while it is open (no "new" badge
 * on the tab being viewed), and again when it closes.
 */
@Composable
internal fun BlockedSeenEffects(newBlocked: Int, markSeen: () -> Unit) {
    val mark by rememberUpdatedState(markSeen)
    DisposableEffect(Unit) {
        mark()
        onDispose { mark() }
    }
    LaunchedEffect(newBlocked) { if (newBlocked > 0) mark() }
}

@Composable
private fun tabDescription(tab: MainTab, shown: TabIndicator, label: String): String = when {
    shown.count > 0 -> pluralStringResource(R.plurals.main_tab_blocked_new, shown.count, shown.count)
    shown.dot && shown.error -> stringResource(R.string.nav_tab_forwarded_failed)
    shown.dot && tab == MainTab.Forwarded -> stringResource(R.string.nav_tab_forwarded_paused)
    shown.dot && tab == MainTab.Archived -> stringResource(R.string.nav_tab_archived_new)
    else -> label
}

@Composable
private fun TabBadge(shown: TabIndicator) {
    val colors = MaterialTheme.colorScheme
    if (shown.count > 0) {
        val locale = uiLocale()
        val count = shown.count
        Badge { Text(if (count > MAX_BADGE) localizeDigits("$MAX_BADGE+", locale) else localizedNumber(count, locale)) }
    } else if (shown.dot) {
        Badge(containerColor = if (shown.error) colors.error else colors.primary)
    }
}
