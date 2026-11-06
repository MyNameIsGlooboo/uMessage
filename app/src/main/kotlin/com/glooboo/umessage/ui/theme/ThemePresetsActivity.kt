package com.glooboo.umessage.ui.theme

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.glooboo.umessage.R
import com.glooboo.umessage.ui.components.UMessageScaffold
import com.glooboo.umessage.ui.components.UMessageTopBar
import com.glooboo.umessage.ui.icons.Symbols
import com.glooboo.umessage.ui.settings.AppearanceDisplayRows

/**
 * Appearance: a Mode (System, Light, Dark, Black) and Colours (Wallpaper and the palettes) as two independent choices
 * (SE-44), then text size, calendar and unread first (U05). There is no colour editor and the launcher icon never
 * changes. No intro or colour description: the swatches and their names say it (U52).
 */
class ThemePresetsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        applyUMessageWindow()
        super.onCreate(savedInstanceState)
        setContent {
            UMessageTheme {
                PresetsScreen(onBack = ::finish)
            }
        }
    }
}

private val SidePadding = 16.dp
private val CardSpacing = 12.dp
private val MinCardWidth = 96.dp

/** The color grid never has more than three columns, so 7 cards read 3, 3, 1 (owner, review 2). */
private const val MAX_COLUMNS = 3

/**
 * How many cards go in each row: every row full except the last, which holds what is left. The rows are laid out
 * from the start edge, so a short last row sits under the first columns like the rows above it (the right side in
 * Persian). 7 cards in 3 columns are 3, 3 and 1.
 */
internal fun gridRows(count: Int, columns: Int): List<Int> {
    if (count <= 0) return emptyList()
    val perRow = columns.coerceIn(1, count)
    return List((count + perRow - 1) / perRow) { index -> minOf(perRow, count - index * perRow) }
}

@Composable
private fun PresetsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    // The theme observes the same state, so a new choice recolours this and every other open screen in place.
    val choice by ThemePresets.flow(context).collectAsStateWithLifecycle()
    val colours = ThemePresets.colourOptions()
    UMessageScaffold(
        topBar = { UMessageTopBar(title = stringResource(R.string.settings_nav_theme_title), onBack = onBack) },
    ) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
            val fit = ((maxWidth - SidePadding * 2 + CardSpacing) / (MinCardWidth + CardSpacing)).toInt()
            val columns = minOf(fit, MAX_COLUMNS).coerceAtLeast(1)
            val rows = gridRows(colours.size, columns)
            val chunks = buildList {
                var start = 0
                rows.forEach { size -> add(colours.subList(start, start + size)); start += size }
            }
            LazyColumn(
                contentPadding = PaddingValues(
                    start = SidePadding,
                    end = SidePadding,
                    top = 8.dp,
                    bottom = 16.dp + padding.calculateBottomPadding(),
                ),
                verticalArrangement = Arrangement.spacedBy(CardSpacing),
                modifier = Modifier.fillMaxSize(),
            ) {
                item(key = "mode-title") { SectionTitle(R.string.settings_nav_theme_modes) }
                item(key = "mode") {
                    ModeSelector(
                        onPick = { mode -> if (mode != choice.mode) ThemePresets.setMode(context, mode) },
                        selected = choice.mode,
                    )
                }
                item(key = "colours-title") { SectionTitle(R.string.settings_nav_theme_colours) }
                itemsIndexed(chunks, key = { index, _ -> "colours-$index" }) { _, row ->
                    ColourRow(
                        onPick = { picked -> if (picked != choice.colours) ThemePresets.setColours(context, picked) },
                        row = row,
                        columns = columns,
                        choice = choice,
                    )
                }
                item(key = "display-title") { SectionTitle(R.string.settings_f_group_display) }
                item(key = "display") { AppearanceDisplayRows() }
            }
        }
    }
}

@Composable
private fun SectionTitle(@StringRes title: Int) {
    Text(
        stringResource(title),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp).semantics { heading() },
    )
}

@Composable
private fun Description(@StringRes text: Int) {
    Text(
        stringResource(text),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Brightness on its own: a segmented row, with the chosen mode explained below it. */
@Composable
private fun ModeSelector(onPick: (ThemeMode) -> Unit, selected: ThemeMode) {
    val colors = MaterialTheme.colorScheme
    val modes = ThemeMode.entries
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            modes.forEachIndexed { index, mode ->
                SegmentedButton(
                    selected = mode == selected,
                    onClick = { onPick(mode) },
                    shape = SegmentedButtonDefaults.itemShape(index, modes.size),
                    colors = SegmentedButtonDefaults.colors(
                        activeContainerColor = colors.secondaryContainer,
                        activeContentColor = colors.onSecondaryContainer,
                        activeBorderColor = colors.outline,
                        inactiveContainerColor = Color.Transparent,
                        inactiveContentColor = colors.onSurface,
                        inactiveBorderColor = colors.outline,
                    ),
                    icon = {},
                ) {
                    Text(stringResource(mode.label), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Description(selected.description)
    }
}

/**
 * One grid row. A shorter last row starts at the start edge like the full rows; one empty spacer per missing column
 * keeps its cards and gaps exactly as wide as theirs. Row follows the layout direction, so in Persian it starts right.
 */
@Composable
private fun ColourRow(onPick: (ThemeColours) -> Unit, row: List<ThemeColours>, columns: Int, choice: ThemeChoice) {
    val missing = columns - row.size
    Row(
        Modifier.fillMaxWidth().height(IntrinsicSize.Min).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(CardSpacing),
    ) {
        row.forEach { colours ->
            ColourCard(colours, choice, onClick = { onPick(colours) }, modifier = Modifier.weight(1f).fillMaxHeight())
        }
        repeat(missing.coerceAtLeast(0)) { Spacer(Modifier.weight(1f)) }
    }
}

@Composable
private fun ColourCard(colours: ThemeColours, choice: ThemeChoice, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val name = stringResource(colours.label)
    val description = stringResource(colours.description)
    val isSelected = colours == choice.colours
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier = modifier
            .clip(shape)
            .background(colors.surfaceContainer)
            .then(if (isSelected) Modifier.border(BorderStroke(2.dp, colors.primary), shape) else Modifier)
            .selectable(selected = isSelected, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = "$name. $description" }
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Swatch(previewScheme(colours, choice.mode))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                name,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (isSelected) {
                Icon(
                    Symbols.CheckCircleFilled,
                    contentDescription = null,
                    tint = colors.primary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/** The scheme these colours give with the chosen mode in the phone's current light or dark: a live preview. */
@Composable
private fun previewScheme(colours: ThemeColours, mode: ThemeMode): ColorScheme =
    resolveColorScheme(LocalContext.current, ThemeChoice(mode, colours), null, null, isSystemInDarkTheme())

/** A rounded bar standing for a line of text in the swatch. */
@Composable
private fun TextLine(color: Color, modifier: Modifier = Modifier) {
    Box(modifier.clip(RoundedCornerShape(3.dp)).background(color))
}

/** A tiny conversation list drawn in [scheme]'s colours. */
@Composable
private fun Swatch(scheme: ColorScheme) {
    val text = scheme.onBackground
    val primary = scheme.primary
    Box(
        Modifier
            .fillMaxWidth()
            .height(64.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(scheme.background)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
            .padding(8.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(12.dp).clip(RoundedCornerShape(4.dp)).background(primary))
                TextLine(text.copy(alpha = 0.85f), Modifier.height(5.dp).weight(1f))
            }
            TextLine(text.copy(alpha = 0.45f), Modifier.height(4.dp).fillMaxWidth(0.8f))
        }
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .size(width = 22.dp, height = 14.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(primary),
        )
    }
}
