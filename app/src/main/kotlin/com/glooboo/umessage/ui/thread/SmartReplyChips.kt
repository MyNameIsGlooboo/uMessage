package com.glooboo.umessage.ui.thread

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.glooboo.umessage.R
import com.glooboo.umessage.helpers.smartreply.SmartReply
import com.glooboo.umessage.helpers.smartreply.SmartReplyMatcher
import com.glooboo.umessage.helpers.smartreply.SmartReplyPolicy
import com.glooboo.umessage.helpers.smartreply.SmartReplyTables
import com.glooboo.umessage.helpers.smartreply.smartReplyPrefs
import com.glooboo.umessage.models.Message
import com.glooboo.umessage.ui.icons.Symbols
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The reply chosen for one received message; [messageId] ties the row's "hide" to that message. */
private class Suggestion(val messageId: Long, val reply: SmartReply)

/**
 * I59: up to three suggested replies above the text field, for the newest received text, while the switch in Settings
 * is on. A tap fills the field and never sends. The row goes when the user types, when a newer text arrives, or on
 * "hide" (kept until a newer text arrives). Nothing is shown unless [SmartReplyMatcher] is sure.
 */
@Composable
internal fun SmartReplyChips(vm: ThreadViewModel, modifier: Modifier = Modifier) {
    if (LocalInspectionMode.current) return
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(context.smartReplyPrefs.enabled) }
    DisposableEffect(context) {
        val stop = context.smartReplyPrefs.observe { enabled = it }
        onDispose(stop)
    }
    if (enabled) SmartReplyRow(vm, modifier)
}

@Composable
private fun SmartReplyRow(vm: ThreadViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val matcher by produceState<SmartReplyMatcher?>(null) {
        value = withContext(Dispatchers.Default) { SmartReplyTables.matcher(context) }
    }
    val language = LocalConfiguration.current.locales[0].language
    val suggestion = remember(vm.items, vm.linksTrusted, vm.isGroup, matcher) {
        matcher?.let { suggestionFor(vm, it, language) }
    }
    var hiddenFor by rememberSaveable { mutableStateOf<Long?>(null) }
    val hidden = suggestion?.messageId == hiddenFor || vm.replyDisabled || vm.isRecycleBin
    if (suggestion == null || hidden || vm.text.text.isNotEmpty()) return
    val direction = if (suggestion.reply.rightToLeft) LayoutDirection.Rtl else LayoutDirection.Ltr
    CompositionLocalProvider(LocalLayoutDirection provides direction) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .padding(start = 4.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LazyRow(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(suggestion.reply.replies) { reply ->
                    SuggestionChip(
                        onClick = {
                            vm.insertAtCursor(reply)
                            vm.focusInput++
                        },
                        label = { Text(reply, maxLines = 1) },
                    )
                }
            }
            IconButton(onClick = { hiddenFor = suggestion.messageId }, modifier = Modifier.size(40.dp)) {
                Icon(
                    Symbols.Close,
                    contentDescription = stringResource(R.string.smart_replies_hide),
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/**
 * The newest text, when it may be answered with chips: it was received, it is a plain text (no picture), and the
 * sender is a saved contact or someone the user has written to. Null otherwise, or when the matcher is not sure.
 */
private fun suggestionFor(vm: ThreadViewModel, matcher: SmartReplyMatcher, language: String): Suggestion? {
    val texts = vm.items.filterIsInstance<Message>().filter { !it.isScheduled }
    val newest = texts.lastOrNull()?.takeIf { it.isReceivedMessage() && !it.isMMS } ?: return null
    val candidate = SmartReplyPolicy.Candidate(
        address = newest.senderPhoneNumber,
        body = newest.body,
        receivedMillis = newest.millis(),
        isReceived = true,
        isGroup = vm.isGroup,
        knownSender = vm.linksTrusted || texts.any { !it.isReceivedMessage() },
    )
    if (!SmartReplyPolicy.allows(candidate, System.currentTimeMillis())) return null
    return matcher.match(newest.body, language, newest.id)?.let { Suggestion(newest.id, it) }
}
