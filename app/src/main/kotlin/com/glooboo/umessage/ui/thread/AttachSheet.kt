package com.glooboo.umessage.ui.thread

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.glooboo.umessage.R
import com.glooboo.umessage.ui.icons.Symbols
import kotlinx.coroutines.launch

private class AttachEntry(val kind: AttachKind, val icon: ImageVector, val label: Int)

/** The "+" sheet's grid: Photo also picks videos, Camera asks photo or video. Schedule is a tile like the rest. */
private val AttachEntries = listOf(
    AttachEntry(AttachKind.Photo, Symbols.Image, R.string.thread_attach_photo),
    AttachEntry(AttachKind.Camera, Symbols.PhotoCamera, R.string.thread_review_attach_camera),
    AttachEntry(AttachKind.File, Symbols.AttachFile, R.string.thread_attach_file),
    AttachEntry(AttachKind.Contact, Symbols.Contacts, R.string.thread_attach_contact),
    AttachEntry(AttachKind.RecordAudio, ComposerIcons.Waveform, R.string.thread_review_attach_voice),
    AttachEntry(AttachKind.Location, ComposerIcons.Location, R.string.i52_attach_location),
    AttachEntry(AttachKind.SavedText, Symbols.TextFields, R.string.n06_attach_saved_text),
    AttachEntry(AttachKind.Schedule, Symbols.Schedule, R.string.w7_attach_schedule),
)

private const val ATTACH_COLUMNS = 4

/**
 * The attach sheet: the newest pictures on top (I69), then the entries. Picking an entry closes the sheet first, then
 * reports it; a picture is attached by [onPhoto], [onAllowPhotos] asks for the media permission.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AttachSheet(
    photoAccessVersion: Int,
    onDismiss: () -> Unit,
    onPick: (AttachKind) -> Unit,
    onPhoto: (Uri) -> Unit,
    onAllowPhotos: () -> Unit,
) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    fun closeThen(action: () -> Unit) {
        scope.launch { state.hide() }.invokeOnCompletion { action() }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 8.dp, end = 8.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            RecentPhotosRow(photoAccessVersion, onPick = { uri -> closeThen { onPhoto(uri) } }, onAllow = onAllowPhotos)
            AttachEntries.chunked(ATTACH_COLUMNS).forEach { rowEntries ->
                Row(modifier = Modifier.fillMaxWidth()) {
                    rowEntries.forEach { entry ->
                        AttachOption(
                            entry,
                            onClick = { closeThen { onPick(entry.kind) } },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    FillRow(ATTACH_COLUMNS - rowEntries.size)
                }
            }
        }
    }
}

/** Empty cells so a short last row keeps the width of the others. */
@Composable
private fun RowScope.FillRow(count: Int) {
    repeat(count) { Box(Modifier.weight(1f)) }
}

@Composable
private fun AttachOption(entry: AttachEntry, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(onClick = onClick, color = Color.Transparent, shape = RoundedCornerShape(16.dp), modifier = modifier) {
        Column(
            modifier = Modifier.padding(vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ) {
                Box(modifier = Modifier.size(56.dp), contentAlignment = Alignment.Center) {
                    Icon(entry.icon, contentDescription = null)
                }
            }
            Text(
                stringResource(entry.label),
                style = MaterialTheme.typography.labelMedium,
                maxLines = 2,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 2.dp),
                textAlign = TextAlign.Center,
            )
        }
    }
}
