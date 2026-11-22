package com.glooboo.umessage.ui.main

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.glooboo.umessage.helpers.SpamReaders

/**
 * H-16: true while the app lock covers Spam and is locked, read as [rememberSpamLocked] does. H-20: once it is not, a
 * Spam section that went while it was locked is searched again ([MainSearchState.spamUnlocked]).
 */
@Composable
internal fun rememberSpamLockWatch(search: MainSearchState): Boolean {
    val locked = rememberSpamLocked(search.blockedHits)
    LaunchedEffect(locked) { if (!locked) search.spamUnlocked() }
    return locked
}

/** H-16: true while the app lock covers Spam and is locked; read again on each resume and when [key] changes. */
@Composable
internal fun rememberSpamLocked(key: Any): Boolean {
    val context = LocalContext.current
    var resumes by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        resumes++
        onPauseOrDispose { }
    }
    return remember(resumes, key) { SpamReaders.lockedNow(context) }
}
