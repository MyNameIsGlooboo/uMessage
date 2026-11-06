package com.glooboo.umessage.ui.thread

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldDecorator
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.glooboo.umessage.R
import com.glooboo.umessage.ui.icons.Symbols
import org.fossify.commons.extensions.normalizeString
import org.fossify.commons.models.SimpleContact

private const val MIN_QUERY_FOR_SUGGESTIONS = 2
private const val MIN_QUERY_FOR_NUMBER = 3
private const val MAX_SUGGESTIONS = 6

internal fun matchContacts(contacts: List<SimpleContact>, query: String): List<SimpleContact> {
    if (query.length < MIN_QUERY_FOR_SUGGESTIONS) return emptyList()
    val search = query.normalizeString()
    return contacts
        .filter { it.doesContainPhoneNumber(search) || it.name.contains(search, ignoreCase = true) }
        .sortedBy { !it.name.startsWith(search, ignoreCase = true) }
        .take(MAX_SUGGESTIONS)
}

/** Panel under the top bar to add people to the conversation: current members, a search field and suggestions. */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun PeoplePanel(vm: ThreadViewModel, actions: ThreadActions) {
    val colors = MaterialTheme.colorScheme
    val query = vm.peopleQuery.text.toString()
    val focusRequester = remember { FocusRequester() }
    val suggestions = remember(query, vm.contacts) { matchContacts(vm.contacts, query) }
    Surface(color = colors.surface, contentColor = colors.onSurface, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                FlowRow(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    vm.people.forEach { person ->
                        InputChip(
                            selected = false,
                            onClick = { if (person.removable) actions.onRemovePerson(person.rawId) },
                            label = { Text(person.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            trailingIcon = if (person.removable) {
                                {
                                    Icon(
                                        Symbols.Close,
                                        contentDescription = stringResource(R.string.thread_people_remove, person.name),
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            } else {
                                null
                            },
                        )
                    }
                }
                IconButton(onClick = actions.onConfirmPeople) {
                    Icon(Symbols.Check, contentDescription = stringResource(R.string.thread_people_confirm))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                LaunchedEffect(Unit) { focusRequester.requestFocus() }
                val style = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface)
                BasicTextField(
                    state = vm.peopleQuery,
                    textStyle = style,
                    cursorBrush = SolidColor(colors.primary),
                    lineLimits = TextFieldLineLimits.SingleLine,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                    modifier = Modifier.weight(1f).focusRequester(focusRequester),
                    decorator = TextFieldDecorator { inner ->
                        Box(
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .padding(vertical = 12.dp),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            if (query.isEmpty()) {
                                Text(stringResource(R.string.add_contact_or_number), style = style, color = colors.onSurfaceVariant)
                            }
                            inner()
                        }
                    },
                )
                if (query.length >= MIN_QUERY_FOR_NUMBER) {
                    IconButton(onClick = { actions.onAddTypedNumber(query) }) {
                        Icon(Symbols.Add, contentDescription = stringResource(R.string.thread_people_add_number))
                    }
                }
            }
            suggestions.forEach { contact ->
                Surface(
                    onClick = { actions.onPickContact(contact) },
                    shape = RoundedCornerShape(12.dp),
                    color = colors.surface,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        LetterAvatar(label = contact.name, size = 36.dp)
                        Column {
                            Text(contact.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                contact.phoneNumbers.firstOrNull()?.normalizedNumber.orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}
