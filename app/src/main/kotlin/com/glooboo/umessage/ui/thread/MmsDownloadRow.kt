package com.glooboo.umessage.ui.thread

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.glooboo.umessage.R
import com.glooboo.umessage.messaging.MmsDownloadRules
import com.glooboo.umessage.messaging.MmsDownloadState
import com.glooboo.umessage.messaging.MmsDownloadTracker
import com.glooboo.umessage.ui.icons.Symbols

/**
 * Q40: the row for a picture message that was announced but never downloaded. The state comes from [tracker]
 * (running, failed) and the carrier's expiry; the tap goes to [onDownload] with the message id.
 */
@Composable
fun MmsDownloadRow(
    row: ThreadRow.MmsDownload,
    onDownload: (Long) -> Unit,
    tracker: MmsDownloadTracker = MmsDownloadTracker.shared,
    nowMillis: () -> Long = System::currentTimeMillis,
) {
    val entries by tracker.state.collectAsState()
    val state = MmsDownloadRules.stateOf(entries[row.messageId], row.expirySeconds, nowMillis())
    MmsDownloadBubble(state = state, onDownload = { onDownload(row.messageId) })
}

/** The bubble itself: starts at the leading edge (it is always a received message) and mirrors in right-to-left. */
@Composable
fun MmsDownloadBubble(state: MmsDownloadState, onDownload: () -> Unit, modifier: Modifier = Modifier) {
    val title = stringResource(R.string.mms_download_failed_title)
    val action = when (state) {
        MmsDownloadState.NotDownloaded -> stringResource(R.string.mms_tap_to_download)
        MmsDownloadState.Downloading -> stringResource(R.string.mms_downloading)
        MmsDownloadState.Failed -> stringResource(R.string.mms_download_again)
        MmsDownloadState.Expired -> stringResource(R.string.mms_expired)
    }
    val colors = MaterialTheme.colorScheme
    val canTap = state == MmsDownloadState.NotDownloaded || state == MmsDownloadState.Failed
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.Start,
    ) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = colors.surfaceContainerHigh,
            contentColor = colors.onSurface,
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Symbols.Image, contentDescription = null, modifier = Modifier.size(20.dp))
                    Text(title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 8.dp))
                }
                if (state == MmsDownloadState.Expired) {
                    Text(action, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                } else {
                    val label = stringResource(R.string.mms_download_action_desc)
                    TextButton(
                        onClick = onDownload,
                        enabled = canTap,
                        modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "$label. $action" },
                    ) {
                        if (state == MmsDownloadState.Downloading) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Text(action, Modifier.padding(start = 8.dp))
                        } else {
                            Icon(Symbols.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                            Text(action, Modifier.padding(start = 8.dp))
                        }
                    }
                }
            }
        }
    }
}
