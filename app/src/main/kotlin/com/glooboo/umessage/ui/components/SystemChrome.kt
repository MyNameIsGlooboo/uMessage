package com.glooboo.umessage.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.material3.Button
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glooboo.umessage.R
import com.glooboo.umessage.ui.icons.Symbols
import com.glooboo.umessage.ui.theme.isDarkBackground

/*
 * Shared patterns from design review 2 (system findings): system-bar clearance, one FAB role, filter chip selected
 * state, a disabled-button reason line and the quiet delete icon. Existing components keep their signatures.
 */

/** Bottom space a scrolling list reserves so its last row can scroll clear of an extended FAB (VS-41). */
val FabListBottomPadding: Dp = 88.dp

/** The FAB's 56dp plus its 16dp margin: the part of [fabListBottomPadding] that does not grow with the text. */
private val FabFootprint: Dp = 72.dp

/** One line of body text under the FAB: the part of [fabListBottomPadding] that grows with the text size. */
private val FabTextClearance = 16.sp

/**
 * B12, VS-41: [FabListBottomPadding] that grows with the app and system text size, so at the Largest size the last
 * row's time and badges still scroll clear of the FAB. Equals [FabListBottomPadding] at the default size.
 */
@Composable
fun fabListBottomPadding(): Dp = FabFootprint + with(LocalDensity.current) { FabTextClearance.toDp() }

/**
 * Navigation bar height as [PaddingValues]: pass
 * `contentPadding = navBarContentPadding(extraBottom = FabListBottomPadding)`
 * to a LazyColumn that draws edge to edge, so the last row never sits under the 3-button or gesture bar (VS-03, VS-19).
 */
@Composable
fun navBarContentPadding(extraBottom: Dp = 0.dp, top: Dp = 0.dp): PaddingValues {
    val bars = WindowInsets.navigationBars.asPaddingValues()
    return PaddingValues(top = top, bottom = bars.calculateBottomPadding() + extraBottom)
}

/** For a non-lazy container (a Column with verticalScroll): pad the bottom by the navigation bar height. */
fun Modifier.navBarClearance(): Modifier = navigationBarsPadding()

/**
 * Solid strip as tall as the navigation bar, in the page background. Place it at the bottom of a screen whose content
 * would otherwise show through a transparent bar, so both themes read as one bar (VS-04, VS-36).
 */
@Composable
fun NavigationBarScrim(modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.background) {
    Spacer(modifier.fillMaxWidth().windowInsetsBottomHeight(WindowInsets.navigationBars).background(color))
}

/**
 * The one extended FAB: primaryContainer in every screen (VS-01), at least 56dp tall. Keep the 16dp end margin the
 * Scaffold already gives. Owner notes R2 (D): it now matches the Messages tab's New conversation FAB exactly, with the
 * M3 default extended-FAB shape and elevation and no outline (the owner disliked the 1dp edge B13 had added).
 *
 * The button carries [text] as its own accessible name, so it is announced (and is not a nameless node in a UI dump)
 * whether or not the label is showing; [expanded] false folds it to the icon.
 */
@Composable
fun UMessageFab(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    expanded: Boolean = true,
) {
    val colors = MaterialTheme.colorScheme
    ExtendedFloatingActionButton(
        text = { Text(text) },
        icon = { Icon(icon, contentDescription = null) },
        onClick = onClick,
        modifier = modifier
            .heightIn(min = 56.dp)
            // Kept beside the label's own text (not cleared), so tests and TalkBack still find the visible label.
            .semantics { contentDescription = text },
        expanded = expanded,
        containerColor = colors.primaryContainer,
        contentColor = colors.onPrimaryContainer,
    )
}

/** VS-44: choice chips are a little taller than the M3 32dp so the label and check breathe. */
private val ChipHeight = 36.dp

/**
 * Choice chip with the M3 selected state (VS-09, VS-35, VS-44): secondaryContainer fill, leading check, a 1dp
 * outlineVariant ring on selected chips in dark themes, and a 48dp touch target around the 36dp chip. The label colour
 * is checked against the fill, since raised-contrast Wallpaper schemes make the container mid-tone.
 */
@Composable
fun UMessageFilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = MaterialTheme.colorScheme
    val selectedContent = readableOn(colors.secondaryContainer, colors.onSecondaryContainer)
    FilterChip(
        selected = selected,
        onClick = onClick,
        enabled = enabled,
        label = { Text(label) },
        modifier = modifier.minimumInteractiveComponentSize().heightIn(min = ChipHeight),
        leadingIcon = if (selected) {
            { Icon(Symbols.Check, contentDescription = null) }
        } else {
            null
        },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = colors.secondaryContainer,
            selectedLabelColor = selectedContent,
            selectedLeadingIconColor = selectedContent,
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = enabled,
            selected = selected,
            borderColor = colors.outline,
            selectedBorderColor = if (colors.isDarkBackground) colors.outlineVariant else Color.Transparent,
        ),
    )
}

/**
 * The app's switch (VS-44, BR-43, U08): a check in the thumb when on, and a calm outlined off state with an empty
 * thumb, so on and off differ by more than colour without off looking like an error. [onCheckedChange] is null when
 * the whole row toggles it.
 */
@Composable
fun UMessageSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = MaterialTheme.colorScheme
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        enabled = enabled,
        thumbContent = if (checked) {
            {
                Icon(
                    Symbols.Check,
                    contentDescription = null,
                    modifier = Modifier.size(SwitchDefaults.IconSize),
                )
            }
        } else {
            null
        },
        colors = SwitchDefaults.colors(
            uncheckedThumbColor = colors.outline,
            uncheckedBorderColor = colors.outlineVariant,
            uncheckedTrackColor = Color.Transparent,
        ),
    )
}

/**
 * Primary action with the reason it cannot be used yet written above it (VS-12). The button stays disabled, in the
 * M3 disabled colours, and [reason] explains what is missing; the line disappears once [enabled] is true.
 */
@Composable
fun ReasonButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean,
    reason: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!enabled && reason.isNotBlank()) {
            Text(
                reason,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
            Text(text)
        }
    }
}

/**
 * Delete icon for rows (VS-14): a 48dp ghost button in onSurfaceVariant instead of a dark outlined glyph, so it does
 * not outrank the row's own tap target. Pair it with [DeleteConfirmDialog] or an Undo snackbar.
 */
@Composable
fun GhostDeleteButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentDescription: String = stringResource(R.string.screens_delete),
) {
    IconButton(onClick = onClick, modifier = modifier) {
        Icon(Symbols.Delete, contentDescription = contentDescription, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Confirmation for deleting one named item: destructive confirm button, Cancel as the default way out. */
@Composable
fun DeleteConfirmDialog(
    itemName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ConfirmDialog(
        message = stringResource(R.string.system_delete_item_message, itemName),
        confirmLabel = stringResource(R.string.screens_delete),
        onConfirm = onConfirm,
        onDismiss = onDismiss,
        modifier = modifier,
        title = stringResource(R.string.system_delete_item_title),
        destructive = true,
    )
}
