package com.glooboo.umessage.ui.lock

import com.glooboo.umessage.ui.components.uiLocale
import com.glooboo.umessage.ui.components.localizeDigits
import android.content.res.Configuration
import android.text.format.DateUtils
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.glooboo.umessage.R
import com.glooboo.umessage.dialogs.AppLockLogic
import com.glooboo.umessage.dialogs.PatternGeometry
import com.glooboo.umessage.ui.icons.Symbols
import com.glooboo.umessage.ui.theme.UMessageTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.fossify.commons.helpers.PROTECTION_FINGERPRINT
import org.fossify.commons.helpers.PROTECTION_PATTERN
import org.fossify.commons.helpers.PROTECTION_PIN
import kotlin.math.roundToInt

/** Everything the lock screen shows, so it can be previewed without a ViewModel. */
data class LockUiState(
    val protectionType: Int,
    val pinLength: Int = 0,
    val message: LockMessage = LockMessage.Prompt,
    val lockoutSeconds: Int = 0,
    val inputEnabled: Boolean = true,
    val wrongCount: Int = 0,
    /** For a biometric lock: the way in when biometrics fail. */
    val fallback: LockFallback = LockFallback.None,
    /** A biometric lock showing its backup PIN pad. */
    val usingPin: Boolean = false,
    /** The phone has a screen lock, offered as "Forgot your PIN?". */
    val screenLockAvailable: Boolean = false,
    /** Wrong entries left before a wait, shown with "Wrong PIN". */
    val triesLeft: Int = 0,
    /** The heading; a confirmation (LockActivity.EXTRA_CONFIRM) says what it is for instead of "locked". */
    @androidx.annotation.StringRes val title: Int = R.string.lock_title,
) {
    /** What is asked for right now. */
    val inputType: Int get() = if (usingPin) PROTECTION_PIN else protectionType
}

class LockCallbacks(
    val onDigit: (Char) -> Unit = {},
    val onDelete: () -> Unit = {},
    val onConfirm: () -> Unit = {},
    val onPattern: (List<Int>) -> Unit = {},
    val onBiometric: () -> Unit = {},
    val onUsePin: () -> Unit = {},
    val onUseBiometrics: () -> Unit = {},
    /** The phone's screen lock (older biometric-only locks), or opening when nothing is left to check. */
    val onScreenLock: () -> Unit = {},
)

@Composable
fun LockScreen(
    viewModel: LockViewModel,
    onBiometric: () -> Unit,
    onScreenLock: () -> Unit,
    onLeave: () -> Unit,
    @androidx.annotation.StringRes title: Int = R.string.lock_title,
) {
    val haptic = LocalHapticFeedback.current
    val state = LockUiState(
        protectionType = viewModel.protectionType,
        pinLength = viewModel.pinLength,
        message = viewModel.message,
        lockoutSeconds = viewModel.lockoutSeconds,
        inputEnabled = viewModel.inputEnabled,
        wrongCount = viewModel.wrongCount,
        fallback = viewModel.fallback,
        usingPin = viewModel.usingPin,
        screenLockAvailable = viewModel.deviceSecure,
        triesLeft = viewModel.triesLeft,
        title = title,
    )
    val callbacks = remember(viewModel) {
        LockCallbacks(
            onDigit = { if (viewModel.addDigit(it)) haptic.performHapticFeedback(HapticFeedbackType.KeyboardTap) },
            onDelete = { if (viewModel.deleteDigit()) haptic.performHapticFeedback(HapticFeedbackType.KeyboardTap) },
            onConfirm = {
                haptic.performHapticFeedback(HapticFeedbackType.KeyboardTap)
                viewModel.confirmPin()
            },
            onPattern = viewModel::submitPattern,
            onBiometric = onBiometric,
            onUsePin = { viewModel.usePin() },
            onUseBiometrics = {
                viewModel.useBiometrics()
                onBiometric()
            },
            onScreenLock = onScreenLock,
        )
    }
    LaunchedEffect(viewModel.wrongCount) {
        if (viewModel.wrongCount > 0) haptic.performHapticFeedback(HapticFeedbackType.Reject)
    }

    // Back never reveals the screen behind: it leaves the app, with the predictive back gesture shrinking the lock
    // screen as a preview of that.
    var backProgress by remember { mutableFloatStateOf(0f) }
    val latestLeave by rememberUpdatedState(onLeave)
    PredictiveBackHandler { events ->
        try {
            events.collect { backProgress = it.progress }
            latestLeave()
        } catch (e: CancellationException) {
            backProgress = 0f
            throw e
        }
    }
    LockContent(
        state = state,
        callbacks = callbacks,
        modifier = Modifier.graphicsLayer {
            val scale = 1f - BACK_SCALE * backProgress
            scaleX = scale
            scaleY = scale
        },
    )
}

@Composable
fun LockContent(state: LockUiState, callbacks: LockCallbacks, modifier: Modifier = Modifier) {
    val shake = remember { Animatable(0f) }
    LaunchedEffect(state.wrongCount) {
        if (state.wrongCount == 0) return@LaunchedEffect
        for (target in listOf(-12f, 10f, -6f, 3f, 0f)) {
            shake.animateTo(target, spring(stiffness = Spring.StiffnessHigh))
        }
    }
    Surface(color = MaterialTheme.colorScheme.surface, modifier = modifier.fillMaxSize()) {
        BoxWithConstraints(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 24.dp, vertical = 16.dp),
        ) {
            val width = maxWidth
            val height = maxHeight
            val sideBySide = width > height && height < 560.dp
            val shakeOffset = Modifier.offset { IntOffset(shake.value.dp.roundToPx(), 0) }
            if (sideBySide) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxSize()) {
                    Header(state, shakeOffset.weight(1f))
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.weight(1f).fillMaxHeight()) {
                        Input(state, callbacks, width / 2, height)
                    }
                }
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxSize()) {
                    Spacer(Modifier.weight(1f))
                    Header(state, shakeOffset)
                    Spacer(Modifier.height(32.dp))
                    Input(state, callbacks, width, height - HEADER_HEIGHT)
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun Header(state: LockUiState, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(64.dp).background(colors.primaryContainer, CircleShape),
        ) {
            Icon(Symbols.Lock, contentDescription = null, tint = colors.onPrimaryContainer, modifier = Modifier.size(32.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(state.title),
            style = MaterialTheme.typography.headlineSmall,
            color = colors.onSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        val error = state.message != LockMessage.Prompt
        // Announced by TalkBack whenever it changes; the ticking wait below is not, so it does not talk every second.
        Text(
            text = when (state.message) {
                LockMessage.Prompt -> stringResource(promptRes(state.inputType))
                LockMessage.Wrong -> {
                    val wrong = stringResource(if (state.inputType == PROTECTION_PATTERN) R.string.lock_wrong_pattern else R.string.lock_wrong_pin)
                    if (state.triesLeft in 1 until AppLockLogic.MAX_RETRIES) {
                        wrong + "\n" + pluralStringResource(R.plurals.lock_tries_left, state.triesLeft, state.triesLeft)
                    } else {
                        wrong
                    }
                }
                LockMessage.LockedOut -> stringResource(R.string.lock_too_many)
                LockMessage.BiometricsUnavailable -> stringResource(R.string.lock_biometrics_unavailable)
            },
            style = MaterialTheme.typography.bodyLarge,
            color = if (error) colors.error else colors.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        Box(contentAlignment = Alignment.Center, modifier = Modifier.heightIn(min = 24.dp).padding(top = 4.dp)) {
            if (state.lockoutSeconds > 0) {
                Text(
                    stringResource(R.string.lock_try_again_in, DateUtils.formatElapsedTime(state.lockoutSeconds.toLong())),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.error,
                    textAlign = TextAlign.Center,
                )
            }
        }
        if (state.inputType == PROTECTION_PIN) {
            Spacer(Modifier.height(16.dp))
            PinDots(state.pinLength)
        }
    }
}

private fun promptRes(type: Int) = when (type) {
    PROTECTION_PATTERN -> R.string.lock_draw_pattern
    PROTECTION_PIN -> R.string.lock_enter_pin
    else -> R.string.lock_use_biometrics
}

/** One dot per typed digit, never the digits themselves. */
@Composable
private fun PinDots(length: Int) {
    val description = if (length == 0) {
        stringResource(R.string.lock_no_digits)
    } else {
        pluralStringResource(R.plurals.lock_digits_entered, length, length)
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.height(24.dp).semantics { contentDescription = description },
    ) {
        repeat(length) {
            Box(Modifier.size(14.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
        }
    }
}

@Composable
private fun Input(state: LockUiState, callbacks: LockCallbacks, width: Dp, height: Dp) {
    // Keypad and pattern pad keep their left-to-right layout in right-to-left languages, as on the phone's own lock
    // screen; pattern dots are numbered by position, so mirroring them would also change the pattern.
    val outer = LocalLayoutDirection.current
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            InputPad(state, callbacks, width, height - FORGOT_BUTTON_HEIGHT)
            val forgot = state.screenLockAvailable && state.inputType != PROTECTION_FINGERPRINT &&
                state.fallback != LockFallback.DeviceCredential
            if (forgot) {
                CompositionLocalProvider(LocalLayoutDirection provides outer) {
                    TextButton(onClick = callbacks.onScreenLock) { Text(stringResource(R.string.lock_forgot)) }
                }
            }
        }
    }
}

@Composable
private fun InputPad(state: LockUiState, callbacks: LockCallbacks, width: Dp, height: Dp) {
    if (state.usingPin) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Keypad(state.inputEnabled, callbacks, width, height - FALLBACK_BUTTON_HEIGHT)
            TextButton(onClick = callbacks.onUseBiometrics, modifier = Modifier.padding(top = 8.dp)) {
                Text(stringResource(R.string.lock_use_biometrics_instead))
            }
        }
        return
    }
    when (state.protectionType) {
        PROTECTION_PIN -> Keypad(state.inputEnabled, callbacks, width, height)
        PROTECTION_PATTERN -> PatternPad(
            enabled = state.inputEnabled,
            wrongCount = state.wrongCount,
            locked = state.lockoutSeconds > 0,
            size = minOf(width, height, PATTERN_MAX).coerceAtLeast(KEY_MIN * 3),
            onComplete = callbacks.onPattern,
        )
        PROTECTION_FINGERPRINT -> BiometricInput(state.fallback, callbacks)
    }
}

@Composable
private fun BiometricInput(fallback: LockFallback, callbacks: LockCallbacks) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Symbols.Fingerprint, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(72.dp))
        Spacer(Modifier.height(24.dp))
        if (fallback == LockFallback.Open) {
            // Nothing left to check with (no fingerprints, no screen lock): say so and let the user in.
            Text(
                stringResource(R.string.lock_no_way_to_check),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
            Button(onClick = callbacks.onScreenLock) { Text(stringResource(R.string.lock_open_anyway)) }
            return@Column
        }
        Button(onClick = callbacks.onBiometric) { Text(stringResource(R.string.dialogs_lock_biometric_open)) }
        Spacer(Modifier.height(8.dp))
        when (fallback) {
            LockFallback.Pin -> TextButton(onClick = callbacks.onUsePin) { Text(stringResource(R.string.lock_use_pin_instead)) }
            LockFallback.DeviceCredential ->
                TextButton(onClick = callbacks.onScreenLock) { Text(stringResource(R.string.lock_use_screen_lock)) }
            else -> Unit
        }
    }
}

// region PIN keypad

@Composable
private fun Keypad(enabled: Boolean, callbacks: LockCallbacks, width: Dp, height: Dp) {
    // Keys as big as fit (4 rows, 3 columns), between the 48 dp touch minimum and 80 dp.
    val gap = 16.dp
    val key = minOf((width - gap * 2) / 3, (height - gap * 3) / 4, KEY_MAX).coerceAtLeast(KEY_MIN)
    Column(verticalArrangement = Arrangement.spacedBy(gap), horizontalAlignment = Alignment.CenterHorizontally) {
        listOf("123", "456", "789").forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                row.forEach { digit -> DigitKey(digit, key, enabled) { callbacks.onDigit(digit) } }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
            PadKey(
                size = key,
                enabled = enabled,
                container = Color.Transparent,
                description = stringResource(R.string.lock_key_delete),
                onClick = callbacks.onDelete,
            ) { Icon(Symbols.Backspace, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface) }
            DigitKey('0', key, enabled) { callbacks.onDigit('0') }
            PadKey(
                size = key,
                enabled = enabled,
                container = MaterialTheme.colorScheme.primary,
                description = stringResource(R.string.lock_key_confirm),
                onClick = callbacks.onConfirm,
            ) { Icon(Symbols.Check, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary) }
        }
    }
}

@Composable
private fun DigitKey(digit: Char, size: Dp, enabled: Boolean, onClick: () -> Unit) {
    PadKey(size = size, enabled = enabled, container = MaterialTheme.colorScheme.surfaceContainerHigh, onClick = onClick) {
        Text(localizeDigits(digit.toString(), uiLocale()), style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun PadKey(
    size: Dp,
    enabled: Boolean,
    container: Color,
    onClick: () -> Unit,
    description: String? = null,
    content: @Composable () -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        color = container,
        modifier = Modifier
            .size(size)
            .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.alpha(if (enabled) 1f else DISABLED_ALPHA)) { content() }
    }
}

// endregion

// region Pattern

/**
 * The 3x3 pattern pad. Drawn with a finger as usual; with a screen reader every dot is its own button ("Dot in row 1,
 * column 2"), tapped in order, and Done checks the pattern (Clear starts over), so TalkBack users can unlock too.
 */
@Composable
private fun PatternPad(enabled: Boolean, wrongCount: Int, locked: Boolean, size: Dp, onComplete: (List<Int>) -> Unit) {
    var cells by remember { mutableStateOf(emptyList<Int>()) }
    var finger by remember { mutableStateOf<Offset?>(null) }
    var wrong by remember { mutableStateOf(false) }
    // True while the pattern is being entered dot by dot (screen reader), so it waits for Done.
    var tapped by remember { mutableStateOf(false) }
    val latestOnComplete by rememberUpdatedState(onComplete)
    val haptic = LocalHapticFeedback.current
    // A wrong pattern stays on the pad in red for a moment, then clears.
    LaunchedEffect(wrongCount) {
        if (wrongCount == 0) return@LaunchedEffect
        wrong = true
        delay(WRONG_PATTERN_MS)
        wrong = false
        cells = emptyList()
        tapped = false
    }
    LaunchedEffect(locked) {
        if (locked) {
            cells = emptyList()
            tapped = false
        }
    }
    val idle = MaterialTheme.colorScheme.onSurfaceVariant
    val active = if (wrong) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    val description = stringResource(R.string.lock_pattern_pad)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(size).semantics { contentDescription = description }) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(enabled) {
                        if (!enabled) return@pointerInput
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            tapped = false
                            var drawn = emptyList<Int>()
                            fun touch(position: Offset) {
                                finger = position
                                val cell = PatternGeometry.cellAt(position.x, position.y, this.size.width.toFloat(), this.size.height.toFloat())
                                if (cell != null) {
                                    val next = AppLockLogic.addPatternCell(drawn, cell)
                                    if (next.size != drawn.size) haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                    drawn = next
                                    cells = drawn
                                }
                            }
                            touch(down.position)
                            down.consume()
                            do {
                                val event = awaitPointerEvent()
                                event.changes.firstOrNull()?.let {
                                    touch(it.position)
                                    it.consume()
                                }
                            } while (event.changes.any { it.pressed })
                            finger = null
                            if (drawn.isNotEmpty()) latestOnComplete(drawn)
                        }
                    },
            ) {
                val cell = this.size.width / AppLockLogic.PATTERN_SIZE
                fun center(index: Int) = Offset(
                    (index % AppLockLogic.PATTERN_SIZE + 0.5f) * cell,
                    (index / AppLockLogic.PATTERN_SIZE + 0.5f) * cell,
                )
                val stroke = 6.dp.toPx()
                val line = active.copy(alpha = if (wrong) 1f else 0.6f)
                for (i in 1 until cells.size) {
                    drawLine(line, center(cells[i - 1]), center(cells[i]), strokeWidth = stroke, cap = StrokeCap.Round)
                }
                val tail = finger
                if (tail != null && cells.isNotEmpty()) {
                    drawLine(line, center(cells.last()), tail, strokeWidth = stroke, cap = StrokeCap.Round)
                }
                for (index in 0 until AppLockLogic.PATTERN_SIZE * AppLockLogic.PATTERN_SIZE) {
                    if (index in cells) {
                        drawCircle(active.copy(alpha = 0.2f), radius = 22.dp.toPx(), center = center(index))
                        drawCircle(active, radius = 9.dp.toPx(), center = center(index))
                    } else {
                        drawCircle(idle, radius = 6.dp.toPx(), center = center(index))
                    }
                }
            }
            PatternDotTargets(cells, enabled) { cell ->
                tapped = true
                cells = AppLockLogic.addPatternCell(cells, cell)
                haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
            }
        }
        if (tapped && cells.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(top = 8.dp)) {
                TextButton(
                    enabled = enabled,
                    onClick = {
                        cells = emptyList()
                        tapped = false
                    },
                ) { Text(stringResource(R.string.lock_pattern_clear)) }
                val chosen = pluralStringResource(R.plurals.lock_pattern_dots_selected, cells.size, cells.size)
                val done = stringResource(R.string.lock_pattern_done)
                TextButton(
                    enabled = enabled,
                    onClick = {
                        tapped = false
                        latestOnComplete(cells)
                    },
                    modifier = Modifier.semantics { contentDescription = "$done, $chosen" },
                ) { Text(done) }
            }
        }
    }
}

/**
 * Nine screen-reader targets laid over the pad, one per dot. They carry semantics only (no pointer input), so drawing
 * with a finger still goes to the pad underneath.
 */
@Composable
private fun PatternDotTargets(cells: List<Int>, enabled: Boolean, onTap: (Int) -> Unit) {
    val n = AppLockLogic.PATTERN_SIZE
    Column(Modifier.fillMaxSize()) {
        for (row in 0 until n) {
            Row(Modifier.weight(1f).fillMaxWidth()) {
                for (col in 0 until n) {
                    val index = row * n + col
                    val place = cells.indexOf(index)
                    val label = if (place >= 0) {
                        stringResource(R.string.lock_pattern_dot_selected, row + 1, col + 1, place + 1)
                    } else {
                        stringResource(R.string.lock_pattern_dot, row + 1, col + 1)
                    }
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .semantics {
                                contentDescription = label
                                role = Role.Button
                                selected = place >= 0
                                if (!enabled) disabled()
                                onClick {
                                    if (enabled && place < 0) onTap(index)
                                    enabled
                                }
                            },
                    )
                }
            }
        }
    }
}

// endregion

private const val BACK_SCALE = 0.1f
private const val DISABLED_ALPHA = 0.38f
private const val WRONG_PATTERN_MS = 600L
private val KEY_MIN = 48.dp
private val KEY_MAX = 80.dp
private val PATTERN_MAX = 300.dp
private val HEADER_HEIGHT = 260.dp
private val FALLBACK_BUTTON_HEIGHT = 56.dp
private val FORGOT_BUTTON_HEIGHT = 48.dp

// region Previews

@Preview(name = "PIN light", showBackground = true, widthDp = 360, heightDp = 740)
@Composable
private fun PinLightPreview() = UMessageTheme(darkTheme = false, dynamicColor = false, syncWindow = false) {
    LockContent(LockUiState(PROTECTION_PIN, pinLength = 3), LockCallbacks())
}

@Preview(name = "PIN dark, locked out", showBackground = true, widthDp = 360, heightDp = 740, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun PinDarkPreview() = UMessageTheme(darkTheme = true, dynamicColor = false, syncWindow = false) {
    LockContent(LockUiState(PROTECTION_PIN, message = LockMessage.LockedOut, lockoutSeconds = 42, inputEnabled = false), LockCallbacks())
}

@Preview(name = "Pattern dark", showBackground = true, widthDp = 360, heightDp = 740, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun PatternDarkPreview() = UMessageTheme(darkTheme = true, dynamicColor = false, syncWindow = false) {
    LockContent(LockUiState(PROTECTION_PATTERN, message = LockMessage.Wrong), LockCallbacks())
}

@Preview(name = "PIN landscape", showBackground = true, widthDp = 740, heightDp = 360)
@Composable
private fun PinLandscapePreview() = UMessageTheme(darkTheme = false, dynamicColor = false, syncWindow = false) {
    LockContent(LockUiState(PROTECTION_PIN, pinLength = 5), LockCallbacks())
}

@Preview(name = "Biometric light", showBackground = true, widthDp = 360, heightDp = 740)
@Composable
private fun BiometricLightPreview() = UMessageTheme(darkTheme = false, dynamicColor = false, syncWindow = false) {
    LockContent(LockUiState(PROTECTION_FINGERPRINT), LockCallbacks())
}

// endregion
