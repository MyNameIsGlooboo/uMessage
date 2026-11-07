package com.glooboo.umessage.ui.spam

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.glooboo.umessage.R
import com.glooboo.umessage.spam.KeywordMatch

/**
 * "Whole word only" for a keyword rule: on puts the word in double quotes ([KeywordMatch]), so "win" does not hit
 * "window". It edits the typed value, so what the rule matches stays visible in the field.
 */
@Composable
fun WholeWordSwitch(pattern: String, onPattern: (String) -> Unit, modifier: Modifier = Modifier) {
    val on = KeywordMatch.isWholeWord(pattern)
    val enabled = pattern.isNotBlank()
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(
                value = on,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = { onPattern(KeywordMatch.toggled(pattern)) },
            ),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(R.string.s042_whole_word_title), style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(R.string.s042_whole_word_help),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = on, onCheckedChange = null, enabled = enabled)
    }
}
