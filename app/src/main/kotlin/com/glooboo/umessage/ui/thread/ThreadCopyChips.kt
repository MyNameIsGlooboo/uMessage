package com.glooboo.umessage.ui.thread

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.glooboo.umessage.R
import com.glooboo.umessage.ui.icons.Symbols
import org.fossify.commons.extensions.copyToClipboard
import com.glooboo.umessage.helpers.SensitiveClip

/** TH-06: one-tap chips under a received bubble for the code, password or username in it (see [detectCopyTokens]). */
@Composable
internal fun CopyChips(body: String, modifier: Modifier = Modifier) {
    val tokens = remember(body) { detectCopyTokens(body) }
    if (tokens.isEmpty()) return
    val context = LocalContext.current
    Row(
        modifier = modifier.padding(top = 4.dp, start = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        tokens.forEach { token ->
            val label = stringResource(
                when (token.kind) {
                    CopyKind.Code -> R.string.thread_review_copy_code
                    CopyKind.Password -> R.string.thread_review_copy_password
                    CopyKind.Username -> R.string.thread_review_copy_username
                },
            )
            Surface(
                onClick = {
                    context.copyToClipboard(token.value)
                    // A code or password is marked sensitive and leaves the clipboard after a minute (I15, I25).
                    SensitiveClip.mark(context, token.value, clear = token.kind != CopyKind.Username)
                },
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.heightIn(min = 32.dp),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Symbols.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.size(6.dp))
                    Text(label, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

/** U36: the stable id of the newest received message with something to copy; [rows] are newest first. */
internal fun newestCopyChipId(rows: List<ThreadRow>): Long? = rows.asSequence()
    .filterIsInstance<ThreadRow.Bubble>()
    .map { it.message }
    .firstOrNull { it.isReceivedMessage() && detectCopyTokens(it.body).isNotEmpty() }
    ?.getStableId()
