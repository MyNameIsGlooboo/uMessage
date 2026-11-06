package com.glooboo.umessage.ui.spam

import android.content.res.Resources
import android.graphics.BitmapFactory
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.glooboo.umessage.R
import com.glooboo.umessage.spam.BlockedMessage
import com.glooboo.umessage.spam.MmsMedia
import com.glooboo.umessage.spam.mms.BlockedMmsStore
import com.glooboo.umessage.ui.components.InfoChip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What the row says is kept of a picture message (SU-17), or null for an SMS. */
@StringRes
fun mmsMediaLabel(message: BlockedMessage, now: Long = System.currentTimeMillis()): Int? {
    if (!message.isMms) return null
    return when (message.mediaAt(now)) {
        MmsMedia.STORED -> if (message.hasStoredMedia) R.string.spam_mms_stored else R.string.spam_mms_missing
        MmsMedia.DOWNLOADING -> R.string.spam_mms_downloading
        else -> R.string.spam_mms_missing
    }
}

/** The text a row shows: the message text, or for a picture message without text what is kept of it. */
fun blockedBodyText(resources: Resources, message: BlockedMessage): String {
    if (!message.isMms || message.hasText) return message.body
    return mmsMediaLabel(message)?.let(resources::getString) ?: message.body
}

/** A small label on a picture message's row: kept, downloading, or not downloaded. */
@Composable
fun MmsMediaChip(message: BlockedMessage) {
    val label = mmsMediaLabel(message) ?: return
    InfoChip(stringResource(label))
}

/** The thumbnail of a kept picture message's first picture, if it has one. Loaded off the main thread. */
@Composable
fun MmsThumbnail(message: BlockedMessage, modifier: Modifier = Modifier) {
    val name = message.mediaFile?.takeIf { message.hasStoredMedia } ?: return
    val context = LocalContext.current
    val bitmap by produceState<ImageBitmap?>(null, name) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                BlockedMmsStore.thumbnailFile(context, name)?.takeIf { it.isFile }
                    ?.let { BitmapFactory.decodeFile(it.path)?.asImageBitmap() }
            }.getOrNull()
        }
    }
    val image = bitmap ?: return
    Image(
        bitmap = image,
        contentDescription = stringResource(R.string.spam_mms_thumbnail),
        contentScale = ContentScale.Crop,
        modifier = modifier.size(96.dp).clip(RoundedCornerShape(8.dp)),
    )
}
