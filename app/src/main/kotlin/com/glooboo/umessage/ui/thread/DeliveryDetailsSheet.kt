package com.glooboo.umessage.ui.thread

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.glooboo.umessage.R
import com.glooboo.umessage.extensions.config
import com.glooboo.umessage.models.Message
import com.glooboo.umessage.ui.components.displayNumber
import com.glooboo.umessage.ui.components.isolateNumbers
import com.glooboo.umessage.ui.components.rememberTimeLabel
import com.glooboo.umessage.ui.icons.Symbols
import com.glooboo.umessage.models.MessageKey
import com.glooboo.umessage.models.ThreadItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Q12: the sheet behind the delivery line of a sent group message, open while [ThreadViewModel.deliveryDetailsFor]
 * names a message. It follows the message on screen, so a delivery report that arrives while it is open updates it.
 */
@Composable
fun DeliveryDetailsHost(shownKey: MessageKey?, items: List<ThreadItem>, onClose: () -> Unit) {
    val key = shownKey ?: return
    val message = items.firstOrNull { it is Message && it.key == key } as? Message
    if (message == null) {
        // The message is gone (deleted, or the chat reloaded without it): close instead of showing nothing.
        LaunchedEffect(key, onClose) { onClose() }
        return
    }
    val context = LocalContext.current
    val reportsOn = context.config.enableDeliveryReports
    // Overall state at once; each person's own state replaces it as soon as the provider has answered.
    var details by remember(key) { mutableStateOf<DeliveryDetails?>(null) }
    LaunchedEffect(message, reportsOn) {
        details = withContext(Dispatchers.IO) { loadDeliveryDetails(context, message, reportsOn) }
    }
    val summary = CopyRow(message.type, message.status, message.millis())
    val shown = details
        ?: groupDeliveryDetails(groupPeopleOf(message), summary, message.isMMS, emptyMap(), reportsOn)
    DeliveryDetailsSheet(shown, onDismiss = onClose)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DeliveryDetailsSheet(details: DeliveryDetails, onDismiss: () -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state) {
        DeliveryDetailsContent(details)
    }
}

/** "Delivery details (3)", the carrier note for a picture message, then one line per person. */
@Composable
internal fun DeliveryDetailsContent(details: DeliveryDetails) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp)
            .padding(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            stringResource(R.string.delivery_details_title, details.recipients.size),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.semantics { heading() },
        )
        if (!details.perPerson) {
            Text(
                stringResource(R.string.delivery_details_mms_note),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        Column(Modifier.verticalScroll(rememberScrollState())) {
            details.recipients.forEach { RecipientLine(it) }
        }
    }
}

@Composable
private fun RecipientLine(line: RecipientDelivery) {
    val failed = line.state == RecipientState.NotSent || line.state == RecipientState.NotDelivered
    val color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    val name = isolateNumbers(displayNumber(line.person.name))
    val state = stateLabel(line.state)
    val time = line.timeMillis?.let { rememberTimeLabel(it) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.Center,
    ) {
        Text(name, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(stateIcon(line.state), contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
            Spacer(Modifier.size(4.dp))
            val text = if (time == null) state else state + stringResource(R.string.ui_separator) + time
            Text(text, style = MaterialTheme.typography.labelMedium, color = color)
        }
    }
}

@Composable
internal fun stateLabel(state: RecipientState): String = stringResource(
    when (state) {
        RecipientState.Pending -> R.string.thread_status_sending
        RecipientState.Sent -> R.string.thread_status_sent
        RecipientState.Delivered -> R.string.thread_status_delivered
        RecipientState.NotDelivered -> R.string.thread_status_delivery_failed
        RecipientState.NotSent -> R.string.delivery_state_not_sent
    },
)

private fun stateIcon(state: RecipientState): ImageVector = when (state) {
    RecipientState.Pending -> Symbols.Schedule
    RecipientState.Sent -> Symbols.Check
    RecipientState.Delivered -> Symbols.DoneAll
    RecipientState.NotDelivered, RecipientState.NotSent -> Symbols.Error
}
