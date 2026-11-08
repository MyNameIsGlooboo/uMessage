package com.glooboo.umessage.ui.thread

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.glooboo.umessage.R
import com.glooboo.umessage.ui.icons.Symbols

/**
 * The "Sent", "Delivered" or "Delivery failed" line under the newest sent message. [onDetails] is set for a group
 * message only (Q12): the line is then a 48dp button, announced as "Delivery details", that ends in an info icon and
 * opens each person's own state. A chat with one person passes null and keeps the plain line.
 */
@Composable
internal fun SentStatusLine(row: ThreadRow.Sent, onDetails: (() -> Unit)?) {
    val detailsLabel = stringResource(R.string.delivery_details_desc)
    val trailing = if (onDetails != null) Symbols.Info else null
    if (row.deliveryFailed) {
        StatusLine(
            text = stringResource(R.string.thread_status_delivery_failed),
            icon = Symbols.Error,
            color = MaterialTheme.colorScheme.error,
            onClick = onDetails,
            onClickLabel = detailsLabel,
            trailingIcon = trailing,
            trailingDescription = detailsLabel,
        )
    } else {
        StatusLine(
            text = stringResource(if (row.delivered) R.string.thread_status_delivered else R.string.thread_status_sent),
            icon = if (row.delivered) Symbols.DoneAll else Symbols.Check,
            onClick = onDetails,
            onClickLabel = detailsLabel,
            trailingIcon = trailing,
            trailingDescription = detailsLabel,
        )
    }
}
