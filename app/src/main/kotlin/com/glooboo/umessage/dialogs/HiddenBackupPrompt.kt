package com.glooboo.umessage.dialogs

import android.app.Activity
import android.content.Intent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.res.stringResource
import androidx.activity.ComponentActivity
import com.glooboo.umessage.R
import com.glooboo.umessage.helpers.HiddenChatsSession
import com.glooboo.umessage.ui.components.showComposeDialog
import com.glooboo.umessage.ui.lock.LockActivity

/**
 * Q14: asked before a full backup starts while the hidden chats are locked. "Unlock" opens the app's own lock screen
 * (PIN, biometrics, the phone's screen lock; this class never sees or keeps the PIN); "Skip hidden chats" backs up
 * without them. [onAnswer] gets true for skip and false after a successful unlock; a failed or cancelled unlock asks
 * again, so the user can retry or skip. Dismissing the dialog cancels the backup.
 */
object HiddenBackupPrompt {
    private var requests = 0

    fun show(activity: ComponentActivity, onAnswer: (skip: Boolean) -> Unit) {
        activity.showComposeDialog { dismiss ->
            AlertDialog(
                onDismissRequest = dismiss,
                title = { Text(stringResource(R.string.backup_hidden_title)) },
                text = { Text(stringResource(R.string.backup_hidden_message)) },
                confirmButton = {
                    TextButton(onClick = { dismiss(); unlock(activity, onAnswer) }) {
                        Text(stringResource(R.string.backup_hidden_unlock))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { dismiss(); onAnswer(true) }) {
                        Text(stringResource(R.string.backup_hidden_skip))
                    }
                },
            )
        }
    }

    private fun unlock(activity: ComponentActivity, onAnswer: (skip: Boolean) -> Unit) {
        var launcher: ActivityResultLauncher<Intent>? = null
        launcher = activity.activityResultRegistry.register(
            "hidden_backup_unlock_${requests++}", ActivityResultContracts.StartActivityForResult(),
        ) { result ->
            launcher?.unregister()
            if (result.resultCode == Activity.RESULT_OK && HiddenChatsSession.isUnlocked()) {
                onAnswer(false)
            } else {
                show(activity, onAnswer) // failed or cancelled: retry or skip
            }
        }
        launcher.launch(Intent(activity, LockActivity::class.java).putExtra(LockActivity.EXTRA_HIDDEN, true))
    }
}
