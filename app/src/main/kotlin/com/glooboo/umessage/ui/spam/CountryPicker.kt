package com.glooboo.umessage.ui.spam

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.glooboo.umessage.R
import com.glooboo.umessage.ui.components.uiLocale

private val ListMaxHeight = 320.dp

/**
 * I24: picks the home country. [selected] is the ISO region now in use, blank when the setting is off; [onSelect]
 * gets the chosen region, or "" for "Off".
 */
@Composable
fun CountryPicker(selected: String, onSelect: (String) -> Unit, onDismiss: () -> Unit) {
    val locale = uiLocale()
    val all = remember(locale) { CountryOptions.all(locale) }
    var query by rememberSaveable { mutableStateOf("") }
    val shown = remember(all, query) { CountryOptions.filter(all, query) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.region_picker_title)) },
        text = {
            Column {
                Text(stringResource(R.string.region_picker_body), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.region_picker_search)) },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                LazyColumn(Modifier.selectableGroup().heightIn(max = ListMaxHeight)) {
                    item { CountryRow(stringResource(R.string.region_off), selected.isBlank()) { onSelect("") } }
                    items(shown, key = { it.region }) { option ->
                        CountryRow(option.label, option.region.equals(selected, ignoreCase = true)) {
                            onSelect(option.region)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.screens_cancel)) } },
    )
}

@Composable
private fun CountryRow(label: String, isSelected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(selected = isSelected, role = Role.RadioButton, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = isSelected, onClick = null)
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 16.dp))
    }
}
