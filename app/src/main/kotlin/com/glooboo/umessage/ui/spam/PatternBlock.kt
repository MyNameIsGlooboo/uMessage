package com.glooboo.umessage.ui.spam

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.glooboo.umessage.R

/**
 * Ready-made pieces for the Pattern rule (ID-41), so a pattern can be made without knowing regular expressions.
 * [template] is what a chip puts in the field; [STARTS_WITH] and [ANY_OF] are filled in by the user afterwards.
 */
enum class PatternBlock(val template: String) {
    /** A web address. */
    LINK("(https?://|www\\.)\\S+"),

    /** A number of five or more digits, such as an account or tracking number. */
    LONG_NUMBER("\\d{5,}"),

    /** The text starts with what follows `^`. */
    STARTS_WITH("^"),

    /** Any of the words between the brackets, separated by `|`. */
    ANY_OF("(first|second)"),
    ;

    companion object {
        /** The block [pattern] was made from, so the sheet can say in words what it does. */
        fun of(pattern: String): PatternBlock? {
            val value = pattern.trim()
            return when {
                value == LINK.template -> LINK
                value == LONG_NUMBER.template -> LONG_NUMBER
                value.startsWith("^") && !value.contains('|') -> STARTS_WITH
                value.startsWith("(") && value.endsWith(")") && value.contains('|') && !value.contains('\\') -> ANY_OF
                else -> null
            }
        }

        /** What a chip leaves in the field: "Starts with" keeps the text already typed after a `^`. */
        fun apply(block: PatternBlock, current: String): String = when (block) {
            STARTS_WITH -> "^" + current.trim().removePrefix("^").takeIf { of(current) == STARTS_WITH }.orEmpty()
            else -> block.template
        }
    }
}

/** The chips above the pattern field and, under them, one plain sentence for the block in use. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PatternBlockChips(pattern: String, onPattern: (String) -> Unit, modifier: Modifier = Modifier) {
    val current = PatternBlock.of(pattern)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.e_pattern_blocks_label), style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PatternBlock.entries.forEach { block ->
                RuleChoiceChip(
                    selected = current == block,
                    onClick = { onPattern(PatternBlock.apply(block, pattern)) },
                    label = stringResource(blockLabel(block)),
                )
            }
        }
        current?.let {
            Text(
                stringResource(blockSentence(it)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun blockLabel(block: PatternBlock): Int = when (block) {
    PatternBlock.LINK -> R.string.e_pattern_link
    PatternBlock.LONG_NUMBER -> R.string.e_pattern_long_number
    PatternBlock.STARTS_WITH -> R.string.e_pattern_starts_with
    PatternBlock.ANY_OF -> R.string.e_pattern_any_of
}

private fun blockSentence(block: PatternBlock): Int = when (block) {
    PatternBlock.LINK -> R.string.minor_sui_pattern_link_sentence
    PatternBlock.LONG_NUMBER -> R.string.e_pattern_long_number_sentence
    PatternBlock.STARTS_WITH -> R.string.e_pattern_starts_with_sentence
    PatternBlock.ANY_OF -> R.string.e_pattern_any_of_sentence
}
