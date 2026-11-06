package com.glooboo.umessage.ui.spam

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.glooboo.umessage.R
import com.glooboo.umessage.spam.RuleType

private val commonTypes = listOf(RuleType.NUMBER, RuleType.SENDER_ID, RuleType.KEYWORD)
private val moreTypes = listOf(RuleType.PREFIX, RuleType.REGEX)

/** The type said in the plain words of the add-rule sheet ("A word in the message"). */
private fun matchLabel(type: RuleType): Int = when (type) {
    RuleType.NUMBER -> R.string.ux8d_match_number
    RuleType.SENDER_ID -> R.string.ux8d_match_sender
    RuleType.KEYWORD -> R.string.ux8d_match_word
    RuleType.PREFIX -> R.string.ux8d_match_prefix
    RuleType.REGEX -> R.string.ux8d_match_pattern
}

/** The type said once, short, for a rule's row and the type filter ("Word in message"). */
@Composable
fun ruleRowTypeLabel(type: RuleType): String = stringResource(
    when (type) {
        RuleType.NUMBER -> R.string.ux8d_row_type_number
        RuleType.PREFIX -> R.string.ux8d_row_type_prefix
        RuleType.SENDER_ID -> R.string.ux8d_row_type_sender
        RuleType.KEYWORD -> R.string.ux8d_row_type_word
        RuleType.REGEX -> R.string.ux8d_row_type_pattern
    },
)

/**
 * "What to match": three plain choices. Number-prefix and pattern sit behind "More ways to match", open from the
 * start when the rule being edited already uses one of them.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RuleMatchPicker(selected: RuleType, onType: (RuleType) -> Unit, modifier: Modifier = Modifier) {
    var more by rememberSaveable { mutableStateOf(selected in moreTypes) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.ux8d_match_label), style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (if (more) commonTypes + moreTypes else commonTypes).forEach { type ->
                RuleChoiceChip(
                    selected = selected == type,
                    onClick = { onType(type) },
                    label = stringResource(matchLabel(type)),
                )
            }
        }
        if (!more) {
            TextButton(onClick = { more = true }) { Text(stringResource(R.string.ux8d_match_more)) }
        }
    }
}
