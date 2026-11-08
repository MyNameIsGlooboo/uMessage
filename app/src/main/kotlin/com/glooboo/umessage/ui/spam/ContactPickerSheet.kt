package com.glooboo.umessage.ui.spam

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.glooboo.umessage.R
import com.glooboo.umessage.spam.engine.NumberNormalizer
import com.glooboo.umessage.spam.engine.defaultRegion
import com.glooboo.umessage.ui.components.ContactRow
import com.glooboo.umessage.ui.components.displayNumber
import com.glooboo.umessage.ui.components.isolateNumbers
import com.glooboo.umessage.ui.icons.Symbols
import com.glooboo.umessage.ui.newconversation.loadPhoneContacts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.fossify.commons.models.SimpleContact

/**
 * The "Choose from contacts" button: a 48dp outlined button with the contacts icon. It asks for the Contacts
 * permission first when that is off, then calls [onChoose].
 */
@Composable
fun ChooseFromContactsButton(onChoose: () -> Unit, modifier: Modifier = Modifier) {
    val access = rememberContactsAccess()
    OutlinedButton(
        onClick = { access.withAccess(onChoose) },
        modifier = modifier.heightIn(min = 48.dp).testTag("choose_from_contacts"),
    ) {
        Icon(Symbols.Contacts, contentDescription = null, modifier = Modifier.size(18.dp))
        Text(stringResource(R.string.p44_choose_contacts), modifier = Modifier.padding(start = 8.dp))
    }
}

/**
 * Searchable list of contacts with phone numbers. With [multi] several numbers can be ticked and added at once;
 * without it, a tap on a number hands that one number back. A contact with several numbers opens to list them.
 */
@Composable
fun ContactPickerSheet(
    multi: Boolean,
    onPick: (List<PickedNumber>) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val normalizer = remember(context) { NumberNormalizer { defaultRegion(context) } }
    val contacts by produceState<List<SimpleContact>?>(initialValue = null, context) {
        value = withContext(Dispatchers.IO) { loadPhoneContacts(context) }
    }
    ContactPickerContent(contacts, normalizer, multi, onPick, onDismiss)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ContactPickerContent(
    contacts: List<SimpleContact>?,
    normalizer: NumberNormalizer,
    multi: Boolean,
    onPick: (List<PickedNumber>) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by rememberSaveable { mutableStateOf("") }
    var expanded by rememberSaveable { mutableStateOf<Int?>(null) }
    var chosen by remember { mutableStateOf(listOf<PickedNumber>()) }
    val shown = remember(contacts, query) { ContactNumberPicks.matching(contacts.orEmpty(), query) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            Text(
                stringResource(if (multi) R.string.p44_picker_title_many else R.string.p44_picker_title_one),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            OutlinedTextField(
                value = query,
                onValueChange = { query = it.take(MAX_QUERY) },
                modifier = Modifier.fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 8.dp)
                    .testTag("contact_search"),
                label = { Text(stringResource(R.string.p44_picker_search)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
            )
            if (contacts != null && shown.isEmpty()) {
                Text(
                    stringResource(R.string.p44_picker_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                )
            }
            LazyColumn(Modifier.weight(1f, fill = false)) {
                items(shown, key = { it.contactId }) { contact ->
                    val own = remember(contact) { ContactNumberPicks.numbersOf(contact, normalizer) }
                    if (own.isNotEmpty()) {
                        ContactEntry(
                            contact = contact,
                            own = own,
                            multi = multi,
                            open = expanded == contact.contactId,
                            chosen = chosen,
                            onToggleOpen = {
                                expanded = if (expanded == contact.contactId) null else contact.contactId
                            },
                            onNumber = { pick ->
                                if (multi) {
                                    chosen = if (pick in chosen) chosen - pick else chosen + pick
                                } else {
                                    onPick(listOf(pick))
                                }
                            },
                        )
                    }
                }
            }
            if (multi) {
                PickerBar(chosen.size, onCancel = onDismiss, onAdd = { onPick(ContactNumberPicks.distinct(chosen)) })
            }
        }
    }
}

@Composable
private fun ContactEntry(
    contact: SimpleContact,
    own: List<PickedNumber>,
    multi: Boolean,
    open: Boolean,
    chosen: List<PickedNumber>,
    onToggleOpen: () -> Unit,
    onNumber: (PickedNumber) -> Unit,
) {
    val single = own.size == 1
    ContactRow(
        name = isolateNumbers(displayNumber(contact.name)),
        supporting = if (single) {
            isolateNumbers(displayNumber(own.first().number))
        } else {
            stringResource(R.string.p44_picker_several)
        },
        photoUri = contact.photoUri,
        onClick = if (single) ({ onNumber(own.first()) }) else onToggleOpen,
        modifier = Modifier.testTag("contact_row"),
        trailing = entryTrailing(single, multi, open, own.any { it in chosen }),
    )
    if (!single && open) {
        own.forEach { pick -> NumberLine(pick, multi, pick in chosen) { onNumber(pick) } }
    }
}

/** The tick of a one-number contact in multi mode, or the arrow of a contact whose numbers open below it. */
private fun entryTrailing(single: Boolean, multi: Boolean, open: Boolean, ticked: Boolean): (@Composable () -> Unit)? =
    when {
        single && multi -> ({ Checkbox(checked = ticked, onCheckedChange = null) })
        !single -> ({
            Icon(
                if (open) Symbols.ExpandLess else Symbols.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.let { if (ticked) it.primary else it.onSurfaceVariant },
            )
        })
        else -> null
    }

@Composable
private fun NumberLine(pick: PickedNumber, multi: Boolean, ticked: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("contact_number")
            .clickable(role = if (multi) Role.Checkbox else Role.Button, onClick = onClick)
            .padding(start = 72.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (multi) Checkbox(checked = ticked, onCheckedChange = null)
        Text(isolateNumbers(displayNumber(pick.number)), style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun PickerBar(count: Int, onCancel: () -> Unit, onAdd: () -> Unit) {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
    ) {
        TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.spam_cancel))
        }
        Button(
            onClick = onAdd,
            enabled = count > 0,
            modifier = Modifier.heightIn(min = 48.dp).testTag("contact_pick_confirm"),
        ) { Text(stringResource(R.string.p44_picker_add, count)) }
    }
}

private const val MAX_QUERY = 60
