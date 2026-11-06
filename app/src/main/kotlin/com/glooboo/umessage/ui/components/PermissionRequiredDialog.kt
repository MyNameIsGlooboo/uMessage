package com.glooboo.umessage.ui.components

import android.app.Activity
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.glooboo.umessage.R

/** "Permission required" explanation with a grant button; [onCancel] runs for the cancel button and for dismissing. */
@Composable
fun PermissionRequiredDialog(
    text: String,
    onGrant: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AlertDialog(
        modifier = modifier,
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.dialogs_permission_required)) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onGrant) { Text(stringResource(R.string.dialogs_grant_permission)) } },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.screens_cancel)) } },
    )
}

/** Drop-in for the old view-based permission dialog: [onGrant] after the grant button, [onCancel] otherwise. */
fun showPermissionRequiredDialog(
    activity: Activity,
    text: String,
    onGrant: () -> Unit,
    onCancel: (() -> Unit)? = null,
) {
    activity.showComposeDialog { dismiss ->
        PermissionRequiredDialog(
            text = text,
            onGrant = {
                dismiss()
                onGrant()
            },
            onCancel = {
                dismiss()
                onCancel?.invoke()
            },
        )
    }
}
