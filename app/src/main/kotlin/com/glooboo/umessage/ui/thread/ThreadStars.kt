package com.glooboo.umessage.ui.thread

import android.content.Context
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.glooboo.umessage.R
import com.glooboo.umessage.helpers.StarredMessages
import com.glooboo.umessage.helpers.starredDB
import com.glooboo.umessage.models.Message
import com.glooboo.umessage.models.MessageKey
import com.glooboo.umessage.ui.icons.ShortcutIcons
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** True when every one of [selected] is starred, so the selection bar offers Unstar instead of Star. */
fun allStarred(selected: List<Message>, starred: Set<MessageKey>): Boolean =
    selected.isNotEmpty() && selected.all { it.key in starred }

/**
 * I13: which messages are starred, for the selection bar and the bubbles. The set holds every star of the app, not
 * only the open chat's: it is small, and it needs no chat id that may still change. The activity calls [attach] once.
 */
class ThreadStars {
    var keys by mutableStateOf<Set<MessageKey>>(emptySet())
        private set

    private var job: Job? = null
    private var app: Context? = null
    private var scope: CoroutineScope? = null

    fun attach(context: Context, scope: CoroutineScope) {
        val appContext = context.applicationContext
        app = appContext
        this.scope = scope
        job?.cancel()
        job = scope.launch {
            StarredMessages.relink(appContext)
            appContext.starredDB.observeAll().map { all -> all.mapTo(HashSet()) { it.key } }.collect { keys = it }
        }
    }

    fun isStarred(message: Message): Boolean = message.key in keys

    /** Stars [selected], or unstars them when they are all starred already. */
    fun toggle(selected: List<Message>) {
        val appContext = app ?: return
        val unstar = allStarred(selected, keys)
        scope?.launch {
            if (unstar) {
                StarredMessages.unstar(appContext, selected.map { it.key })
            } else {
                StarredMessages.star(appContext, selected)
            }
        }
    }
}

/** The small star under a starred bubble. */
@Composable
fun StarMark(modifier: Modifier = Modifier) {
    Icon(
        ShortcutIcons.StarFilled,
        contentDescription = stringResource(R.string.i13_starred_mark),
        modifier = modifier.padding(horizontal = 8.dp, vertical = 2.dp).size(12.dp),
        tint = MaterialTheme.colorScheme.tertiary,
    )
}
