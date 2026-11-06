package com.glooboo.umessage.ui.main

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.glooboo.umessage.R
import com.glooboo.umessage.ui.components.localizedNumber
import com.glooboo.umessage.ui.components.uiLocale
import com.glooboo.umessage.ui.icons.ShortcutIcons
import com.glooboo.umessage.ui.icons.Symbols

/**
 * One tile of the search page's filter grid: an icon and one word. A new filter (labels, say) is one more entry in
 * [searchFilterTiles] and one more [InboxFilter] value; Unknown, Starred, Images and Links look more up
 * ([SearchShortcuts]). The empty texts say what the inbox shows while the filter is on and nothing matches.
 */
internal class SearchFilterTile(
    val filter: InboxFilter,
    val labelRes: Int,
    val icon: ImageVector,
    val emptyTitleRes: Int,
    val emptyBodyRes: Int,
)

internal val searchFilterTiles: List<SearchFilterTile> by lazy {
    listOf(
        SearchFilterTile(
            InboxFilter.Unread, R.string.w2b1_filter_unread, ShortcutIcons.UnreadDot,
            R.string.w2b1_filter_unread_empty_title, R.string.w2b1_filter_unread_empty_body,
        ),
        SearchFilterTile(
            InboxFilter.Codes, R.string.r2a_filter_codes, Symbols.Tag,
            R.string.r2a_filter_codes_empty_title, R.string.r2a_filter_codes_empty_body,
        ),
        SearchFilterTile(
            InboxFilter.Transactions, R.string.i11_filter_transactions, TransactionsIcon,
            R.string.i11_filter_transactions_empty_title, R.string.i11_filter_transactions_empty_body,
        ),
        SearchFilterTile(
            InboxFilter.Offers, R.string.i11_filter_offers, OffersIcon,
            R.string.i11_filter_offers_empty_title, R.string.i11_filter_offers_empty_body,
        ),
        SearchFilterTile(
            InboxFilter.Unknown, R.string.i28_filter_unknown, ShortcutIcons.UnknownPerson,
            R.string.i28_filter_unknown_empty_title, R.string.i28_filter_unknown_empty_body,
        ),
        SearchFilterTile(
            InboxFilter.Starred, R.string.i13_filter_starred, ShortcutIcons.Star,
            R.string.i13_filter_starred_empty_title, R.string.i13_filter_starred_empty_body,
        ),
        SearchFilterTile(
            InboxFilter.Images, R.string.i28_filter_images, Symbols.Image,
            R.string.i28_filter_images_empty_title, R.string.i28_filter_images_empty_body,
        ),
        SearchFilterTile(
            InboxFilter.Links, R.string.i28_filter_links, ShortcutIcons.Link,
            R.string.i28_filter_links_empty_title, R.string.i28_filter_links_empty_body,
        ),
    )
}

/** The tile of [filter]; null for [InboxFilter.All], which has none. */
internal fun filterTile(filter: InboxFilter): SearchFilterTile? = searchFilterTiles.firstOrNull { it.filter == filter }

private const val LARGE_FONT_SCALE = 1.15f
private const val LARGEST_FONT_SCALE = 1.5f
private const val COLUMNS_LARGE_FONT = 3
private const val COLUMNS_DEFAULT = 4

/** Four tiles to a row; fewer as the font grows, so a word still fits under its icon. */
private fun tileColumns(fontScale: Float): Int = when {
    fontScale >= LARGEST_FONT_SCALE -> 2
    fontScale >= LARGE_FONT_SCALE -> COLUMNS_LARGE_FONT
    else -> COLUMNS_DEFAULT
}

/** The grid of filter tiles, then one tile per SIM on a phone with two; [selected] is the filter that is on. */
@Composable
internal fun SearchFilterGrid(
    selected: InboxFilter,
    simTiles: List<SimTile>,
    selectedSim: Int,
    onTap: (InboxFilter) -> Unit,
    onTapSim: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val columns = tileColumns(LocalDensity.current.fontScale)
    val context = LocalContext.current
    // Unknown needs the contacts; without that permission its tile is not offered.
    val tiles = remember(context) {
        searchFilterTiles.filter { it.filter != InboxFilter.Unknown || SearchShortcuts.unknownAvailable(context) }
    }
    val filterCells = tiles.map { tile ->
        GridCell(stringResource(tile.labelRes), tile.icon, tile.filter == selected) { onTap(tile.filter) }
    }
    val locale = context.uiLocale
    val simCells = simTiles.map { sim ->
        val on = selected == InboxFilter.Sim && sim.subscriptionId == selectedSim
        val description = stringResource(R.string.sim_tile_description, sim.label, localizedNumber(sim.slot, locale))
        GridCell(sim.label, SimOutlineIcon, on, localizedNumber(sim.slot, locale), description) {
            onTapSim(sim.subscriptionId)
        }
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        (filterCells + simCells).chunked(columns).forEach { rowCells ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowCells.forEach { cell ->
                    SearchTile(
                        cell.label, cell.icon, cell.selected, cell.onClick, Modifier.weight(1f),
                        badge = cell.badge, description = cell.description,
                    )
                }
                repeat(columns - rowCells.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

private class GridCell(
    val label: String,
    val icon: ImageVector,
    val selected: Boolean,
    val badge: String? = null,
    val description: String? = null,
    val onClick: () -> Unit,
)

private val SimIconSize = 32.dp
private val SimDigitSize = 13.dp
private val SimDigitDrop = 3.dp

/**
 * One tile: an icon on a rounded pad (tinted by [tint] when given) and one or two words under it. A [badge] is a
 * short text drawn inside the icon in the pad's own colour, and [description] replaces what a screen reader says.
 */
@Composable
internal fun SearchTile(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color? = null,
    badge: String? = null,
    description: String? = null,
) {
    val colors = MaterialTheme.colorScheme
    val padColor = if (selected) colors.secondaryContainer else colors.surfaceContainerHigh
    val said = if (description == null) Modifier else Modifier.semantics(mergeDescendants = true) {
        contentDescription = description
    }
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .toggleable(value = selected, role = Role.Checkbox, onValueChange = { onClick() })
            .then(said)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = padColor,
            border = if (selected) BorderStroke(2.dp, colors.primary) else null,
            modifier = Modifier.width(64.dp).height(48.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    icon,
                    contentDescription = null,
                    modifier = Modifier.size(if (badge == null) 24.dp else SimIconSize),
                    tint = tint ?: if (selected) colors.onSecondaryContainer else colors.onSurfaceVariant,
                )
                if (badge != null) {
                    val ink = tint ?: if (selected) colors.onSecondaryContainer else colors.onSurfaceVariant
                    TileBadge(badge, ink, Modifier.padding(top = SimDigitDrop))
                }
            }
        }
        Text(
            label,
            modifier = if (description == null) Modifier else Modifier.clearAndSetSemantics { },
            style = MaterialTheme.typography.labelMedium,
            color = colors.onSurface,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The digit inside a tile's icon. Its size does not follow the font scale: the icon it sits in does not grow. */
@Composable
private fun TileBadge(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier.clearAndSetSemantics { },
        style = MaterialTheme.typography.labelMedium.copy(
            fontSize = with(LocalDensity.current) { SimDigitSize.toSp() },
            fontWeight = FontWeight.Bold,
        ),
        color = color,
        maxLines = 1,
    )
}

/** A bank card with a stripe. Drawn here: the generated Symbols object is not edited by hand. */
private val TransactionsIcon: ImageVector by lazy {
    vector(
        "Transactions",
        "M160,200L800,200Q840,200 840,240L840,720Q840,760 800,760L160,760Q120,760 120,720L120,240Q120,200 160,200Z" +
            "M120,360L840,360L840,440L120,440Z" +
            "M200,560L360,560L360,640L200,640Z",
    )
}

/** A SIM card outline with a cut corner and an empty middle for the slot digit (no contact pads to clash with it). */
private val SimOutlineIcon: ImageVector by lazy {
    vector(
        "SimOutline",
        "M240,880Q207,880 183.5,856.5Q160,833 160,800L160,320L400,80L720,80Q753,80 776.5,103.5Q800,127 800,160" +
            "L800,800Q800,833 776.5,856.5Q753,880 720,880L240,880Z" +
            "M240,800L720,800L720,160L434,160L240,354L240,800Z",
    )
}

/** A price tag with a hole. */
private val OffersIcon: ImageVector by lazy {
    vector(
        "Offers",
        "M120,200L600,200L840,480L600,760L120,760Z" +
            "M190,480A50,50 0 1,0 290,480A50,50 0 1,0 190,480Z",
    )
}

private fun vector(name: String, pathData: String): ImageVector = ImageVector.Builder(
    name = name,
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 960f,
    viewportHeight = 960f,
).addPath(
    PathParser().parsePathString(pathData).toNodes(),
    pathFillType = PathFillType.EvenOdd,
    fill = SolidColor(Color.Black),
).build()
