package com.glooboo.umessage.ui.onboarding

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.IntrinsicMeasurable
import androidx.compose.ui.layout.IntrinsicMeasureScope
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import com.glooboo.umessage.R

/** Test tags of the two options of the Welcome choice. */
internal const val FILTER_ON_TAG = "onb_filter_on"
internal const val FILTER_OFF_TAG = "onb_filter_off"

/**
 * Provider truth (4.6, U12): the spam filter choice under the sorting card on the Welcome page. Two radio cards, the
 * first ("Sort unknown senders into Spam") recommended and chosen by default, each with its second line, then a hint
 * that Settings can change it. The heading names the radio group. The cards wrap at large font sizes; nothing is cut.
 */
@Composable
internal fun WelcomeFilterChoice(
    filterOn: Boolean,
    onFilter: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    saveFailed: Boolean = false,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // The heading takes the whole width and wraps onto as many lines as it needs: measured at its own one-line
        // width, the Persian heading at twice the font size was cut instead of wrapping (G-8).
        Text(
            stringResource(R.string.spam_settings_unknown_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            softWrap = true,
            maxLines = Int.MAX_VALUE,
            modifier = Modifier.fillMaxWidth().semantics { heading() },
        )
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ChoiceCard(
                selected = filterOn,
                titleRes = R.string.pt_onb_filter_on,
                descRes = R.string.pt_onb_filter_on_desc,
                recommended = true,
                onSelect = { onFilter(true) },
                modifier = Modifier.testTag(FILTER_ON_TAG),
            )
            ChoiceCard(
                selected = !filterOn,
                titleRes = R.string.pt_onb_filter_off,
                descRes = R.string.pt_onb_filter_off_desc,
                recommended = false,
                onSelect = { onFilter(false) },
                modifier = Modifier.testTag(FILTER_OFF_TAG),
            )
        }
        Text(
            stringResource(R.string.pt_onb_filter_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (saveFailed) {
            Text(
                stringResource(R.string.spam_snack_failed),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

/**
 * A radio card: the bold name with the "Recommended" tag after it (on its own line when they do not fit), and the
 * description under it. TalkBack reads the card as one radio button: name, tag and description.
 */
@Composable
private fun ChoiceCard(
    selected: Boolean,
    titleRes: Int,
    descRes: Int,
    recommended: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val border by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
        label = "modeBorder",
    )
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(if (selected) 2.dp else 1.dp, border),
        modifier = modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect),
    ) {
        Row(
            Modifier
                .padding(12.dp)
                .heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selected, onClick = null)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                ChoiceTitle(titleRes, recommended)
                Text(
                    stringResource(descRes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    softWrap = true,
                    maxLines = Int.MAX_VALUE,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/**
 * The card's bold name and, when [recommended], the tag: side by side when both fit on one line, else the tag on its
 * own line under the name. Every text is measured at a fixed width so it wraps there: given a loose width, a Persian
 * name at twice the font size was laid out wider than its own box and cut (G-8). See [ChoiceTitleMeasurePolicy].
 */
@Composable
internal fun ChoiceTitle(titleRes: Int, recommended: Boolean, modifier: Modifier = Modifier) {
    Layout(
        content = {
            Text(
                stringResource(titleRes),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                softWrap = true,
                maxLines = Int.MAX_VALUE,
            )
            if (recommended) {
                Text(
                    stringResource(R.string.settings_nav_recommended),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    softWrap = true,
                    maxLines = Int.MAX_VALUE,
                )
            }
        },
        modifier = modifier.fillMaxWidth(),
        measurePolicy = ChoiceTitleMeasurePolicy,
    )
}

/**
 * How [ChoiceTitle] lays out its name (first child) and tag (second, optional). An unbounded width (a horizontal
 * scroll, a parent that sizes by intrinsics) puts both on one line at their own widths (R2-2). The width intrinsics
 * are that one-line width (max) and the widest single text (min), so an intrinsic query never measures at an
 * infinite width.
 */
internal object ChoiceTitleMeasurePolicy : MeasurePolicy {
    private val gap = 8.dp

    override fun MeasureScope.measure(measurables: List<Measurable>, constraints: Constraints): MeasureResult {
        val oneLine = oneLineWidth(measurables, gap.roundToPx())
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else constraints.constrainWidth(oneLine)
        val fixed = { w: Int -> Constraints(minWidth = w, maxWidth = w, maxHeight = constraints.maxHeight) }
        val tagMeasurable = measurables.getOrNull(1)
        return if (tagMeasurable != null && oneLine <= width) {
            val titleWidth = measurables[0].maxIntrinsicWidth(Constraints.Infinity)
            val title = measurables[0].measure(fixed(titleWidth))
            val tag = tagMeasurable.measure(fixed(tagMeasurable.maxIntrinsicWidth(Constraints.Infinity)))
            val height = constraints.constrainHeight(maxOf(title.height, tag.height))
            layout(width, height) {
                title.placeRelative(0, (height - title.height) / 2)
                tag.placeRelative(titleWidth + gap.roundToPx(), (height - tag.height) / 2)
            }
        } else {
            val title = measurables[0].measure(fixed(width))
            val tag = tagMeasurable?.measure(fixed(width))
            layout(width, constraints.constrainHeight(title.height + (tag?.height ?: 0))) {
                title.placeRelative(0, 0)
                tag?.placeRelative(0, title.height)
            }
        }
    }

    override fun IntrinsicMeasureScope.maxIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int): Int =
        oneLineWidth(measurables, gap.roundToPx())

    override fun IntrinsicMeasureScope.minIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int): Int =
        measurables.maxOfOrNull { it.minIntrinsicWidth(Constraints.Infinity) } ?: 0

    override fun IntrinsicMeasureScope.minIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int): Int =
        intrinsicHeight(measurables, width) { child, w -> child.minIntrinsicHeight(w) }

    override fun IntrinsicMeasureScope.maxIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int): Int =
        intrinsicHeight(measurables, width) { child, w -> child.maxIntrinsicHeight(w) }

    /** The name's and the tag's one-line widths plus the gap; the name's alone without a tag. */
    private fun oneLineWidth(measurables: List<IntrinsicMeasurable>, gapPx: Int): Int {
        val title = measurables[0].maxIntrinsicWidth(Constraints.Infinity)
        val tag = measurables.getOrNull(1)?.maxIntrinsicWidth(Constraints.Infinity) ?: return title
        return title + gapPx + tag
    }

    /** The height [measure] would report at [width], with [height] as the children's intrinsic height. */
    private fun IntrinsicMeasureScope.intrinsicHeight(
        measurables: List<IntrinsicMeasurable>,
        width: Int,
        height: (IntrinsicMeasurable, Int) -> Int,
    ): Int {
        val oneLine = oneLineWidth(measurables, gap.roundToPx())
        val given = if (width == Constraints.Infinity) oneLine else width
        return if (measurables.size > 1 && oneLine <= given) {
            measurables.maxOf { height(it, it.maxIntrinsicWidth(Constraints.Infinity)) }
        } else {
            measurables.sumOf { height(it, given) }
        }
    }
}
