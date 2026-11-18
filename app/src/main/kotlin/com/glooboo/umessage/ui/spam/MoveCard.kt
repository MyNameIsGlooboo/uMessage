package com.glooboo.umessage.ui.spam

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.glooboo.umessage.R
import com.glooboo.umessage.ui.components.localizedNumber
import com.glooboo.umessage.ui.components.uiLocale
import com.glooboo.umessage.ui.conversations.MarkReadProgress
import kotlinx.coroutines.delay

/** Test tag of the card in the Spam list, so a test can tell whether it shows. */
internal const val MOVE_CARD_TAG = "move_card"

/** A move shows its indicator only once it has run this long: a quick one never flashes a bar. */
internal const val MOVE_INDICATOR_DELAY_MS = 300L

/** TalkBack hears the count in quarters of the total, so it speaks four times at most. */
private const val SPOKEN_STEPS = 4
private const val BAR_ANIMATION_MS = 200
private const val CARD_ENTER_MS = 150
private const val CARD_EXIT_MS = 100
private const val TRACK_ALPHA = 0.24f

/** Milliseconds still to wait before the indicator may show; 0 when it is due. Pure, for tests. */
internal fun moveIndicatorWait(startedAt: Long, now: Long): Long =
    (MOVE_INDICATOR_DELAY_MS - (now - startedAt)).coerceIn(0L, MOVE_INDICATOR_DELAY_MS)

/** True once a move that began at [startedAt] has run for 300 ms. Recomposes when it becomes true. */
@Composable
internal fun moveIndicatorDue(startedAt: Long): Boolean {
    val due: State<Boolean> = produceState(false, startedAt) {
        value = false
        if (startedAt > 0L) {
            delay(moveIndicatorWait(startedAt, System.currentTimeMillis()))
            value = true
        }
    }
    return due.value
}

/** The count TalkBack hears: [done] rounded down to a quarter of [total]. Pure. */
internal fun spokenDone(done: Int, total: Int): Int =
    if (total <= 0) 0 else (done.coerceIn(0, total) * SPOKEN_STEPS / total) * total / SPOKEN_STEPS

/** "Moving messages: 12 of 80" over a bar. Used by the sender page's footer and by the Spam list's card. */
@Composable
internal fun MoveProgressLine(
    progress: MarkReadProgress,
    modifier: Modifier = Modifier,
    barColor: Color = MaterialTheme.colorScheme.primary,
    trackColor: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
) {
    val locale = uiLocale()
    val label = stringResource(
        R.string.move_progress,
        localizedNumber(progress.done, locale),
        localizedNumber(progress.total, locale),
    )
    val spoken = stringResource(
        R.string.move_progress,
        localizedNumber(spokenDone(progress.done, progress.total), locale),
        localizedNumber(progress.total, locale),
    )
    val animated by animateFloatAsState(progress.fraction, tween(BAR_ANIMATION_MS), label = "moveProgress")
    Column(
        modifier.fillMaxWidth().clearAndSetSemantics {
            liveRegion = LiveRegionMode.Polite
            contentDescription = spoken
        },
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        LinearProgressIndicator(
            progress = { animated },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(4.dp),
            color = barColor,
            trackColor = trackColor,
            strokeCap = StrokeCap.Round,
            gapSize = 0.dp,
            drawStopIndicator = {},
        )
    }
}

/** "Moved to inbox" for a move whose own screen is gone: the Spam list tells the user it is over. */
@Composable
internal fun OrphanMovedSnack(snackbar: SnackbarHostState, moves: NotSpamMoves = NotSpamMoves.shared) {
    val text = stringResource(R.string.nsf_snack_moved)
    LaunchedEffect(moves) {
        moves.orphanDone.collect { snackbar.showSnackbar(text, duration = SnackbarDuration.Short) }
    }
}

/** The card in the snackbar slot of the Spam list. Shows nothing when [progress] is null or not due yet. */
@Composable
internal fun MoveCardHost(progress: MarkReadProgress?, startedAt: Long) {
    // The last counts stay for the exit animation, which runs after [progress] is already null.
    val last = remember { mutableStateOf(progress) }
    if (progress != null) last.value = progress
    AnimatedVisibility(
        visible = progress != null && moveIndicatorDue(startedAt),
        enter = fadeIn(tween(CARD_ENTER_MS)) + slideInVertically { it / 2 },
        exit = fadeOut(tween(CARD_EXIT_MS)),
    ) {
        last.value?.let { shown ->
            Surface(
                modifier = Modifier.padding(12.dp).fillMaxWidth().widthIn(max = 600.dp).testTag(MOVE_CARD_TAG),
                shape = SnackbarDefaults.shape,
                color = SnackbarDefaults.color,
                contentColor = SnackbarDefaults.contentColor,
                shadowElevation = 6.dp,
            ) {
                MoveProgressLine(
                    shown,
                    Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    barColor = MaterialTheme.colorScheme.inversePrimary,
                    trackColor = SnackbarDefaults.contentColor.copy(alpha = TRACK_ALPHA),
                )
            }
        }
    }
}
