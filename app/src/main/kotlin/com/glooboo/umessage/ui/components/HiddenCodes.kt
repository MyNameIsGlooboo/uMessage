package com.glooboo.umessage.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.glooboo.umessage.helpers.CodeMasker
import com.glooboo.umessage.helpers.w3aPrefs

/** Whether "Hide codes on the lock screen" is on. Read on every composition, so a change shows on return. */
@Composable
fun hideCodesSetting(): Boolean {
    val context = LocalContext.current
    return runCatching { context.w3aPrefs.hideCodesOnLockScreen }.getOrDefault(false)
}

/**
 * [text] as the screen shows it: with "Hide codes on the lock screen" on, the code of a message is `••••` in the
 * conversation list, the search results and the Spam tab, not only on the lock screen. The text itself stays whole
 * (the Codes filter, the search and Copy code still see the real code); only what is drawn changes.
 */
@Composable
fun rememberCodeHidden(text: String): String {
    val hide = hideCodesSetting()
    return remember(text, hide) { CodeMasker.maskIf(hide, text) }
}
