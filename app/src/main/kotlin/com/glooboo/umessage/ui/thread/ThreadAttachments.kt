package com.glooboo.umessage.ui.thread

import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.glooboo.umessage.R
import com.glooboo.umessage.extensions.getFileSizeFromUri
import com.glooboo.umessage.extensions.isAudioMimeType
import com.glooboo.umessage.extensions.isCalendarMimeType
import com.glooboo.umessage.extensions.isPdfMimeType
import com.glooboo.umessage.extensions.isZipMimeType
import com.glooboo.umessage.helpers.AudioPlayerManager
import com.glooboo.umessage.helpers.parseNameFromVCard
import com.glooboo.umessage.helpers.parseVCardFromUri
import com.glooboo.umessage.models.AttachmentSelection
import com.glooboo.umessage.ui.icons.Symbols
import ezvcard.VCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.fossify.commons.extensions.formatSize

/** How a message attachment reacts to taps: while a selection is active a tap selects instead of opening. */
class AttachmentGestures(
    val selecting: Boolean,
    val onToggleSelect: () -> Unit,
    val onLongClick: () -> Unit,
)

private val CardShape = RoundedCornerShape(14.dp)
private val ThumbSize = 76.dp
private const val MAX_MEDIA_HEIGHT_RATIO = 3f
private const val MS_PER_SECOND = 1000
private const val SECONDS_PER_MINUTE = 60

internal fun formatMs(ms: Int): String {
    val totalSeconds = ms / MS_PER_SECOND
    return "%d:%02d".format(totalSeconds / SECONDS_PER_MINUTE, totalSeconds % SECONDS_PER_MINUTE)
}

private fun iconForMimeType(mimeType: String) = when {
    mimeType.isAudioMimeType() -> Symbols.AudioFile
    mimeType.isCalendarMimeType() -> Symbols.CalendarMonth
    mimeType.isPdfMimeType() -> Symbols.PictureAsPdf
    mimeType.isZipMimeType() -> Symbols.FolderZip
    else -> Symbols.Description
}

/** Image or video preview inside a message bubble. Hidden when the file cannot be decoded, like the old list. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageMedia(
    uri: Uri,
    isVideo: Boolean,
    width: Dp,
    gestures: AttachmentGestures,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val density = context.resources.displayMetrics.density
    val widthPx = (width.value * density).toInt().coerceAtLeast(1)
    val load by rememberGlideBitmap(uri, widthPx, (widthPx * MAX_MEDIA_HEIGHT_RATIO).toInt())
    val result = load
    if (result == ImageLoad.Failed) return

    val bitmap = (result as? ImageLoad.Loaded)?.bitmap
    val key = uri.toString()
    // TU-25: drawn at the size learnt the last time, so the chat does not jump when the picture comes in.
    val aspect = mediaAspect(bitmap?.width, bitmap?.height, remember(key) { MediaSizes.cached(key) })
    if (bitmap != null) {
        LaunchedEffect(key, bitmap) {
            withContext(Dispatchers.IO) { MediaSizes.store(context.applicationContext, key, bitmap.width, bitmap.height) }
        }
    }
    val description = stringResource(if (isVideo) R.string.thread_video_attachment else R.string.thread_image_attachment)
    Box(
        modifier = modifier
            .width(width)
            .aspectRatio(aspect.coerceAtLeast(1f / MAX_MEDIA_HEIGHT_RATIO))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .semantics { contentDescription = description }
            .combinedClickable(
                onClick = { if (gestures.selecting) gestures.onToggleSelect() else onOpen() },
                onLongClick = gestures.onLongClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (isVideo) {
            Icon(
                Symbols.PlayCircle,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier
                    .size(56.dp)
                    .background(Color.Black.copy(alpha = 0.35f), CircleShape),
            )
        }
    }
}

@Composable
private fun AttachmentCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = CardShape,
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        content = content,
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DocumentAttachment(
    uri: Uri,
    title: String,
    mimeType: String,
    gestures: AttachmentGestures,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    onRemove: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val size by produceState<String?>(null, uri) {
        value = withContext(Dispatchers.IO) {
            try {
                context.getFileSizeFromUri(uri).formatSize()
            } catch (_: Exception) {
                null
            }
        }
    }
    AttachmentCard(
        modifier = modifier.combinedClickable(
            onClick = { if (gestures.selecting) gestures.onToggleSelect() else onOpen() },
            onLongClick = gestures.onLongClick,
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    iconForMimeType(mimeType),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(22.dp),
                )
            }
            Column(modifier = Modifier.weight(1f, fill = false)) {
                Text(
                    title.ifBlank { uri.lastPathSegment.orEmpty() },
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                size?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (onRemove != null) RemoveButton(onRemove)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun VCardAttachment(
    uri: Uri,
    gestures: AttachmentGestures,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    onRemove: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val vCards by produceState<List<VCard>?>(null, uri) {
        value = suspendCancellableCoroutine { cont ->
            parseVCardFromUri(context, uri) { if (cont.isActive) cont.resumeWith(Result.success(it)) }
        }
    }
    val loaded = vCards
    val name = loaded?.firstOrNull()?.parseNameFromVCard()
    AttachmentCard(
        modifier = modifier.combinedClickable(
            onClick = { if (gestures.selecting) gestures.onToggleSelect() else onOpen() },
            onLongClick = gestures.onLongClick,
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            LetterAvatar(label = name.orEmpty(), size = 40.dp)
            Column(modifier = Modifier.weight(1f, fill = false)) {
                when {
                    loaded == null -> CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    loaded.isEmpty() -> Text(
                        stringResource(R.string.thread_contact_unreadable),
                        style = MaterialTheme.typography.bodyMedium,
                    )

                    else -> {
                        Text(
                            name.orEmpty(),
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (loaded.size > 1) {
                            val others = loaded.size - 1
                            Text(
                                pluralStringResource(R.plurals.and_other_contacts, others, others),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            if (onRemove != null) RemoveButton(onRemove)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AudioAttachment(
    uri: Uri,
    gestures: AttachmentGestures,
    onOpenWith: () -> Unit,
    modifier: Modifier = Modifier,
    onRemove: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    var playing by remember(uri) { mutableStateOf(false) }
    var position by remember(uri) { mutableIntStateOf(0) }
    var total by remember(uri) { mutableIntStateOf(0) }
    val openWith by rememberUpdatedState(onOpenWith)
    val errorText = stringResource(R.string.thread_audio_error)

    DisposableEffect(uri) {
        val listener = object : AudioPlayerManager.AudioPlayerListener {
            override fun onPlaybackStateChanged(isPlaying: Boolean) {
                playing = isPlaying
            }

            override fun onProgressUpdated(positionMs: Int) {
                position = positionMs
            }

            override fun onPlaybackCompleted() {
                position = 0
            }

            override fun onPlaybackError() {
                position = 0
                Toast.makeText(context, errorText, Toast.LENGTH_SHORT).show()
                openWith()
            }
        }
        if (!AudioPlayerManager.attachListener(uri, listener)) {
            playing = false
            position = 0
        }
        onDispose { AudioPlayerManager.detachListener(listener) }
    }
    LaunchedEffect(uri) { AudioPlayerManager.getDurationMs(uri, context) { total = it } }

    val toggle = {
        if (gestures.selecting) gestures.onToggleSelect() else AudioPlayerManager.togglePlay(uri, position, context)
    }
    AttachmentCard(
        modifier = modifier.combinedClickable(onClick = toggle, onLongClick = gestures.onLongClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            FilledIconButton(onClick = toggle, modifier = Modifier.size(40.dp)) {
                Icon(
                    if (playing) Symbols.Pause else Symbols.PlayArrow,
                    contentDescription = stringResource(if (playing) R.string.pause else R.string.play),
                )
            }
            Column(modifier = Modifier.width(170.dp)) {
                Slider(
                    value = position.coerceAtMost(total.coerceAtLeast(1)).toFloat(),
                    onValueChange = {
                        position = it.toInt()
                        AudioPlayerManager.seekTo(uri, position)
                    },
                    valueRange = 0f..total.coerceAtLeast(1).toFloat(),
                    enabled = !gestures.selecting && total > 0,
                )
                Text(
                    formatMs(if (playing || position > 0) position else total),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (onRemove != null) RemoveButton(onRemove)
        }
    }
}

@Composable
private fun RemoveButton(onRemove: () -> Unit) {
    // Default IconButton size keeps the 48dp touch target; only the glyph is small.
    IconButton(onClick = onRemove) {
        Icon(
            Symbols.Close,
            contentDescription = stringResource(R.string.thread_remove_attachment),
            modifier = Modifier.size(18.dp),
        )
    }
}

/** Thumbnail of a media file the user picked, shown above the text field before sending. */
@Composable
fun ComposerMediaThumbnail(
    attachment: AttachmentSelection,
    isVideo: Boolean,
    onOpen: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalContext.current.resources.displayMetrics.density
    val sizePx = (ThumbSize.value * density).toInt()
    val load by rememberGlideBitmap(attachment.uri, sizePx, sizePx)
    val bitmap = (load as? ImageLoad.Loaded)?.bitmap
    Box(
        modifier = modifier
            .size(ThumbSize)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Surface(onClick = onOpen, color = Color.Transparent, modifier = Modifier.fillMaxSize()) {
            if (bitmap != null) {
                Image(
                    bitmap.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        if (attachment.isPending) {
            CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
        } else if (isVideo) {
            Icon(Symbols.PlayCircle, contentDescription = null, tint = Color.White, modifier = Modifier.size(32.dp))
        }
        Surface(
            onClick = onRemove,
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            // No size here: Surface(onClick) pads the 24dp circle to a 48dp touch target.
            modifier = Modifier.align(Alignment.TopEnd),
        ) {
            Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                Icon(
                    Symbols.Close,
                    contentDescription = stringResource(R.string.thread_remove_attachment),
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}
