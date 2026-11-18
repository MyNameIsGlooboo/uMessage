package com.glooboo.umessage.ui.onboarding

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.glooboo.umessage.R
import com.glooboo.umessage.ui.icons.Symbols
import com.glooboo.umessage.ui.theme.success
import kotlin.math.roundToInt

private val LaneHeight = 40.dp
private val EndIconSize = 32.dp
private val BubbleSize = 28.dp

/**
 * A two-second silent picture of how uMessage sorts texts: a text from a saved contact flies to the Inbox, one from a
 * stranger to Spam, a stranger's code still to the Inbox. It plays once (not again after a rotation) and shows its
 * final frame when [reduceMotion] is on. It has no words, so one description covers it for TalkBack.
 */
@Composable
internal fun WelcomeAnimation(reduceMotion: Boolean, modifier: Modifier = Modifier) {
    var played by rememberSaveable { mutableStateOf(false) }
    val startsFinished = remember { WelcomeTimeline.startsFinished(reduceMotion, played) }
    val clock = remember { Animatable(if (startsFinished) WelcomeTimeline.TOTAL_MS.toFloat() else 0f) }
    LaunchedEffect(Unit) {
        if (!startsFinished) {
            played = true
            clock.animateTo(WelcomeTimeline.TOTAL_MS.toFloat(), tween(WelcomeTimeline.TOTAL_MS, easing = LinearEasing))
        }
    }
    val description = stringResource(R.string.onb_welcome_demo_desc)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clearAndSetSemantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        DemoLanes(timeMs = clock.value)
        Icon(
            imageVector = Symbols.Shield,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .size(40.dp)
                .background(MaterialTheme.colorScheme.background, CircleShape),
        )
    }
}

@Composable
private fun DemoLanes(timeMs: Float) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        WelcomeTimeline.lanes.forEach { DemoLaneRow(it, timeMs) }
    }
}

@Composable
private fun DemoLaneRow(lane: DemoLane, timeMs: Float) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(LaneHeight),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val known = lane.message == DemoMessage.CONTACT
        DemoEndIcon(
            icon = if (known) Symbols.PersonFilled else Symbols.PersonOff,
            tint = if (known) scheme.primary else scheme.onSurfaceVariant,
        )
        DemoFlight(lane, timeMs, Modifier.weight(1f))
        val folderTint = if (lane.toSpam) scheme.error else scheme.success
        DemoEndIcon(
            icon = if (lane.toSpam) Symbols.Block else Symbols.Inbox,
            tint = folderTint,
            modifier = Modifier.alpha(WelcomeTimeline.folderGlow(lane, timeMs)),
        )
    }
}

@Composable
private fun DemoEndIcon(icon: ImageVector, tint: Color, modifier: Modifier = Modifier) {
    Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = modifier.size(EndIconSize))
}

/** The track between sender and folder. The offset mirrors by itself in right-to-left, so the text flies "forward". */
@Composable
private fun DemoFlight(lane: DemoLane, timeMs: Float, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
        val track = constraints.maxWidth - with(LocalDensity.current) { BubbleSize.roundToPx() }
        val travel = WelcomeTimeline.travel(lane, timeMs)
        DemoBubble(
            lane = lane,
            modifier = Modifier
                .offset { IntOffset((travel * track).roundToInt().coerceAtLeast(0), 0) }
                .alpha(WelcomeTimeline.bubbleAlpha(lane, timeMs)),
        )
    }
}

@Composable
private fun DemoBubble(lane: DemoLane, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val stranger = lane.message != DemoMessage.CONTACT
    Box(
        modifier = modifier
            .size(BubbleSize)
            .clip(RoundedCornerShape(8.dp))
            .background(if (stranger) scheme.surfaceVariant else scheme.secondaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        if (lane.message == DemoMessage.CODE) {
            CodeDots(scheme.onSurfaceVariant)
        } else {
            Icon(
                imageVector = Symbols.Sms,
                contentDescription = null,
                tint = if (stranger) scheme.onSurfaceVariant else scheme.onSecondaryContainer,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** Three dots: the look of a verification code. */
@Composable
private fun CodeDots(color: Color) {
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        repeat(3) { Box(Modifier.size(4.dp).background(color, CircleShape)) }
    }
}
