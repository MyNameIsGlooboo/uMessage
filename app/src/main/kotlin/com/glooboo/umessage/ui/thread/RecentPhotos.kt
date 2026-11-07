package com.glooboo.umessage.ui.thread

import com.glooboo.umessage.helpers.recentPhotosAvailable
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.glooboo.umessage.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val PhotoSize = 84.dp

/**
 * The newest pictures above the attach sheet's entries (I69). With permission (all photos, or the ones the user chose
 * on Android 14) a tap adds the picture to the message; without it one button asks, and the "Photo" entry stays
 * the way in that needs no permission. [accessVersion] changes when the permission prompt was answered.
 */
@Composable
internal fun RecentPhotosRow(accessVersion: Int, onPick: (Uri) -> Unit, onAllow: () -> Unit) {
    val context = LocalContext.current
    // The Google Play build declares no photo-library permission: no row, no prompt (the Photo entry still works).
    if (!context.recentPhotosAvailable()) return
    val access = remember(accessVersion) { RecentPhotosAccess.current(context) }
    if (access == PhotoAccess.None) {
        TextButton(onClick = onAllow, modifier = Modifier.padding(horizontal = 8.dp)) {
            Text(stringResource(R.string.i69_show_recent))
        }
        return
    }
    val photos by produceState(emptyList<Uri>(), access, accessVersion) {
        value = withContext(Dispatchers.IO) { RecentPhotosLoader(context.contentResolver).load() }
    }
    if (photos.isEmpty()) return
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(photos, key = { it.toString() }) { uri -> RecentPhoto(uri, onClick = { onPick(uri) }) }
    }
}

@Composable
private fun RecentPhoto(uri: Uri, onClick: () -> Unit) {
    val density = LocalContext.current.resources.displayMetrics.density
    val sizePx = (PhotoSize.value * density).toInt()
    val load by rememberGlideBitmap(uri, sizePx, sizePx)
    val bitmap = (load as? ImageLoad.Loaded)?.bitmap
    val description = stringResource(R.string.i69_photo_description)
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .size(PhotoSize)
            .semantics { contentDescription = description },
    ) {
        if (bitmap != null) {
            Image(
                bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(PhotoSize),
            )
        }
    }
}
