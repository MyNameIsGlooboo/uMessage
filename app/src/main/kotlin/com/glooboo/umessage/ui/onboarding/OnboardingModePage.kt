package com.glooboo.umessage.ui.onboarding

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.glooboo.umessage.R
import com.glooboo.umessage.spam.UnknownSenderMode
import com.glooboo.umessage.ui.components.ChoiceDialog
import com.glooboo.umessage.ui.icons.Symbols
import com.glooboo.umessage.ui.spam.SettingsUiState

/** The unknown-senders step: three short radio rows, how long blocked messages are kept, and delivery reports. */
@Composable
internal fun ModePage(
    mode: UnknownSenderMode,
    onMode: (UnknownSenderMode) -> Unit,
    purgeDays: Int,
    onPurgeDays: (Int) -> Unit,
    modifier: Modifier = Modifier,
    deliveryReports: Boolean = true,
    onDeliveryReports: (Boolean) -> Unit = {},
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PageTitle(stringResource(R.string.onb_mode_title))
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ModeOption(
                UnknownSenderMode.STRICT, mode, R.string.onb_mode_strict, R.string.onb_mode_strict_short, false, onMode,
            )
            ModeOption(
                UnknownSenderMode.SMART, mode, R.string.onb_mode_smart, R.string.onb_mode_smart_short, true, onMode,
            )
            ModeOption(UnknownSenderMode.OFF, mode, R.string.onb_mode_off, R.string.onb_mode_off_short, false, onMode)
        }
        if (mode != UnknownSenderMode.OFF) PurgeRow(purgeDays, onPurgeDays)
        if (mode == UnknownSenderMode.STRICT) StrictWarning()
        DeliveryReportsRow(deliveryReports, onDeliveryReports)
    }
}

/** Test tags of the two options of the Welcome choice. */
internal const val FILTER_ON_TAG = "onb_filter_on"
internal const val FILTER_OFF_TAG = "onb_filter_off"

/**
 * Provider truth (4.6, U12): the spam filter choice under the sorting card on the Welcome page. Two radio cards, the
 * first ("Sort unknown senders into Spam") recommended and chosen by default, each with its second line, then a hint
 * that Settings can change it. The heading names the radio group. The cards wrap at large font sizes; nothing is cut.
 */
@Composable
internal fun WelcomeFilterChoice(filterOn: Boolean, onFilter: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(R.string.spam_settings_unknown_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.semantics { heading() },
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
    }
}

/** One radio card with two lines: the bold name and a one-line description. */
@Composable
private fun ModeOption(
    value: UnknownSenderMode,
    current: UnknownSenderMode,
    titleRes: Int,
    descRes: Int,
    recommended: Boolean,
    onMode: (UnknownSenderMode) -> Unit,
) = ChoiceCard(current == value, titleRes, descRes, recommended, onSelect = { onMode(value) })

/**
 * A radio card: the bold name with the "Recommended" tag after it (on its own line when they do not fit), and the
 * description under it. TalkBack reads the card as one radio button: name, tag and description.
 */
@OptIn(ExperimentalLayoutApi::class)
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
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(titleRes),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (recommended) {
                        Text(
                            stringResource(R.string.settings_nav_recommended),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Text(
                    stringResource(descRes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun StrictWarning() {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { },
    ) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Symbols.Warning, null)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(R.string.onb_mode_warning_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(stringResource(R.string.onb_mode_warning_body), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun purgeLabel(days: Int): String =
    if (days == 0) stringResource(R.string.spam_purge_forever) else stringResource(R.string.spam_purge_days, days)

/** How long blocked messages are kept, on one line: the label, then a dropdown with the same choices as Settings. */
@Composable
internal fun PurgeRow(days: Int, onDays: (Int) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    val options = remember(days) {
        (SettingsUiState.PRESET_PURGE_DAYS + days).distinct().sortedBy { if (it == 0) Int.MAX_VALUE else it }
    }
    val question = stringResource(R.string.onb_purge_question)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(question, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        OutlinedButton(onClick = { open = true }, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(purgeLabel(days), maxLines = 1)
            Icon(Symbols.ExpandMore, null, modifier = Modifier.size(18.dp))
        }
    }
    if (open) {
        ChoiceDialog(
            title = question,
            options = options.map { purgeLabel(it) },
            selectedIndex = options.indexOf(days),
            onSelect = {
                open = false
                onDays(options[it])
            },
            onDismiss = { open = false },
        )
    }
}
