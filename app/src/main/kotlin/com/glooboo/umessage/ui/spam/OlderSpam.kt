package com.glooboo.umessage.ui.spam

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.glooboo.umessage.R
import com.glooboo.umessage.spam.BlockedMessage
import com.glooboo.umessage.ui.components.UMessageScaffold
import com.glooboo.umessage.ui.components.UMessageTopBar
import com.glooboo.umessage.ui.components.displayNumber
import com.glooboo.umessage.ui.components.formatListTimeStyled
import com.glooboo.umessage.ui.components.isolateNumbers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/** U20: the older spam still kept only in uMessage: held rows the message migration has not moved yet. */
object OlderSpam {
    fun of(blocked: List<BlockedMessage>): List<BlockedMessage> =
        blocked.filter { it.migratedMsgId == null }.sortedByDescending { it.receivedAt }

    /** How many held rows [of] lists, without sorting them (C-7). */
    fun count(blocked: List<BlockedMessage>): Int = blocked.count { it.migratedMsgId == null }
}

/**
 * U20: "Older spam kept only in uMessage". While the message migration waits (uMessage is not the default SMS app),
 * the held texts that were not moved to the phone's store yet can be read here: sender, date and text. It is read
 * only on purpose: the old screen with its restore buttons is not reused, and nothing can be deleted here until the
 * migration is done.
 */
@Composable
fun OlderSpamRoute(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val older = remember(context) {
        runCatching { SpamRepositoryProvider.get(context).blocked }.getOrNull()
            ?.map { OlderSpam.of(it) }
            ?.catch { emit(emptyList()) }
    }
    val messages by (older ?: flowOf(emptyList()))
        .collectAsStateWithLifecycle(initialValue = emptyList())
    OlderSpamScreen(messages = messages, onBack = onBack, modifier = modifier)
}

@Composable
internal fun OlderSpamScreen(messages: List<BlockedMessage>, onBack: () -> Unit, modifier: Modifier = Modifier) {
    UMessageScaffold(
        modifier = modifier,
        topBar = { UMessageTopBar(title = stringResource(R.string.pt_older_spam_title), onBack = onBack) },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item(key = "hint") {
                Text(
                    stringResource(R.string.pt_older_spam_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
            items(messages, key = { it.id }) { message ->
                OlderSpamRow(message)
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun OlderSpamRow(message: BlockedMessage) {
    val context = LocalContext.current
    val sender = remember(message.address) { displayNumber(context, message.address) }
    val date = remember(message.receivedAt) { formatListTimeStyled(context, message.receivedAt) }
    val text = if (message.hasText) message.body else stringResource(R.string.mms)
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp).semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                isolateNumbers(sender),
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(date, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            isolateNumbers(text),
            style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
