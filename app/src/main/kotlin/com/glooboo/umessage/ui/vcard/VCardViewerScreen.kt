package com.glooboo.umessage.ui.vcard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.glooboo.umessage.R
import com.glooboo.umessage.models.VCardPropertyWrapper
import com.glooboo.umessage.models.VCardWrapper
import com.glooboo.umessage.ui.components.EmptyState
import com.glooboo.umessage.ui.components.LoadingState
import com.glooboo.umessage.ui.components.UMessageScaffold
import com.glooboo.umessage.ui.components.UMessageTopBar
import com.glooboo.umessage.ui.conversations.ContactAvatar
import com.glooboo.umessage.ui.icons.Symbols
import ezvcard.property.Email
import ezvcard.property.Telephone

/**
 * Contacts inside a shared vCard. A single contact is shown open; several are collapsed rows that expand to
 * their phone numbers, emails and other details. Numbers and emails act when tapped.
 */
@Composable
fun VCardViewerScreen(
    contacts: List<VCardWrapper>?,
    onBack: () -> Unit,
    onAddContact: (() -> Unit)?,
    onProperty: (VCardPropertyWrapper) -> Unit,
    isActionable: (VCardPropertyWrapper) -> Boolean,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable(stateSaver = ExpandedSaver) { mutableStateOf(emptySet<Int>()) }
    UMessageScaffold(
        modifier = modifier,
        topBar = {
            UMessageTopBar(title = stringResource(R.string.screens_vcard_title), onBack = onBack) {
                if (onAddContact != null) {
                    IconButton(onClick = onAddContact) {
                        Icon(Symbols.PersonAdd, contentDescription = stringResource(R.string.screens_vcard_add_contact))
                    }
                }
            }
        },
    ) { padding ->
        when {
            contacts == null -> LoadingState(Modifier.padding(top = padding.calculateTopPadding()))
            contacts.isEmpty() -> Box(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
                EmptyState(
                    icon = Symbols.PersonOff,
                    title = stringResource(R.string.screens_vcard_empty),
                    body = "",
                    modifier = Modifier.align(Alignment.Center),
                )
            }
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 8.dp),
            ) {
                val single = contacts.size == 1
                contacts.forEachIndexed { index, contact ->
                    val open = single || index in expanded
                    item(key = "contact-$index") {
                        ContactHeader(
                            contact = contact,
                            open = open,
                            collapsible = !single,
                            onToggle = { expanded = if (index in expanded) expanded - index else expanded + index },
                        )
                    }
                    if (open) {
                        itemsIndexed(contact.properties, key = { propertyIndex, _ -> "property-$index-$propertyIndex" }) { _, property ->
                            PropertyRow(property, actionable = isActionable(property), onClick = { onProperty(property) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ContactHeader(contact: VCardWrapper, open: Boolean, collapsible: Boolean, onToggle: () -> Unit) {
    val name = contact.fullName.orEmpty()
    val state = stringResource(if (open) R.string.screens_vcard_collapse else R.string.screens_vcard_expand)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .then(
                if (collapsible) {
                    Modifier
                        .clickable(role = Role.Button, onClickLabel = state, onClick = onToggle)
                        .semantics { stateDescription = state }
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        VCardAvatar(name = name, photo = contact.vCard.photos.firstOrNull()?.data)
        Text(
            text = name,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (collapsible) {
            Icon(
                if (open) Symbols.ExpandLess else Symbols.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun VCardAvatar(name: String, photo: ByteArray?) {
    val bitmap = remember(photo) {
        photo?.let {
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(it, 0, it.size, bounds)
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= 256 && bounds.outHeight / (sample * 2) >= 256) sample *= 2
                BitmapFactory.decodeByteArray(it, 0, it.size, BitmapFactory.Options().apply { inSampleSize = sample })
            }.getOrNull()?.asImageBitmap()
        }
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(48.dp).clip(CircleShape),
        )
    } else {
        ContactAvatar(title = name, photoUri = "")
    }
}

/** The expanded contact indexes survive rotation. */
private val ExpandedSaver = Saver<Set<Int>, ArrayList<Int>>(
    save = { ArrayList(it) },
    restore = { it.toSet() },
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PropertyRow(property: VCardPropertyWrapper, actionable: Boolean, onClick: () -> Unit) {
    val context = LocalContext.current
    val copied = stringResource(R.string.screens_vcard_copied)
    val actionLabel = stringResource(
        if (property.property is Email) R.string.screens_vcard_email_action else R.string.convx_details_call,
    )
    val copyLabel = stringResource(R.string.screens_copy)
    val copy = { copyToClipboard(context, property.value, copied) }
    val tappable = property.property is Telephone || property.property is Email
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .then(
                if (actionable) {
                    Modifier.combinedClickable(
                        role = Role.Button,
                        onClickLabel = actionLabel,
                        onClick = onClick,
                        onLongClickLabel = copyLabel,
                        onLongClick = copy,
                    )
                } else if (tappable) {
                    Modifier.combinedClickable(onClick = {}, onLongClickLabel = copyLabel, onLongClick = copy)
                } else {
                    Modifier
                },
            )
            // Aligned with the contact name: 16dp gutter + 48dp avatar + 16dp gap.
            .padding(start = 80.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(property.value, style = MaterialTheme.typography.bodyLarge)
        if (property.type.isNotBlank()) {
            Text(
                property.type,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun copyToClipboard(context: Context, text: String, confirmation: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText(text, text))
    // Android 13+ shows its own "copied" confirmation.
    if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(context, confirmation, Toast.LENGTH_SHORT).show()
    }
}
