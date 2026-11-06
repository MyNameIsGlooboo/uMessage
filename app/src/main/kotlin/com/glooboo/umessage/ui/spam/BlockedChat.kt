package com.glooboo.umessage.ui.spam

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import com.glooboo.umessage.R
import com.glooboo.umessage.activities.ThreadActivity
import com.glooboo.umessage.extensions.getThreadId
import com.glooboo.umessage.helpers.MainTabLinks
import com.glooboo.umessage.helpers.THREAD_FOCUS_COMPOSER
import com.glooboo.umessage.helpers.THREAD_ID
import com.glooboo.umessage.helpers.THREAD_NOTICE
import com.glooboo.umessage.helpers.THREAD_NUMBER
import com.glooboo.umessage.helpers.THREAD_TITLE
import com.glooboo.umessage.helpers.refreshConversations
import com.glooboo.umessage.ui.components.displayNumber
import com.glooboo.umessage.ui.components.isolateNumbers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * After "Not spam" in a blocked chat: the normal chat of the same sender, opened with the composer focused so the
 * user can reply at once.
 */
internal object BlockedChat {

    /**
     * The chat the system keeps for [address]; 0 when Android cannot make one. A provider round trip, so it runs off
     * the main thread, the way the contact picker looks a number up.
     */
    suspend fun threadIdOf(context: Context, address: String): Long =
        withContext(Dispatchers.IO) { runCatching { context.getThreadId(address) }.getOrDefault(0L) }

    /** The chat for [threadId], with the title and number extras the conversation list sets, and the reply hints. */
    fun chatIntent(context: Context, threadId: Long, address: String, notice: String?): Intent =
        Intent(context, ThreadActivity::class.java)
            .putExtra(THREAD_ID, threadId)
            .putExtra(THREAD_TITLE, displayNumber(context, address))
            .putExtra(THREAD_NUMBER, address)
            .putExtra(THREAD_FOCUS_COMPOSER, true)
            .apply { notice?.let { putExtra(THREAD_NOTICE, it) } }

    /**
     * Shows the conversation list under the chat, so Back from the chat returns to it like from any chat, then opens
     * the chat. The list screen is brought forward and the blocked chat above it is closed with it.
     */
    fun open(context: Context, chat: Intent) {
        context.startActivity(MainTabLinks.intent(context, MainTabLinks.TAB_MESSAGES))
        context.startActivity(chat)
    }
}

/**
 * Waits for the "Not spam" of a blocked chat. Once its messages are in the inbox, opens the normal chat of the
 * sender (composer focused, keyboard shown) and closes this screen. When nothing reached the inbox the screen stays
 * and [onRetry] lets its buttons work again; the failure snackbar comes from [BlockedEvents].
 */
@Composable
internal fun OpenChatAfterNotSpam(
    viewModel: BlockedViewModel,
    address: String,
    threadIdOf: suspend (Context, String) -> Long,
    onBack: () -> Unit,
    onRetry: () -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val close by rememberUpdatedState(onBack)
    val retry by rememberUpdatedState(onRetry)
    val lookUp by rememberUpdatedState(threadIdOf)
    LaunchedEffect(viewModel, address) {
        viewModel.notSpamDone.collect { done ->
            if (done.toInbox <= 0) {
                retry()
                return@collect
            }
            val threadId = lookUp(context, address)
            if (threadId > 0L) {
                val moved = BlockedEvent.Restored(0L, done.toInbox, done.allowed.size, 0, done.allowed)
                val notice = restoredSnackText(resources, moved) { isolateNumbers(displayNumber(context, it)) }
                refreshConversations(threadId)
                BlockedChat.open(context, BlockedChat.chatIntent(context, threadId, address, notice))
            } else {
                Toast.makeText(context, R.string.nsf_snack_moved, Toast.LENGTH_SHORT).show()
            }
            close()
        }
    }
}
