package com.glooboo.umessage.ui.newconversation

import com.glooboo.umessage.ui.components.displayNumber
import com.glooboo.umessage.ui.components.isolateNumbers
import com.glooboo.umessage.ui.components.listSeparator
import com.glooboo.umessage.ui.components.uiLocale
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.material3.AssistChip
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.TextButton
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.onFocusChanged
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.delay
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.glooboo.umessage.R
import com.glooboo.umessage.models.Conversation
import com.glooboo.umessage.ui.components.ContactRow
import com.glooboo.umessage.ui.components.EmptyState
import com.glooboo.umessage.ui.components.LoadingState
import com.glooboo.umessage.ui.components.SectionHeader
import com.glooboo.umessage.ui.components.UMessageBottomSheet
import com.glooboo.umessage.ui.components.UMessageScaffold
import com.glooboo.umessage.ui.components.UMessageTopBar
import com.glooboo.umessage.ui.conversations.ContactAvatar
import com.glooboo.umessage.ui.icons.Symbols
import org.fossify.commons.extensions.getPhoneNumberTypeText
import org.fossify.commons.extensions.normalizeString
import org.fossify.commons.models.PhoneNumber
import org.fossify.commons.models.SimpleContact

/** Minimum characters before the typed text can be used as a raw number or name. */
private const val MIN_ADDRESS_LENGTH = 3

/** B11: after the window gains focus, the clipboard is looked at again this many times, this far apart. */
private const val CLIP_RECHECKS = 3
private const val CLIP_RECHECK_MS = 300L

/**
 * Recipient picker: a "To" field that accepts a number or a name, recent-conversation suggestions, and the
 * address book. Picking a contact with several numbers asks which one in a bottom sheet.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun NewConversationScreen(
    contacts: List<SimpleContact>,
    suggestions: List<SimpleContact>,
    loaded: Boolean,
    hasContactsPermission: Boolean,
    onBack: () -> Unit,
    onRequestPermission: () -> Unit,
    onSubmitAddress: (String) -> Unit,
    onPickNumber: (contact: SimpleContact, number: PhoneNumber) -> Unit,
    modifier: Modifier = Modifier,
    onStartGroup: (List<GroupRecipient>) -> Unit = {},
    shareMode: Boolean = false,
    recentChats: List<Conversation> = emptyList(),
    onPickRecent: (Conversation) -> Unit = {},
) {
    // Saveable so rotation or a fold keeps what was typed.
    var query by rememberSaveable { mutableStateOf("") }
    var dialpad by rememberSaveable { mutableStateOf(false) }
    var choosing by remember { mutableStateOf<SimpleContact?>(null) }
    // MC-13: "Create group" switches to picking several people; one tap on a contact still opens its chat at once.
    var groupMode by rememberSaveable { mutableStateOf(false) }
    var picked by rememberSaveable(stateSaver = GroupRecipientsSaver) { mutableStateOf(emptyList<GroupRecipient>()) }
    // CL-35: the keyboard does not open by itself; it would cover most of the contact list. Tapping the field opens it.
    val focus = remember { FocusRequester() }

    // ID-47: a "Paste number" chip while the clipboard holds text. Only the clip's type is looked at until it is
    // tapped.
    // B11: Android refuses clipboard reads from a window without focus, and the first composition runs before the
    // window has it. The clip is looked at on every resume, when the window gains focus (again a few times shortly
    // after, as the system can see the focus a moment later than the app), when the To field is focused and whenever
    // the clip changes.
    val context = LocalContext.current
    val clipboard = remember(context) { context.getSystemService(ClipboardManager::class.java) }
    var clipHasText by remember { mutableStateOf(clipboardHasText(clipboard)) }
    val checkClip = { clipHasText = clipboardHasText(clipboard) }
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    LifecycleResumeEffect(clipboard) {
        checkClip()
        onPauseOrDispose { }
    }
    LaunchedEffect(clipboard, windowFocused) {
        if (!windowFocused) return@LaunchedEffect
        checkClip()
        repeat(CLIP_RECHECKS) {
            if (clipHasText) return@LaunchedEffect
            delay(CLIP_RECHECK_MS)
            checkClip()
        }
    }
    DisposableEffect(clipboard) {
        val listener = ClipboardManager.OnPrimaryClipChangedListener { checkClip() }
        clipboard?.addPrimaryClipChangedListener(listener)
        onDispose { clipboard?.removePrimaryClipChangedListener(listener) }
    }
    val pasteNumber: () -> Unit = {
        val clip = clipboard?.primaryClip
        val text = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).coerceToText(context) else null
        val number = clipboardNumber(text)
        if (number != null) {
            query = number
        } else {
            Toast.makeText(context, R.string.w3c_new_paste_none, Toast.LENGTH_SHORT).show()
        }
    }

    val results = remember(contacts, query) { contactItems(filterContacts(contacts, query)) }
    val groups = remember(results) { letterGroups(results) }
    val shownRecents = remember(shareMode, recentChats, query) { shareTargetsFor(shareMode, recentChats, query) }
    val canSubmit = query.trim().length >= MIN_ADDRESS_LENGTH
    val typedNumber = if (canSubmit) typedPhoneNumber(query.trim()) else null
    val choose: (SimpleContact, PhoneNumber) -> Unit = { contact, number ->
        if (groupMode) {
            picked = toggleRecipient(picked, GroupRecipient(contact.name, number.normalizedNumber))
            query = ""
        } else {
            onPickNumber(contact, number)
        }
    }
    val pick: (SimpleContact) -> Unit = { contact ->
        val already = contact.phoneNumbers.firstOrNull { isPicked(picked, it.normalizedNumber) }
        when {
            // A second tap on a picked contact takes them out again, whichever of their numbers was picked.
            groupMode && already != null -> choose(contact, already)
            contact.phoneNumbers.isEmpty() -> Unit
            contact.phoneNumbers.size == 1 -> choose(contact, contact.phoneNumbers.first())
            else -> choosing = contact
        }
    }
    val submit: () -> Unit = {
        val typed = query.trim()
        if (groupMode) {
            val number = typedGroupRecipient(typed)
            if (number == null) {
                Toast.makeText(context, R.string.group_recipient_invalid, Toast.LENGTH_SHORT).show()
            } else {
                picked = addRecipient(picked, GroupRecipient(number, number))
                query = ""
            }
        } else {
            onSubmitAddress(typed)
        }
    }
    val leaveGroupMode = {
        groupMode = false
        picked = emptyList()
    }
    BackHandler(enabled = groupMode) { leaveGroupMode() }
    val isSelected: (SimpleContact) -> Boolean = { contact ->
        groupMode && contact.phoneNumbers.any { isPicked(picked, it.normalizedNumber) }
    }

    UMessageScaffold(
        modifier = modifier,
        topBar = {
            UMessageTopBar(
                title = stringResource(pickerTitle(groupMode, shareMode)),
                onBack = if (groupMode) leaveGroupMode else onBack,
                actions = {
                    if (groupMode) {
                        TextButton(enabled = picked.isNotEmpty(), onClick = { onStartGroup(picked) }) {
                            Text(stringResource(R.string.screens_new_group_next))
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()).imePadding()) {
            if (groupMode && picked.isNotEmpty()) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    picked.forEach { recipient ->
                        val removeLabel = stringResource(R.string.screens_new_group_remove, recipient.name)
                        InputChip(
                            selected = true,
                            onClick = { picked = picked - recipient },
                            label = {
                                Text(
                                    isolateNumbers(displayNumber(recipient.name)),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            trailingIcon = {
                                Icon(
                                    Symbols.Close,
                                    contentDescription = null,
                                    modifier = Modifier.width(InputChipDefaults.IconSize),
                                )
                            },
                            modifier = Modifier.semantics { contentDescription = removeLabel },
                        )
                    }
                }
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .focusRequester(focus)
                    .onFocusChanged { if (it.isFocused) checkClip() },
                singleLine = true,
                supportingText = if (groupMode) ({ Text(stringResource(R.string.convx_new_group_hint)) }) else null,
                placeholder = { Text(stringResource(R.string.ui_add_contact_or_number)) },
                leadingIcon = {
                    Text(
                        stringResource(R.string.screens_new_to_hint),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, end = 4.dp),
                    )
                },
                trailingIcon = {
                    androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }) {
                                Icon(
                                    Symbols.Close,
                                    contentDescription = stringResource(R.string.screens_new_clear_input),
                                )
                            }
                        }
                        // Google Messages' 123/ABC toggle between the text keyboard and the dial pad.
                        IconButton(onClick = { dialpad = !dialpad }) {
                            Icon(
                                if (dialpad) Symbols.TextFields else Symbols.Dialpad,
                                contentDescription = stringResource(
                                    if (dialpad) R.string.ui_show_keyboard else R.string.ui_show_dialpad,
                                ),
                            )
                        }
                        if (canSubmit) {
                            FilledIconButton(onClick = submit) {
                                Icon(
                                    if (groupMode) Symbols.PersonAdd else Symbols.ArrowForward,
                                    contentDescription = stringResource(
                                        if (groupMode) R.string.screens_new_group_add else R.string.screens_new_start,
                                        displayNumber(query.trim()),
                                    ),
                                )
                            }
                        }
                        androidx.compose.foundation.layout.Spacer(Modifier.width(4.dp))
                    }
                },
                keyboardOptions = if (dialpad) {
                    KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Go)
                } else {
                    KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Go)
                },
                keyboardActions = KeyboardActions(onGo = { if (canSubmit) submit() }),
            )

            // B11: above the list, not its first item: a lazy list keeps its first visible row in place when a row is
            // added before it, so a chip that showed up after the first layout sat above the top, out of sight.
            if (!groupMode && query.isBlank() && clipHasText) {
                AssistChip(
                    onClick = pasteNumber,
                    label = { Text(stringResource(R.string.w3c_new_paste)) },
                    leadingIcon = {
                        Icon(Symbols.ContentCopy, contentDescription = null, modifier = Modifier.width(18.dp))
                    },
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            // Share mode lists the recent chats instead of the suggestion chips.
            val showSuggestions = suggestions.isNotEmpty() && query.isBlank() && !shareMode
            // Recent chats load after the first frame and land above the row the list was anchored to; show them.
            val listState = rememberLazyListState()
            val hasRecents = shownRecents.isNotEmpty()
            LaunchedEffect(hasRecents) { if (hasRecents) listState.scrollToItem(0) }
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = padding.calculateBottomPadding() + 8.dp),
            ) {
                if (showSuggestions) {
                    item(key = "suggestions-header") { SectionHeader(stringResource(R.string.suggestions)) }
                    item(key = "suggestions") {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            itemsIndexed(
                                suggestions,
                                key = { index, suggestion -> "s-$index-${suggestion.rawId}" },
                            ) { _, contact ->
                                SuggestionChip(contact, selected = isSelected(contact)) { pick(contact) }
                            }
                        }
                    }
                }
                if (!groupMode && typedNumber != null) {
                    item(key = "send-to") { SendToRow(typedNumber, onClick = submit) }
                }
                recentChatsSection(if (groupMode) emptyList() else shownRecents, onPickRecent)
                if (!groupMode && query.isBlank()) {
                    item(key = "create-group") {
                        CreateGroupRow { groupMode = true }
                    }
                }
                when {
                    !loaded -> item(key = "loading") { LoadingState() }
                    // Only chats match the search: their section is the answer, no "no matches" below it.
                    onlyRecentsMatch(results.isEmpty(), query, shownRecents) -> Unit
                    results.isEmpty() -> item(key = "empty") {
                        EmptyContacts(
                            hasPermission = hasContactsPermission,
                            typing = query.isNotBlank(),
                            onRequestPermission = onRequestPermission,
                        )
                    }
                    query.isBlank() -> {
                        item(key = "contacts-header") { SectionHeader(stringResource(R.string.screens_new_contacts)) }
                        groups.forEach { (letter, group) ->
                            stickyHeader(key = "letter-$letter") { LetterHeader(letter) }
                            items(group, key = { it.key }) { (_, contact) ->
                                PickableContactRow(contact, isSelected(contact)) { pick(contact) }
                            }
                        }
                    }
                    else -> {
                        contactsHeader(shownRecents.isNotEmpty())
                        items(results, key = { it.key }) { (_, contact) ->
                            PickableContactRow(contact, isSelected(contact)) { pick(contact) }
                        }
                    }
                }
            }
        }
    }

    choosing?.let { contact ->
        NumberChooserSheet(
            contact = contact,
            onDismiss = { choosing = null },
            onChoose = { number ->
                choosing = null
                choose(contact, number)
            },
        )
    }
}

@Composable
private fun SendToRow(typed: String, onClick: () -> Unit) {
    // "Send to 555-1888", grouped as the rest of the app writes the number; sending still uses what was typed.
    val label = stringResource(R.string.convx_new_send_to, isolateNumbers(displayNumber(typed)))
    androidx.compose.foundation.layout.Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        androidx.compose.material3.Surface(
            shape = androidx.compose.foundation.shape.CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ) {
            Icon(Symbols.Send, contentDescription = null, modifier = Modifier.padding(12.dp))
        }
        Text(label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun LetterHeader(letter: String) {
    Text(
        text = letter,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/** "Create group" at the top of the list, as in Google Messages. */
@Composable
private fun CreateGroupRow(onClick: () -> Unit) {
    val label = stringResource(R.string.screens_new_create_group)
    androidx.compose.foundation.layout.Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ContactAvatar(title = label, photoUri = "", isGroup = true)
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

/** A contact in the list; in group mode a picked one shows a check and says "Selected" to TalkBack. */
@Composable
private fun PickableContactRow(contact: SimpleContact, selected: Boolean, onClick: () -> Unit) {
    val selectedLabel = stringResource(R.string.screens_new_group_selected)
    ContactRow(
        // CL-10: numbers in the shared display form, as in the conversation list.
        name = isolateNumbers(displayNumber(contact.name)),
        supporting = supportingNumbers(contact.name, contact.phoneNumbers.map { it.normalizedNumber })
            .map { isolateNumbers(displayNumber(it)) }
            .joinToString(listSeparator(uiLocale())),
        photoUri = contact.photoUri,
        onClick = onClick,
        modifier = if (selected) Modifier.semantics { stateDescription = selectedLabel } else Modifier,
        trailing = if (selected) {
            { Icon(Symbols.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
        } else {
            null
        },
    )
}

@Composable
private fun SuggestionChip(contact: SimpleContact, selected: Boolean = false, onClick: () -> Unit) {
    val selectedLabel = stringResource(R.string.screens_new_group_selected)
    Column(
        modifier = Modifier
            .width(80.dp)
            .heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .then(if (selected) Modifier.semantics { stateDescription = selectedLabel } else Modifier)
            .padding(horizontal = 4.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ContactAvatar(title = contact.name, photoUri = contact.photoUri, selected = selected)
        Text(
            isolateNumbers(displayNumber(contact.name)),
            style = MaterialTheme.typography.labelMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun EmptyContacts(hasPermission: Boolean, typing: Boolean, onRequestPermission: () -> Unit) {
    Box(Modifier.fillMaxWidth()) {
        if (!hasPermission) {
            EmptyState(
                icon = Symbols.Contacts,
                title = stringResource(org.fossify.commons.R.string.no_access_to_contacts),
                body = stringResource(R.string.screens_new_no_matches),
                action = {
                    Button(onClick = onRequestPermission) { Text(stringResource(R.string.screens_grant_permission)) }
                },
            )
        } else {
            EmptyState(
                icon = Symbols.Contacts,
                title = stringResource(org.fossify.commons.R.string.no_contacts_found),
                body = if (typing) stringResource(R.string.screens_new_no_matches) else "",
            )
        }
    }
}

@Composable
private fun NumberChooserSheet(
    contact: SimpleContact,
    onDismiss: () -> Unit,
    onChoose: (PhoneNumber) -> Unit,
) {
    val context = LocalContext.current
    UMessageBottomSheet(onDismiss = onDismiss) {
        Text(
            isolateNumbers(displayNumber(contact.name)),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        contact.phoneNumbers.forEach { number ->
            val type = context.getPhoneNumberTypeText(number.type, number.label)
            val label = stringResource(R.string.ui_number_with_type, isolateNumbers(displayNumber(number.value)), type)
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .clickable(role = Role.Button) { onChoose(number) }
                    .padding(horizontal = 24.dp, vertical = 16.dp)
                    .semantics { contentDescription = label },
            )
        }
    }
}
