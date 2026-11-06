package com.glooboo.umessage.ui.spam

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.glooboo.umessage.R
import com.glooboo.umessage.spam.ListKind
import com.glooboo.umessage.spam.RuleType
import com.glooboo.umessage.ui.icons.Symbols

/**
 * "Test before saving" for keyword and pattern rules: type a sample message and see whether the rule would match it.
 * Nothing leaves the sheet; the sample is not stored. Shows nothing for types that are not about message text, or
 * while the value is not valid. It is a text button until tapped, then the sample field.
 */
@Composable
fun RulePatternTest(kind: ListKind, type: RuleType?, pattern: String, valid: Boolean, modifier: Modifier = Modifier) {
    if (type == null || !RuleTest.supports(type) || !valid) return
    var sample by rememberSaveable { mutableStateOf("") }
    var open by rememberSaveable { mutableStateOf(false) }
    if (!open) {
        // A text button, not a box that looks like an input.
        TextButton(onClick = { open = true }, modifier = modifier) {
            Icon(Symbols.Search, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(stringResource(R.string.rules2_test_label), modifier = Modifier.padding(start = 8.dp))
        }
        return
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            value = sample,
            onValueChange = { sample = it.take(MAX_SAMPLE) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.rules2_test_label)) },
            placeholder = { Text(stringResource(R.string.rules2_test_hint)) },
            minLines = 1,
            maxLines = 3,
        )
        if (sample.isNotBlank()) {
            val result = RuleTest.run(kind, type, pattern.trim(), sample)
            val colors = MaterialTheme.colorScheme
            Text(
                stringResource(
                    when (result) {
                        RuleTestResult.MATCH -> R.string.rules2_test_match
                        RuleTestResult.NO_MATCH -> R.string.rules2_test_no_match
                        RuleTestResult.UNUSABLE -> R.string.rules2_test_unusable
                    },
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = if (result == RuleTestResult.UNUSABLE) colors.error else colors.onSurfaceVariant,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

private const val MAX_SAMPLE = 500
