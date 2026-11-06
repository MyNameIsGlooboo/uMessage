package com.glooboo.umessage.dialogs

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.glooboo.umessage.R
import com.glooboo.umessage.helpers.ExportFormat
import com.glooboo.umessage.ui.components.CheckboxRow
import com.glooboo.umessage.ui.components.SwitchRow

/**
 * The questions of the export dialog: the file name, which type of file, which conversations, SMS and MMS, and the
 * optional password. [onChooseConversations] opens the list to pick conversations from.
 */
@Composable
fun ExportOptionsForm(
    state: ExportOptionsState,
    onChooseConversations: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.verticalScroll(rememberScrollState())) {
        OutlinedTextField(
            value = state.name,
            onValueChange = { state.name = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.screens_dialog_filename)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        )
        FormatChoice(state)
        ScopeChoice(state, onChooseConversations)
        CheckboxRow(stringResource(R.string.export_sms), state.sms, { state.sms = it })
        CheckboxRow(stringResource(R.string.export_mms), state.mms, { state.mms = it })
        PasswordChoice(state)
    }
}

@Composable
private fun FormatChoice(state: ExportOptionsState) {
    Column(Modifier.selectableGroup().padding(top = 8.dp)) {
        ExportChoiceRow(
            label = stringResource(R.string.export_format_json),
            selected = state.format == ExportFormat.JSON,
            onSelect = { state.format = ExportFormat.JSON },
        )
        ExportChoiceRow(
            label = stringResource(R.string.export_format_xml),
            selected = state.format == ExportFormat.SMS_XML,
            onSelect = { state.format = ExportFormat.SMS_XML },
            supporting = if (state.format == ExportFormat.SMS_XML) {
                stringResource(R.string.export_format_xml_hint)
            } else {
                null
            },
        )
    }
}

@Composable
private fun ScopeChoice(state: ExportOptionsState, onChoose: () -> Unit) {
    val chosen = state.chosen
    Column(Modifier.selectableGroup().padding(top = 8.dp)) {
        ExportChoiceRow(
            label = stringResource(R.string.export_scope_all),
            selected = !state.chooseConversations,
            onSelect = {
                state.chooseConversations = false
                state.chosen = null
            },
        )
        ExportChoiceRow(
            label = stringResource(R.string.export_scope_choose),
            selected = state.chooseConversations,
            onSelect = {
                state.chooseConversations = true
                onChoose()
            },
            supporting = chosen?.let { pluralStringResource(R.plurals.export_scope_chosen, it.size, it.size) },
        )
    }
}

@Composable
private fun PasswordChoice(state: ExportOptionsState) {
    Column {
        SwitchRow(
            title = stringResource(R.string.export_password_switch),
            body = "",
            checked = state.protect,
            onCheckedChange = { state.protect = it },
        )
        if (state.protect) {
            val problem = state.passwordProblem
            val showProblem = problem != null && state.confirm.isNotEmpty()
            ExportPasswordField(
                value = state.password,
                onValueChange = { state.password = it },
                label = stringResource(R.string.export_password_field),
            )
            ExportPasswordField(
                value = state.confirm,
                onValueChange = { state.confirm = it },
                label = stringResource(R.string.export_password_confirm),
                modifier = Modifier.padding(top = 8.dp),
                error = if (showProblem) problem?.let { stringResource(it) } else null,
                imeAction = ImeAction.Done,
            )
            Text(
                text = stringResource(R.string.export_password_warning),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 8.dp),
            )
        } else {
            // DL-42: the file leaves the app's private storage unencrypted.
            Text(
                text = stringResource(R.string.export_not_encrypted),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}
