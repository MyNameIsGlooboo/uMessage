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
import com.glooboo.umessage.spam.data.MigrationMark
import com.glooboo.umessage.spam.data.OlderSpamMover
import com.glooboo.umessage.spam.data.SpamProviderMigration
import com.glooboo.umessage.ui.components.UMessageScaffold
import com.glooboo.umessage.ui.components.UMessageTopBar
import com.glooboo.umessage.ui.components.displayNumber
import com.glooboo.umessage.ui.components.formatListTimeStyled
import com.glooboo.umessage.ui.components.isolateNumbers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/**
 * U20: the older spam still kept only in uMessage: held rows the message migration has not moved yet, rows it wrote
 * whose verify has not passed (negative mark, see MigrationMark): their old copy is the one that counts, and (S2-2)
 * done rows whose kept pictures did not go to the store ([picturesHere], SpamProviderMigration.picturesOnlyHere).
 */
object OlderSpam {
    fun of(blocked: List<BlockedMessage>, picturesHere: Set<Long> = emptySet()): List<BlockedMessage> =
        blocked.filter { notMoved(it) || it.id in picturesHere }.sortedByDescending { it.receivedAt }

    /** How many held rows [of] lists, without sorting them (C-7). */
    fun count(blocked: List<BlockedMessage>, picturesHere: Set<Long> = emptySet()): Int =
        blocked.count { notMoved(it) || it.id in picturesHere }

    private fun notMoved(row: BlockedMessage): Boolean = !MigrationMark.isDone(row.migratedMsgId)

    /**
     * F-2, S2-3: some listed rows will not move by themselves: the migration stopped, or rows are [stuck] (tries used
     * up, stuck after three runs; SpamProviderMigration.stuck), or their pictures stay here ([picturesHere]).
     */
    fun someStuck(
        messages: List<BlockedMessage>,
        stopped: Boolean,
        stuck: Set<Long> = emptySet(),
        picturesHere: Set<Long> = emptySet(),
    ): Boolean = messages.isNotEmpty() && (
        stopped ||
            messages.any { it.migrateTries >= OlderSpamMover.MAX_TRIES || it.id in stuck || it.id in picturesHere }
        )
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
            ?.map { all ->
                val here = SpamProviderMigration.picturesOnlyHere(context, all)
                val listed = OlderSpam.of(all, here)
                listed to OlderSpam.someStuck(
                    listed, SpamProviderMigration.stoppedForThisVersion(context),
                    SpamProviderMigration.stuck(context, listed), here,
                )
            }
            ?.flowOn(Dispatchers.IO)
            ?.catch { emit(emptyList<BlockedMessage>() to false) }
    }
    val shown by (older ?: flowOf(emptyList<BlockedMessage>() to false))
        .collectAsStateWithLifecycle(initialValue = emptyList<BlockedMessage>() to false)
    OlderSpamScreen(messages = shown.first, stuck = shown.second, onBack = onBack, modifier = modifier)
}

@Composable
internal fun OlderSpamScreen(
    messages: List<BlockedMessage>,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    stuck: Boolean = false,
) {
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
            if (stuck) {
                item(key = "stuck") {
                    Text(
                        stringResource(R.string.pt_older_spam_stuck),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
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
