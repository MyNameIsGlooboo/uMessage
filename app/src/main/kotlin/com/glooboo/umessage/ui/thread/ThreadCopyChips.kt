package com.glooboo.umessage.ui.thread

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.glooboo.umessage.R
import com.glooboo.umessage.ui.icons.Symbols
import org.fossify.commons.extensions.copyToClipboard
import com.glooboo.umessage.helpers.SensitiveClip

/** The value is Latin or digits inside possibly right-to-left text: isolate it so it keeps its own direction. */
private const val LRI = "\u2066"
private const val PDI = "\u2069"
private val MAX_CHIP_TEXT_WIDTH = 220.dp

/**
 * TH-06: one-tap chips under a received bubble for every code, password or username in it (see [detectCopyTokens]).
 * Each chip shows the value ("Copy 482913") and wraps to the next line when the row is full. The clipboard is never
 * cleared automatically: the value is only marked sensitive.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CopyChips(body: String, modifier: Modifier = Modifier) {
    val tokens = remember(body) { detectCopyTokens(body) }
    if (tokens.isEmpty()) return
    val context = LocalContext.current
    FlowRow(
        modifier = modifier.padding(top = 4.dp, start = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        tokens.forEach { token ->
            val kindLabel = stringResource(
                when (token.kind) {
                    CopyKind.Code -> R.string.thread_review_copy_code
                    CopyKind.Password -> R.string.thread_review_copy_password
                    CopyKind.Username -> R.string.thread_review_copy_username
                },
            )
            val isolated = LRI + token.value + PDI
            val shown = stringResource(R.string.thread_copy_value_format, isolated)
            Surface(
                onClick = {
                    context.copyToClipboard(token.value)
                    // Marked sensitive (hidden from previews) but never cleared by the app.
                    SensitiveClip.mark(context, token.value, clear = false)
                },
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.heightIn(min = 32.dp).semantics { contentDescription = "$kindLabel $isolated" },
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Symbols.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.size(6.dp))
                    Text(
                        shown,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = MAX_CHIP_TEXT_WIDTH),
                    )
                }
            }
        }
    }
}
