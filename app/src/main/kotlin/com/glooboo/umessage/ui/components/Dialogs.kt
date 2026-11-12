package com.glooboo.umessage.ui.components
import androidx.compose.runtime.saveable.rememberSaveable

import android.app.Activity
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.glooboo.umessage.R
import com.glooboo.umessage.ui.theme.UMessageTheme

/**
 * Hosts a Compose dialog from code that is not Compose (legacy callers, ThreadActivity). The dialog draws in its own
 * window; the host view is a 0x0 child of the activity content, removed again by `dismiss`. Safe from any thread.
 */
fun Activity.showComposeDialog(content: @Composable (dismiss: () -> Unit) -> Unit) {
    runOnUiThread {
        if (isFinishing || isDestroyed) return@runOnUiThread
        val host = findViewById<ViewGroup>(android.R.id.content) ?: return@runOnUiThread
        val view = ComposeView(this)
        view.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool)
        view.setContent {
            UMessageTheme {
                content {
                    host.post {
                        view.disposeComposition()
                        host.removeView(view)
                    }
                }
            }
        }
        host.addView(view, FrameLayout.LayoutParams(0, 0))
    }
}

/** Yes/no question. [destructive] colours the confirm button as an error so the risky choice never looks like the default. */
@Composable
fun ConfirmDialog(
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    dismissLabel: String = stringResource(R.string.screens_cancel),
    destructive: Boolean = false,
    content: (@Composable () -> Unit)? = null,
) {
    AlertDialog(
        modifier = modifier,
        onDismissRequest = onDismiss,
        title = title?.let { { Text(it) } },
        text = {
            Column {
                Text(message, style = MaterialTheme.typography.bodyMedium)
                content?.invoke()
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm() }) {
                Text(
                    confirmLabel,
                    color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(dismissLabel) } },
    )
}

/** One-button information dialog with arbitrary content (scrolls when tall). */
@Composable
fun InfoDialog(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    confirmLabel: String = stringResource(R.string.screens_ok),
    /** An extra button beside OK, or null for none. */
    dismissButton: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    AlertDialog(
        modifier = modifier,
        onDismissRequest = onDismiss,
        title = title?.let { { Text(it) } },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) { content() } },
        confirmButton = { TextButton(onClick = onDismiss) { Text(confirmLabel) } },
        dismissButton = dismissButton,
    )
}

/**
 * Single text field dialog. [validate] returns an error message that blocks the confirm button, or null when fine.
 * The field is focused on open and the IME "done" action confirms.
 */
@Composable
fun TextInputDialog(
    title: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    initial: String = "",
    label: String? = null,
    placeholder: String? = null,
    supportingText: String? = null,
    confirmLabel: String = stringResource(R.string.screens_ok),
    keyboardOptions: KeyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
    singleLine: Boolean = true,
    validate: (String) -> String? = { null },
) {
    var value by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(initial, TextRange(initial.length)))
    }
    var showError by rememberSaveable { mutableStateOf(false) }
    val error = validate(value.text)
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val submit = {
        if (error == null) onConfirm(value.text) else showError = true
    }
    AlertDialog(
        modifier = modifier,
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = {
                    value = it
                    showError = false
                },
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
                label = label?.let { { Text(it) } },
                placeholder = placeholder?.let { { Text(it) } },
                singleLine = singleLine,
                isError = showError && error != null,
                supportingText = when {
                    showError && error != null -> ({ Text(error) })
                    supportingText != null -> ({ Text(supportingText) })
                    else -> null
                },
                keyboardOptions = keyboardOptions,
                keyboardActions = KeyboardActions(onDone = { submit() }),
            )
        },
        confirmButton = { TextButton(onClick = submit) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.screens_cancel)) } },
    )
}

/**
 * Checkbox with its label (and optional [supporting] line) in one 48dp+ touch target; the row toggles, the box itself
 * is not separately focusable. [labelStyle] lets a dialog match the label to its body text (owner notes R2: in the
 * Older block lists dialog the bodyLarge label stood out against the bodyMedium bullet points).
 */
@Composable
fun CheckboxRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    supporting: String? = null,
    labelStyle: TextStyle = MaterialTheme.typography.bodyLarge,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = onCheckedChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
        Column(Modifier.padding(start = 16.dp)) {
            Text(label, style = labelStyle)
            // Says why a box is greyed out or what it depends on, so a disabled look never comes without a reason.
            if (supporting != null) {
                Text(
                    supporting,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Modal bottom sheet with the project's content padding and navigation bar clearance. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UMessageBottomSheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = modifier,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) { content() }
    }
}

/**
 * Single-choice list in a dialog: tapping a row selects it and closes the dialog. Each row is a 48dp+ target
 * that announces as a radio button.
 */
@Composable
fun ChoiceDialog(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
) {
    AlertDialog(
        modifier = modifier,
        onDismissRequest = onDismiss,
        title = title?.let { { Text(it) } },
        text = {
            val selectedState = stringResource(R.string.spam_state_selected)
            val notSelectedState = stringResource(R.string.spam_state_not_selected)
            // A selectable group, so TalkBack says "1 of 4" and which option is the current one (e.g. lock delay).
            Column(Modifier.selectableGroup().verticalScroll(rememberScrollState())) {
                options.forEachIndexed { index, label ->
                    val isSelected = index == selectedIndex
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .selectable(selected = isSelected, role = Role.RadioButton) { onSelect(index) }
                            .semantics { stateDescription = if (isSelected) selectedState else notSelectedState },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = index == selectedIndex, onClick = null)
                        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 16.dp))
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.screens_cancel)) } },
    )
}
