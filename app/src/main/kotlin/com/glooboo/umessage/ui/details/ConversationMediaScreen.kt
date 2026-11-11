package com.glooboo.umessage.ui.details

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.glooboo.umessage.R
import com.glooboo.umessage.models.Message
import com.glooboo.umessage.ui.components.LinkRow
import com.glooboo.umessage.ui.components.UMessageScaffold
import com.glooboo.umessage.ui.components.UMessageTopBar
import com.glooboo.umessage.ui.thread.linkHost

private const val PAGE = 60
private const val GRID_COLUMNS = 3

/**
 * I56: all pictures and all links of one conversation, newest first, 60 at a time. A picture opens in the viewer
 * and, with a long press, shows its message in the chat; a link opens the link sheet and has "Show in chat".
 */
@Composable
fun ConversationMediaScreen(
    media: ConversationMedia,
    onBack: () -> Unit,
    onOpenImage: (MediaImage) -> Unit,
    onOpenLink: (String) -> Unit,
    onShowInChat: (Message) -> Unit,
    modifier: Modifier = Modifier,
) {
    val firstTab = if (media.images.isEmpty() && media.links.isNotEmpty()) 1 else 0
    var tab by rememberSaveable { mutableIntStateOf(firstTab) }
    UMessageScaffold(
        modifier = modifier,
        topBar = { UMessageTopBar(title = stringResource(R.string.i56_section), onBack = onBack) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
            TabRow(selectedTabIndex = tab) {
                Tab(tab == 0, { tab = 0 }, text = { Text(stringResource(R.string.i56_pictures)) })
                Tab(tab == 1, { tab = 1 }, text = { Text(stringResource(R.string.i56_links)) })
            }
            val bottom = padding.calculateBottomPadding()
            if (tab == 0) {
                PicturesTab(media.images, bottom, onOpenImage, onShowInChat)
            } else {
                LinksTab(media.links, bottom, onOpenLink, onShowInChat)
            }
        }
    }
}

@Composable
private fun PicturesTab(
    images: List<MediaImage>,
    bottom: Dp,
    onOpen: (MediaImage) -> Unit,
    onShowInChat: (Message) -> Unit,
) {
    var shown by rememberSaveable { mutableIntStateOf(PAGE) }
    if (images.isEmpty()) {
        EmptyTab(R.string.i56_empty_pictures)
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(GRID_COLUMNS),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 8.dp, bottom = bottom + 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        itemsIndexed(images.take(shown), key = { _, entry -> entry.attachment.uriString }) { index, image ->
            MediaThumb(
                image,
                position = index + 1,
                total = images.size,
                onClick = { onOpen(image) },
                onLongClick = { onShowInChat(image.message) },
            )
        }
        if (images.size > shown) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "more") { ShowMore { shown += PAGE } }
        }
    }
}

@Composable
private fun LinksTab(
    links: List<MediaLink>,
    bottom: Dp,
    onOpen: (String) -> Unit,
    onShowInChat: (Message) -> Unit,
) {
    var shown by rememberSaveable { mutableIntStateOf(PAGE) }
    if (links.isEmpty()) {
        EmptyTab(R.string.i56_empty_links)
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottom + 8.dp)) {
        items(links.take(shown), key = { it.url }) { link ->
            LinkRow(
                title = linkHost(link.url) ?: link.url,
                body = link.url,
                onClick = { onOpen(link.url) },
                trailing = {
                    TextButton(onClick = { onShowInChat(link.message) }) {
                        Text(stringResource(R.string.i56_show_in_chat))
                    }
                },
            )
        }
        if (links.size > shown) item(key = "more") { ShowMore { shown += PAGE } }
    }
}

@Composable
private fun ShowMore(onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.i56_load_more))
    }
}

@Composable
private fun EmptyTab(textRes: Int) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            stringResource(textRes),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
