package com.glooboo.umessage.ui.spam

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.glooboo.umessage.R
import com.glooboo.umessage.ui.components.InfoDialog

private class HelpSection(val head: Int, val body: Int, val example: Int)

private val HelpSections = listOf(
    HelpSection(R.string.w7d_help_words_head, R.string.w7d_help_words_body, R.string.w7d_help_words_example),
    HelpSection(R.string.w7d_help_sender_head, R.string.w7d_help_sender_body, R.string.w7d_help_sender_example),
    HelpSection(R.string.w7d_help_number_head, R.string.w7d_help_number_body, R.string.w7d_help_number_example),
    HelpSection(R.string.w7d_help_pattern_head, R.string.w7d_help_pattern_body, R.string.w7d_help_pattern_example),
)

/** The short guide to blocking words: four sections, each a heading, one or two lines and a tiny example. */
@Composable
internal fun KeywordHelpDialog(onDismiss: () -> Unit) {
    InfoDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.w7d_help_title),
        confirmLabel = stringResource(R.string.w7d_help_close),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            HelpSections.forEach { section ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        stringResource(section.head),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.semantics { heading() },
                    )
                    Text(
                        stringResource(section.body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    ExampleChip(stringResource(section.example))
                }
            }
        }
    }
}

@Composable
private fun ExampleChip(text: String) {
    Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceVariant) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}
