package com.glooboo.umessage.ui.spam

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import com.glooboo.umessage.R
import com.glooboo.umessage.ui.components.EmptyState
import com.glooboo.umessage.ui.components.ProgressStrip
import com.glooboo.umessage.ui.components.localizedNumber
import com.glooboo.umessage.ui.components.uiLocale
import com.glooboo.umessage.ui.conversations.MARK_READ_BAR_DELAY_MS
import com.glooboo.umessage.ui.conversations.MARK_READ_BAR_MIN_CHATS
import com.glooboo.umessage.ui.conversations.MarkReadProgress
import com.glooboo.umessage.ui.conversations.ProgressThrottle

/** Test tag of the bar, so a test can tell whether it shows. */
internal const val MOVE_PROGRESS_TAG = "move_progress"

/**
 * The strip in the Spam tab and the sender thread while "Not spam" moves many messages:
 * "Moving messages: 12 of 80".
 */
@Composable
fun MoveProgressBar(progress: MarkReadProgress, modifier: Modifier = Modifier) {
    val locale = uiLocale()
    val label = stringResource(
        R.string.move_progress,
        localizedNumber(progress.done, locale),
        localizedNumber(progress.total, locale),
    )
    ProgressStrip(label, progress.fraction, MOVE_PROGRESS_TAG, modifier)
}

/**
 * Publishes the throttled progress of a restore loop through [publish]: every few messages or every 100 ms, and
 * always the last one. A batch of more than a few messages shows the bar from the start; a short one only when it is
 * slow. [finish] clears the bar and belongs in a `finally`.
 */
internal class MoveProgressReporter(
    private val total: Int,
    private val publish: (MarkReadProgress?) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val startedAt = clock()
    private val throttle = ProgressThrottle(startedAt)

    init {
        if (total > MARK_READ_BAR_MIN_CHATS) publish(MarkReadProgress(0, total))
    }

    /** [done] of the messages have been handled. */
    fun step(done: Int) {
        val now = clock()
        val barWanted = total > MARK_READ_BAR_MIN_CHATS || now - startedAt >= MARK_READ_BAR_DELAY_MS
        if (barWanted && throttle.shouldPublish(done, total, now)) publish(MarkReadProgress(done, total))
    }

    fun finish() = publish(null)
}

/** The sender thread when there is nothing to list: loading, "Not spam" working, or the sender is gone. */
@Composable
internal fun ThreadPlaceholder(
    loading: Boolean,
    moving: Boolean,
    barShown: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(modifier, contentAlignment = Alignment.Center) {
        if (loading || moving) {
            // The progress bar says it already; a second indicator would be read twice.
            if (!barShown) {
                val label = stringResource(if (loading) R.string.spam_loading else R.string.nsf_moving)
                CircularProgressIndicator(Modifier.semantics { contentDescription = label })
            }
        } else {
            EmptyState(
                iconRes = R.drawable.ic_spam_shield_check,
                title = stringResource(R.string.r3b_thread_empty_title),
                body = stringResource(R.string.r3b_thread_empty_body),
            )
        }
    }
}
