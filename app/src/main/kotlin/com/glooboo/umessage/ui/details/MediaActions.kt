package com.glooboo.umessage.ui.details

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.glooboo.umessage.R
import com.glooboo.umessage.ui.components.LinkRow
import com.glooboo.umessage.ui.components.SectionHeader
import com.glooboo.umessage.ui.icons.ShortcutIcons
import com.glooboo.umessage.ui.thread.ImageLoad
import com.glooboo.umessage.ui.thread.linkHost
import com.glooboo.umessage.ui.thread.rememberGlideBitmap

/** What a tap on the media section does: open a picture, open a link (always through the link sheet), see all. */
class MediaActions(
    val onSeeAll: () -> Unit = {},
    val onOpenImage: (MediaImage) -> Unit = {},
    val onOpenLink: (String) -> Unit = {},
)

private const val RECENT_IMAGES = 6
private const val RECENT_LINKS = 3
private val ThumbSize = 72.dp

/**
 * I56: "Media and links" in a conversation's details: a row of the newest pictures, the last few links and a row that
 * opens the whole list. Nothing is shown for a chat without pictures and links.
 */
internal fun LazyListScope.mediaItems(media: ConversationMedia?, actions: MediaActions) {
    if (media == null || media.isEmpty) return
    item(key = "media-header") { SectionHeader(stringResource(R.string.i56_section)) }
    if (media.images.isNotEmpty()) {
        item(key = "media-images") {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val recent = media.images.take(RECENT_IMAGES)
                itemsIndexed(recent, key = { _, entry -> entry.attachment.uriString }) { index, image ->
                    MediaThumb(
                        image,
                        onClick = { actions.onOpenImage(image) },
                        modifier = Modifier.size(ThumbSize),
                        position = index + 1,
                        total = recent.size,
                    )
                }
            }
        }
    }
    items(media.links.take(RECENT_LINKS), key = { "media-link-" + it.url }) { link ->
        LinkRow(
            title = linkHost(link.url) ?: link.url,
            body = link.url,
            onClick = { actions.onOpenLink(link.url) },
            trailing = { Icon(ShortcutIcons.Link, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
        )
    }
    item(key = "media-all") {
        LinkRow(title = stringResource(R.string.i56_see_all), body = countsText(media), onClick = actions.onSeeAll)
    }
}

/** The number of pictures and the number of links, leaving out a part that is zero. */
@Composable
private fun countsText(media: ConversationMedia): String {
    val pictures = media.images.size
    val links = media.links.size
    val parts = listOfNotNull(
        pluralStringResource(R.plurals.i56_count_pictures, pictures, pictures).takeIf { pictures > 0 },
        pluralStringResource(R.plurals.i56_count_links, links, links).takeIf { links > 0 },
    )
    return parts.joinToString(stringResource(R.string.ui_separator))
}

/** One picture as a square thumbnail. A long press shows its message in the chat when [onLongClick] is given. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun MediaThumb(
    image: MediaImage,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    position: Int = 0,
    total: Int = 0,
) {
    val side = with(LocalDensity.current) { ThumbSize.roundToPx() }
    val load by rememberGlideBitmap(image.attachment.getUri(), side, side)
    val context = androidx.compose.ui.platform.LocalContext.current
    val tapToOpen = stringResource(R.string.i56_picture_description)
    val description = if (position > 0 && total > 0) {
        val date = android.text.format.DateUtils.formatDateTime(
            context, image.message.date * MILLIS_PER_SECOND, android.text.format.DateUtils.FORMAT_SHOW_DATE or
                android.text.format.DateUtils.FORMAT_ABBREV_MONTH,
        )
        stringResource(R.string.i56_picture_position, position, total, date)
    } else {
        tapToOpen
    }
    val showInChat = stringResource(R.string.i56_show_in_chat)
    Box(
        modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .combinedClickable(
                role = Role.Button,
                onClick = onClick,
                onLongClick = onLongClick,
                onLongClickLabel = showInChat.takeIf { onLongClick != null },
            )
            .semantics { contentDescription = description },
    ) {
        (load as? ImageLoad.Loaded)?.let {
            Image(it.bitmap.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
    }
}

private const val MILLIS_PER_SECOND = 1000L
