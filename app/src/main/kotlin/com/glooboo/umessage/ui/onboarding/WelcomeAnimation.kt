package com.glooboo.umessage.ui.onboarding

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.glooboo.umessage.R
import com.glooboo.umessage.spam.UnknownSenderMode
import com.glooboo.umessage.ui.components.rememberReduceMotion
import com.glooboo.umessage.ui.icons.Symbols
import com.glooboo.umessage.ui.theme.success
import kotlinx.coroutines.launch

private const val STACK_FONT_SCALE = 1.3f
private val StackMaxWidth = 300.dp
private const val SUCCESS_TINT_ALPHA = 0.14f
private val CardShape = RoundedCornerShape(20.dp)
private val PillShape = RoundedCornerShape(15.dp)

/** The welcome page's card: Smart mode, played from the start of the clock. */
@Composable
internal fun WelcomeAnimation(reduceMotion: Boolean, modifier: Modifier = Modifier) =
    SortingCard(modifier, startAtMs = 0, reduceMotion = reduceMotion)

/**
 * A labelled card of three rows that shows how uMessage sorts texts: Contacts to Messages, an unknown sender to
 * Spam or Messages, a verification code to Messages or Spam, as true for [mode] (see [WelcomeTimeline.rowsFor]).
 * The rows light up one after another, once, from [startAtMs] on the clock when [autoPlay] is on; otherwise, and
 * after a rotation or with [reduceMotion], it shows the finished card. Tapping it replays the animation (not with
 * [reduceMotion]). In Smart mode one sentence describes it for TalkBack; in the other modes each row reads its two
 * labels. A mode change redraws the rows at once, without a replay.
 */
@Composable
internal fun SortingCard(
    modifier: Modifier = Modifier,
    mode: UnknownSenderMode = UnknownSenderMode.SMART,
    autoPlay: Boolean = true,
    startAtMs: Int = WelcomeTimeline.REPLAY_FROM_MS,
    reduceMotion: Boolean = rememberReduceMotion(),
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainer,
) {
    var played by rememberSaveable { mutableStateOf(false) }
    val startsFinished = remember { WelcomeTimeline.startsFinished(reduceMotion, played || !autoPlay) }
    val end = WelcomeTimeline.TOTAL_MS.toFloat()
    val clock = remember { Animatable(if (startsFinished) end else startAtMs.toFloat()) }
    LaunchedEffect(Unit) {
        if (!startsFinished) {
            played = true
            clock.animateTo(end, tween(WelcomeTimeline.TOTAL_MS - startAtMs, easing = LinearEasing))
        }
    }
    val scope = rememberCoroutineScope()
    val smart = mode == UnknownSenderMode.SMART
    val description = stringResource(R.string.onb_welcome_demo_desc)
    // The clock is read only inside graphicsLayer blocks, so a frame redraws but does not recompose.
    val time = remember(clock) { { clock.value } }
    val replay: () -> Unit = {
        scope.launch {
            val from = WelcomeTimeline.REPLAY_FROM_MS
            clock.snapTo(from.toFloat())
            clock.animateTo(end, tween(WelcomeTimeline.TOTAL_MS - from, easing = LinearEasing))
        }
    }
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(containerColor)
            .then(if (smart) Modifier.clearAndSetSemantics { contentDescription = description } else Modifier)
            .replayable(enabled = !reduceMotion, ripple = smart, onReplay = replay),
    ) {
        val stacked = LocalDensity.current.fontScale > STACK_FONT_SCALE || maxWidth < StackMaxWidth
        val rowModifier = if (smart) Modifier else Modifier.semantics(mergeDescendants = true) {}
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            WelcomeTimeline.rowsFor(mode).forEachIndexed { index, row ->
                DemoRowItem(row, index, stacked, time, rowModifier)
            }
        }
    }
}

/**
 * Makes the card tappable. With [ripple] the tap is a click (Smart: the card has one description, and the click is
 * not announced); without it the tap is only a gesture, so TalkBack adds no "double tap to activate".
 */
private fun Modifier.replayable(enabled: Boolean, ripple: Boolean, onReplay: () -> Unit): Modifier = when {
    !enabled -> this
    ripple -> clickable(onClick = onReplay)
    else -> pointerInput(Unit) { detectTapGestures { onReplay() } }
}

/** Lights this part up with [alpha] and pops it with [scale], both read from the clock on every frame. */
private fun Modifier.timed(
    time: () -> Float,
    alpha: (Float) -> Float,
    scale: (Float) -> Float = { 1f },
): Modifier = graphicsLayer {
    val now = time()
    this.alpha = alpha(now)
    scaleX = scale(now)
    scaleY = scale(now)
}

@Composable
private fun DemoRowItem(
    row: DemoRow,
    index: Int,
    stacked: Boolean,
    time: () -> Float,
    modifier: Modifier = Modifier,
) {
    val avatar = Modifier.timed(time, { WelcomeTimeline.senderAlpha(index, it) })
    val arrow = Modifier.timed(time, { WelcomeTimeline.arrowAlpha(index, it) })
    val pill = Modifier.timed(
        time,
        alpha = { WelcomeTimeline.pillAlpha(index, it) },
        scale = { WelcomeTimeline.pillScale(index, it) },
    )
    if (stacked) {
        Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            DemoAvatar(row.sender, avatar)
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                DemoSenderLabel(row, maxLines = Int.MAX_VALUE, modifier = avatar)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DemoArrow(arrow)
                    Spacer(Modifier.width(4.dp))
                    DemoPill(row, wrap = true, modifier = pill.weight(1f, fill = false))
                }
            }
        }
    } else {
        Row(
            modifier = modifier.fillMaxWidth().heightIn(min = 44.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DemoAvatar(row.sender, avatar)
            Spacer(Modifier.width(8.dp))
            DemoSenderLabel(row, maxLines = 2, modifier = avatar.weight(1f))
            Spacer(Modifier.width(4.dp))
            DemoArrow(arrow)
            Spacer(Modifier.width(4.dp))
            DemoPill(row, wrap = false, modifier = pill)
        }
    }
}

@Composable
private fun DemoAvatar(sender: DemoSender, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val icon: ImageVector
    val background: Color
    val content: Color
    when (sender) {
        DemoSender.CONTACT -> {
            icon = Symbols.PersonFilled
            background = scheme.secondaryContainer
            content = scheme.onSecondaryContainer
        }
        DemoSender.UNKNOWN -> {
            icon = Symbols.PersonOff
            background = scheme.surfaceContainerHighest
            content = scheme.onSurfaceVariant
        }
        DemoSender.CODE -> {
            icon = Symbols.Tag
            background = scheme.surfaceContainerHighest
            content = scheme.onSurfaceVariant
        }
    }
    Row(
        modifier = modifier.size(32.dp).background(background, CircleShape),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = content, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun DemoSenderLabel(row: DemoRow, maxLines: Int, modifier: Modifier = Modifier) {
    Text(
        text = stringResource(row.senderLabel),
        modifier = modifier,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

/** The arrow is an auto-mirrored symbol: it points forward in left-to-right and right-to-left alike. */
@Composable
private fun DemoArrow(modifier: Modifier = Modifier) {
    Icon(
        imageVector = Symbols.ArrowForward,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.size(16.dp),
    )
}

/** The destination, with the icon and the name of the real tab. Colour is never the only signal. */
@Composable
private fun DemoPill(row: DemoRow, wrap: Boolean, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val background = if (row.toSpam) scheme.errorContainer else scheme.success.copy(alpha = SUCCESS_TINT_ALPHA)
    val content = if (row.toSpam) scheme.onErrorContainer else scheme.success
    Row(
        modifier = modifier
            .heightIn(min = 30.dp)
            .background(background, PillShape)
            .padding(start = 8.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (row.toSpam) Symbols.Shield else Symbols.ChatBubble,
            contentDescription = null,
            tint = content,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = stringResource(WelcomeTimeline.destinationLabel(row)),
            style = MaterialTheme.typography.labelLarge,
            color = content,
            maxLines = if (wrap) 2 else 1,
            softWrap = wrap,
        )
    }
}
