package com.glooboo.umessage.ui.conversations

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.positionChangeIgnoreConsumed
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val RESET_DELAY_MS = 700L
private const val SWIPE_OUT_MS = 180

/** One swipe direction of a row: what the background shows while dragging and what runs once the swipe commits. */
@Immutable
class SwipeAction(
    val icon: ImageVector,
    val label: String,
    /** Drawn in the error colours, for an action that blocks. */
    val destructive: Boolean = false,
    val onCommit: (threadId: Long) -> Unit,
)

/**
 * Drag state and pointer handling of one swipeable row (see [SwipeableConversation]). [offsetPx] is kept in
 * layout-direction terms (positive = towards the end), so in RTL the row follows the finger and the icon is revealed
 * on the side the row leaves. The composable copies its current row, actions and direction in after every composition.
 */
@Stable
internal class RowSwipeState(private val scope: CoroutineScope) {
    var offsetPx by mutableFloatStateOf(0f)
        private set
    var widthPx = 0f

    /** A finger that has started moving is scrolling or swiping, not asking for selection. */
    var fingerMoved = false
        private set
    var threadId = 0L
    var start: SwipeAction? = null
    var end: SwipeAction? = null
    var selecting = false

    /** -1 in RTL: pointer deltas are physical, [offsetPx] is "towards the end" like Modifier.offset. */
    var directionSign = 1f
    var flingVelocityPx = 0f

    val direction: SwipeToDismissBoxValue
        get() = when {
            offsetPx > 0f -> SwipeToDismissBoxValue.StartToEnd
            offsetPx < 0f -> SwipeToDismissBoxValue.EndToStart
            else -> SwipeToDismissBoxValue.Settled
        }

    /**
     * The swipe only starts when the finger moves within [MAX_SWIPE_ANGLE_DEGREES] of horizontal, so a thumb scroll
     * that drifts sideways never drags the row; a drag commits past [SWIPE_COMMIT_FRACTION] of the width, or on a real
     * fling that has already moved [SWIPE_FLING_MIN_FRACTION]. Swiping is off while selecting.
     */
    suspend fun PointerInputScope.detectSwipes() = awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        fingerMoved = false
        if (selecting || (start == null && end == null)) return@awaitEachGesture
        val tracker = VelocityTracker()
        tracker.addPosition(down.uptimeMillis, down.position)
        if (!awaitSwipeStart(down.id, tracker, viewConfiguration.touchSlop)) return@awaitEachGesture
        followSwipe(down.id, tracker)
        release(tracker.calculateVelocity().x * directionSign)
    }

    /** Waits until the finger has moved past [slop]; true when that move is a horizontal swipe this row can take. */
    private suspend fun AwaitPointerEventScope.awaitSwipeStart(
        id: PointerId,
        tracker: VelocityTracker,
        slop: Float,
    ): Boolean {
        var total = Offset.Zero
        while (true) {
            val change = awaitPointerEvent().changes.firstOrNull { it.id == id }
            if (change == null || !change.pressed || change.isConsumed) return false
            total += delta(change)
            tracker.addPosition(change.uptimeMillis, change.position)
            if (total.getDistance() >= slop) {
                fingerMoved = true
                // A direction without an action does not move the row at all.
                if (!isHorizontalSwipeIntent(total.x, total.y) || !hasAction(total.x)) return false
                offsetPx = total.x.coerceIn(-widthPx, widthPx)
                change.consume()
                return true
            }
        }
    }

    /** Moves the row with the finger until it lifts, never towards a side without an action. */
    private suspend fun AwaitPointerEventScope.followSwipe(id: PointerId, tracker: VelocityTracker) {
        while (true) {
            val change = awaitPointerEvent().changes.firstOrNull { it.id == id }
            if (change == null || !change.pressed) return
            val low = if (end != null) -widthPx else 0f
            val high = if (start != null) widthPx else 0f
            offsetPx = (offsetPx + delta(change).x).coerceIn(low, high)
            tracker.addPosition(change.uptimeMillis, change.position)
            change.consume()
        }
    }

    private fun delta(change: PointerInputChange): Offset {
        val physical = change.positionChangeIgnoreConsumed()
        return physical.copy(x = physical.x * directionSign)
    }

    private fun hasAction(dx: Float): Boolean = if (dx > 0f) start != null else end != null

    private fun release(velocity: Float) {
        if (offsetPx != 0f && shouldCommitSwipe(offsetPx, widthPx, velocity, flingVelocityPx)) {
            commit(if (offsetPx > 0f) 1f else -1f)
        } else {
            scope.launch { animate(offsetPx, 0f) { value, _ -> offsetPx = value } }
        }
    }

    private fun commit(direction: Float) {
        scope.launch {
            animate(offsetPx, direction * widthPx, animationSpec = tween(SWIPE_OUT_MS)) { value, _ -> offsetPx = value }
            (if (direction > 0f) start else end)?.onCommit?.invoke(threadId)
            // If the action failed and the row stays, bring it back instead of leaving it swiped away.
            delay(RESET_DELAY_MS)
            animate(offsetPx, 0f) { value, _ -> offsetPx = value }
        }
    }
}

/** The colour and labelled icon revealed under a row while it is dragged towards [direction]. */
@Composable
internal fun SwipeBackground(direction: SwipeToDismissBoxValue, startToEnd: SwipeAction?, endToStart: SwipeAction?) {
    val action = when (direction) {
        SwipeToDismissBoxValue.StartToEnd -> startToEnd
        SwipeToDismissBoxValue.EndToStart -> endToStart
        else -> null
    }
    if (action == null) {
        Box(Modifier.fillMaxSize())
        return
    }
    val colors = MaterialTheme.colorScheme
    val container = if (action.destructive) colors.errorContainer else colors.primaryContainer
    val content = if (action.destructive) colors.onErrorContainer else colors.onPrimaryContainer
    val towardsEnd = direction == SwipeToDismissBoxValue.StartToEnd
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(container)
            .padding(horizontal = 24.dp),
        contentAlignment = if (towardsEnd) Alignment.CenterStart else Alignment.CenterEnd,
    ) {
        // The label names the action, so a swipe is never a surprise (CL-12).
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(imageVector = action.icon, contentDescription = null, tint = content)
            Text(action.label, style = MaterialTheme.typography.labelLarge, color = content, maxLines = 1)
        }
    }
}
