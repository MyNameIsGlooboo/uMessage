package com.glooboo.umessage.ui.spam

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.glooboo.umessage.R
import com.glooboo.umessage.spam.RuleType

/**
 * I22: under the "Always allow?" question, the box "Also allow messages like this" with the rule it would add. Edit
 * opens that rule in the rules editor instead. A word or a number start lets more than one sender through, so those
 * say so.
 */
@Composable
internal fun AllowSimilarOption(
    prompt: AllowPrompt,
    onIncludeSimilar: (Long, Boolean) -> Unit,
    onEdit: () -> Unit,
) {
    val similar = prompt.similar ?: return
    Column(Modifier.padding(top = 16.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .toggleable(
                    value = prompt.includeSimilar,
                    role = Role.Checkbox,
                    onValueChange = { onIncludeSimilar(prompt.token, it) },
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = prompt.includeSimilar, onCheckedChange = null)
            Column(Modifier.padding(start = 12.dp)) {
                Text(stringResource(R.string.allow_similar_title), style = MaterialTheme.typography.bodyLarge)
                Text(
                    stringResource(similarLabel(similar.type), similar.pattern),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            stringResource(similarRisk(similar.type)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val context = LocalContext.current
        TextButton(onClick = {
                onEdit()
                context.startActivity(prefillIntent(context, similar))
            },) {
            Text(stringResource(R.string.allow_similar_edit))
        }
    }
}

private fun similarLabel(type: RuleType): Int = when (type) {
    RuleType.SENDER_ID -> R.string.allow_similar_sender
    RuleType.PREFIX -> R.string.allow_similar_prefix
    else -> R.string.allow_similar_keyword
}

private fun similarRisk(type: RuleType): Int = when (type) {
    RuleType.SENDER_ID -> R.string.allow_similar_risk_sender
    RuleType.PREFIX -> R.string.allow_similar_risk_prefix
    else -> R.string.allow_similar_risk_keyword
}

private fun prefillIntent(context: Context, similar: SimilarRuleSuggestion.Suggestion): Intent =
    Intent(context, RulesActivity::class.java)
        .putExtra(RulesActivity.EXTRA_PREFILL_TYPE, similar.type.name)
        .putExtra(RulesActivity.EXTRA_PREFILL_PATTERN, similar.pattern)
