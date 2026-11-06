package com.glooboo.umessage.ui.lock

import android.app.Activity
import androidx.annotation.StringRes
import com.glooboo.umessage.AppLockGuard
import com.glooboo.umessage.R
import com.glooboo.umessage.ui.components.ChoiceDialog
import com.glooboo.umessage.ui.components.showComposeDialog

/** The short name of a scope, for the Settings row. */
@StringRes
fun LockScope.shortLabelRes(): Int = when (this) {
    LockScope.WholeApp -> R.string.lock_scope_whole
    LockScope.ChosenParts -> R.string.lock_scope_chosen
}

/** I44: "What to lock" on the App lock page. [onChanged] runs after a new scope was saved. */
fun Activity.showLockScopeDialog(onChanged: () -> Unit) {
    val scopes = LockScope.entries
    showComposeDialog { dismiss ->
        ChoiceDialog(
            title = getString(R.string.lock_scope_title),
            options = scopes.map {
                getString(if (it == LockScope.WholeApp) R.string.lock_scope_whole else R.string.lock_scope_chosen_long)
            },
            selectedIndex = scopes.indexOf(LockScope.current(this)),
            onSelect = { index ->
                dismiss()
                LockScope.set(this, scopes[index])
                AppLockGuard.applyWindowProtection(this)
                onChanged()
            },
            onDismiss = dismiss,
        )
    }
}
