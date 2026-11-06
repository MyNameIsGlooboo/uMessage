package com.glooboo.umessage.ui.thread

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.glooboo.umessage.helpers.TextSize
import com.glooboo.umessage.helpers.w3cPrefs
import com.glooboo.umessage.ui.settings.label

/** A pinch has to grow the text by this factor to move one step up... */
internal const val PINCH_ZOOM_IN_AT = 1.25f

/** ...and shrink it to this factor to move one step down. */
internal const val PINCH_ZOOM_OUT_AT = 0.8f

/**
 * I37: turns the zoom factors of one pinch into [TextSize] steps. The factors add up until they pass a threshold, which
 * gives one step and starts the count again, also at the smallest or largest size where there is no step to give.
 */
class PinchZoomSteps {
    private var accumulated = 1f

    /** The size after a pinch event that scaled by [zoom] while the size was [current], or null for no change. */
    fun onZoom(current: TextSize, zoom: Float): TextSize? {
        accumulated *= zoom
        val sizes = TextSize.entries
        val next = when {
            accumulated >= PINCH_ZOOM_IN_AT -> sizes.getOrNull(current.ordinal + 1)
            accumulated <= PINCH_ZOOM_OUT_AT -> sizes.getOrNull(current.ordinal - 1)
            else -> return null
        }
        accumulated = 1f
        return next
    }

    fun reset() {
        accumulated = 1f
    }
}

/**
 * I37: a two-finger pinch on the message list moves the app's Text size one step at a time ([onChange] gets the new
 * size). The one-finger touches (scroll, long press, text selection) are only watched, never consumed; the two-finger
 * ones are consumed so the list does not scroll under the pinch.
 */
@Composable
fun Modifier.pinchToZoomText(enabled: Boolean, onChange: (TextSize) -> Unit): Modifier {
    if (!enabled) return this
    val prefs = LocalContext.current.w3cPrefs
    val steps = remember { PinchZoomSteps() }
    return pointerInput(prefs) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            steps.reset()
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.changes.count { it.pressed } >= 2) {
                    val zoom = event.calculateZoom()
                    if (zoom != 1f) {
                        steps.onZoom(prefs.textSize, zoom)?.let {
                            prefs.textSize = it
                            onChange(it)
                        }
                    }
                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                }
            } while (event.changes.any { it.pressed })
        }
    }
}

/** The size name that shows for a moment while pinching. */
@Composable
fun PinchZoomLabel(size: TextSize, modifier: Modifier = Modifier) {
    val name = size.label()
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.inverseSurface,
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        modifier = modifier,
    ) {
        Text(
            name,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
        )
    }
}
