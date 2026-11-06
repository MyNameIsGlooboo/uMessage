package com.glooboo.umessage.ui.lock

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.glooboo.umessage.AppLockGuard
import com.glooboo.umessage.R
import com.glooboo.umessage.ui.components.EmptyState
import com.glooboo.umessage.ui.icons.Symbols
import org.fossify.commons.extensions.appLockManager

/**
 * I44: shows [content] (the Blocked tab) unless the app lock covers only chosen parts and is locked. Then it asks for
 * the lock when the tab opens, and shows a note with an Unlock button when the user backs out of it. The lock state is
 * the app lock's own, so the timeout is the one chosen in Settings.
 */
@Composable
fun BlockedLockGate(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val context = LocalContext.current
    var covered by remember { mutableStateOf(LockScope.blockedLocked(context)) }
    var locked by remember { mutableStateOf(covered && context.appLockManager.isLocked()) }
    LifecycleResumeEffect(Unit) {
        covered = LockScope.blockedLocked(context)
        locked = covered && context.appLockManager.isLocked()
        onPauseOrDispose { }
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        locked = covered && context.appLockManager.isLocked()
        // The window hides from Recents while the Blocked tab is open.
        (context as? Activity)?.let(AppLockGuard::applyWindowProtection)
    }
    val unlock = { launcher.launch(Intent(context, LockActivity::class.java).putExtra(LockActivity.EXTRA_PART, true)) }
    LaunchedEffect(locked) { if (locked) unlock() }
    Box(modifier) {
        if (locked) {
            Box(Modifier.fillMaxSize()) {
                EmptyState(
                    icon = Symbols.Lock,
                    title = stringResource(R.string.lock_scope_blocked_title),
                    body = stringResource(R.string.lock_scope_blocked_body),
                    modifier = Modifier.align(Alignment.Center),
                    action = { Button(onClick = { unlock() }) { Text(stringResource(R.string.lock_scope_unlock)) } },
                )
            }
        } else {
            content()
        }
    }
}
