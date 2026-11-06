package com.glooboo.umessage.ui.details

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.glooboo.umessage.R
import com.glooboo.umessage.models.Message
import com.glooboo.umessage.spam.TextNormalizer
import com.glooboo.umessage.ui.components.UMessageScaffold
import com.glooboo.umessage.ui.components.UMessageTopBar
import com.glooboo.umessage.ui.components.formatListTimeStyled
import com.glooboo.umessage.ui.icons.Symbols
import com.glooboo.umessage.ui.main.highlightRanges
import com.glooboo.umessage.ui.main.isSearchable
import com.glooboo.umessage.ui.main.searchExcerpt

/**
 * CL-23: the messages of one conversation whose text holds [query], newest first. Read through [TextNormalizer.fold] as
 * the main search is, so Persian and Latin digits and Arabic letter forms match either way. Scheduled drafts are left
 * out; a query shorter than the main search's minimum matches nothing.
 */
fun searchConversation(messages: List<Message>, query: String): List<Message> {
    if (!isSearchable(query)) return emptyList()
    val wanted = TextNormalizer.fold(query)
    return messages
        .filter { !it.isScheduled && TextNormalizer.fold(it.body).contains(wanted, ignoreCase = true) }
        .sortedWith(compareByDescending<Message> { it.date }.thenByDescending { it.id })
}

/**
 * CL-23: "Search in conversation" from Details: a field and the matching messages of this chat. [messages] is null
 * while they load. Tapping a hit hands it to [onOpen], which opens the chat at that message.
 */
@Composable
fun ConversationSearchScreen(
    messages: List<Message>?,
    onBack: () -> Unit,
    onOpen: (Message) -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val hits = remember(messages, query) { messages?.let { searchConversation(it, query) }.orEmpty() }
    val focus = remember { FocusRequester() }
    BackHandler(onBack = onBack)
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    UMessageScaffold(
        modifier = modifier,
        topBar = { UMessageTopBar(title = stringResource(R.string.inbox_details_search), onBack = onBack) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()).imePadding()) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                placeholder = { Text(stringResource(R.string.inbox_details_search_hint)) },
                leadingIcon = { Icon(Symbols.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(Symbols.Close, contentDescription = stringResource(R.string.main_search_clear))
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .focusRequester(focus),
            )
            when {
                !isSearchable(query) -> SearchNote(stringResource(R.string.main_search_type_more))
                messages != null && hits.isEmpty() -> SearchNote(stringResource(R.string.main_search_no_results))
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = padding.calculateBottomPadding() + 8.dp),
                ) {
                    items(hits, key = { (if (it.isMMS) "m" else "s") + it.id }) { message ->
                        HitRow(message, query, onOpen)
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchNote(text: String) {
    Box(Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 32.dp)) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun HitRow(message: Message, query: String, onOpen: (Message) -> Unit) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val date = remember(message.date) { formatListTimeStyled(context, message.date * MILLIS_PER_SECOND) }
    val highlight = SpanStyle(color = colors.primary, fontWeight = FontWeight.Bold)
    val excerpt = remember(message.body, query) { searchExcerpt(message.body, query) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpen(message) }
            .heightIn(min = 56.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(date, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant, maxLines = 1)
        Text(
            highlighted(excerpt, query, highlight),
            style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content),
            color = colors.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun highlighted(text: String, query: String, style: SpanStyle): AnnotatedString = buildAnnotatedString {
    append(text)
    highlightRanges(text, query).forEach { addStyle(style, it.first, it.last + 1) }
}

private const val MILLIS_PER_SECOND = 1000L
