package com.glooboo.umessage.ui.conversations

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.glooboo.umessage.AppLockGuard
import com.glooboo.umessage.R
import com.glooboo.umessage.helpers.HiddenChatsSession
import com.glooboo.umessage.ui.icons.Symbols
import com.glooboo.umessage.ui.lock.LockActivity

/**
 * B06: the line under the last conversation that leads to the hidden chats. Locked, it asks for the app lock and the
 * hidden chats then show in the list; unlocked, it locks them again. It names no chat and no count.
 */
@Composable
fun HiddenChatsLine(onChange: () -> Unit, unlocked: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val confirm = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            (context as? Activity)?.let(AppLockGuard::applyWindowProtection)
            onChange()
        }
    }
    val label = stringResource(if (unlocked) R.string.hidden_chats_lock else R.string.hidden_chats_show)
    val askLock = {
        confirm.launch(Intent(context, LockActivity::class.java).putExtra(LockActivity.EXTRA_HIDDEN, true))
    }
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable {
                if (unlocked) {
                    HiddenChatsSession.lock()
                    (context as? Activity)?.let(AppLockGuard::applyWindowProtection)
                    onChange()
                } else {
                    askLock()
                }
            }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Symbols.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp),
        )
    }
}
