package com.glooboo.umessage.ui.spam

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.glooboo.umessage.R
import com.glooboo.umessage.activities.ThreadActivity
import com.glooboo.umessage.extensions.getThreadId
import com.glooboo.umessage.helpers.THREAD_ID
import com.glooboo.umessage.helpers.THREAD_TITLE
import com.glooboo.umessage.ui.components.SenderAvatar
import com.glooboo.umessage.ui.components.formatListTime
import com.glooboo.umessage.ui.components.localizeDigits
import com.glooboo.umessage.ui.components.uiLocale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The messages moved to the inbox lately (BR-10), opened from "N restored" on the Blocked banner. A row opens the
 * conversation the message went back to. Only the sender and the times are kept, never the text.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RestoredSheet(entries: List<RestoredEntry>, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val maxHeight = LocalConfiguration.current.screenHeightDp.dp * SHEET_HEIGHT_FRACTION
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(Modifier.fillMaxWidth().heightIn(max = maxHeight).navigationBarsPadding()) {
            Text(
                stringResource(R.string.e_restored_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp).semantics { heading() },
            )
            Text(
                stringResource(if (entries.isEmpty()) R.string.e_restored_empty else R.string.e_restored_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            LazyColumn(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                items(entries, key = { "${it.restoredAt}-${it.receivedAt}-${it.address}" }) { entry ->
                    RestoredRow(entry) { scope.launch { openConversation(context, entry.address) } }
                }
            }
        }
    }
}

@Composable
private fun RestoredRow(entry: RestoredEntry, onOpen: () -> Unit) {
    val context = LocalContext.current
    val locale = uiLocale()
    val restoredLabel = remember(entry, locale) {
        localizeDigits(formatListTime(context, entry.restoredAt), locale)
    }
    val openLabel = stringResource(R.string.e_restored_open, entry.address)
    ListItem(
        modifier = Modifier.clickable(onClickLabel = openLabel, onClick = onOpen),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = { SenderAvatar(label = entry.address, selected = false) },
        headlineContent = { Text(entry.address, maxLines = 1) },
        supportingContent = { Text(stringResource(R.string.e_restored_when, restoredLabel)) },
    )
}

/** Opens the conversation with [address] (the restored message is in it). Looks the thread up off the main thread. */
private suspend fun openConversation(context: Context, address: String) {
    val threadId = withContext(Dispatchers.IO) { context.getThreadId(address) }
    if (threadId == 0L) return
    runCatching {
        context.startActivity(
            Intent(context, ThreadActivity::class.java)
                .putExtra(THREAD_ID, threadId)
                .putExtra(THREAD_TITLE, address),
        )
    }
}
