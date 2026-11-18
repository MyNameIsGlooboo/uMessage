package com.glooboo.umessage.ui.conversations

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.glooboo.umessage.R
import com.glooboo.umessage.ui.components.localizedNumber
import com.glooboo.umessage.ui.components.uiLocale

/** Test tag of the bar, so a test can tell whether it shows. */
internal const val MARK_READ_PROGRESS_TAG = "mark_read_progress"

/**
 * The strip above the list while "Mark all read" works: a label with the counts and a determinate bar. The label is
 * a polite live region, so a screen reader announces the counts as they change; the numbers carry the meaning, not
 * the colour.
 */
@Composable
fun MarkReadProgressBar(progress: MarkReadProgress, modifier: Modifier = Modifier) {
    val locale = uiLocale()
    val label = stringResource(
        R.string.mark_read_progress,
        localizedNumber(progress.done, locale),
        localizedNumber(progress.total, locale),
    )
    Column(
        modifier
            .fillMaxWidth()
            .testTag(MARK_READ_PROGRESS_TAG)
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LinearProgressIndicator(
            progress = { progress.fraction },
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )
    }
}
