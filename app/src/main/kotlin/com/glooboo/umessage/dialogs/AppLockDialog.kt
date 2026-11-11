package com.glooboo.umessage.dialogs

import android.app.Activity
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.github.ajalt.reprint.core.AuthenticationFailureReason
import com.github.ajalt.reprint.core.AuthenticationListener
import com.github.ajalt.reprint.core.Reprint
import com.glooboo.umessage.R
import com.glooboo.umessage.ui.components.localizeDigits
import com.glooboo.umessage.ui.components.showComposeDialog
import com.glooboo.umessage.ui.components.uiLocale
import com.glooboo.umessage.ui.icons.Symbols
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.fossify.commons.extensions.baseConfig
import org.fossify.commons.extensions.isBiometricAuthSupported
import org.fossify.commons.extensions.showBiometricPrompt
import org.fossify.commons.helpers.BaseConfig
import org.fossify.commons.helpers.PROTECTION_FINGERPRINT
import org.fossify.commons.helpers.PROTECTION_PATTERN
import org.fossify.commons.helpers.PROTECTION_PIN
import org.fossify.commons.helpers.SHOW_ALL_TABS
import org.fossify.commons.helpers.isRPlus

/**
 * App lock setup / check. Same contract as the old dialog: [requiredHash] empty means a new credential is being set,
 * [showTabIndex] is SHOW_ALL_TABS to choose a protection type or one type to ask for it. Reports
 * (hash, type, success).
 *
 * Setting up biometrics also sets up a backup PIN first, so a removed fingerprint or a broken sensor never locks the
 * user out: it reports the PIN's stored value with PROTECTION_FINGERPRINT. Checking a biometric lock that has a backup
 * PIN offers both. PIN is the first tab, being the one that also works well with a screen reader.
 */
fun Activity.showAppLockDialog(
    requiredHash: String,
    showTabIndex: Int,
    callback: (hash: String, type: Int, success: Boolean) -> Unit,
) {
    val biometricSupported = isBiometricAuthSupported()
    val autoBiometric = showTabIndex == PROTECTION_FINGERPRINT && isRPlus()
    showComposeDialog { dismiss ->
        AppLockDialog(
            requiredHash = requiredHash,
            showTabIndex = showTabIndex,
            biometricSupported = biometricSupported,
            autoBiometric = autoBiometric,
            onResult = { hash, type, success ->
                dismiss()
                callback(hash, type, success)
            },
        )
    }
}

@Composable
fun AppLockDialog(
    requiredHash: String,
    showTabIndex: Int,
    biometricSupported: Boolean,
    autoBiometric: Boolean,
    onResult: (hash: String, type: Int, success: Boolean) -> Unit,
) {
    val tabs = remember { dialogTabs(showTabIndex, requiredHash, biometricSupported) }
    var current by remember { mutableIntStateOf(tabs.first()) }
    val succeed = { hash: String, type: Int -> onResult(hash, type, true) }
    AlertDialog(
        onDismissRequest = { onResult("", 0, false) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
                if (tabs.size > 1) {
                    // A scrollable row keeps every label whole ("Biometrics" was clipped in a narrow dialog).
                    ScrollableTabRow(
                        selectedTabIndex = tabs.indexOf(current),
                        containerColor = Color.Transparent,
                        edgePadding = 0.dp,
                    ) {
                        tabs.forEach { type ->
                            Tab(
                                selected = type == current,
                                onClick = { current = type },
                                text = { Text(tabTitle(type), maxLines = 1, softWrap = false, style = MaterialTheme.typography.labelLarge) },
                            )
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                }
                when (current) {
                    PROTECTION_PATTERN -> PatternTab(requiredHash) { hash -> succeed(hash, PROTECTION_PATTERN) }
                    PROTECTION_PIN -> PinTab(requiredHash) { hash -> succeed(hash, PROTECTION_PIN) }
                    else -> if (requiredHash.isEmpty() && showTabIndex == SHOW_ALL_TABS) {
                        BiometricSetupTab { backupHash -> succeed(backupHash, PROTECTION_FINGERPRINT) }
                    } else {
                        BiometricTab(autoBiometric) { succeed(requiredHash, PROTECTION_FINGERPRINT) }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = { onResult("", 0, false) }) { Text(stringResource(R.string.screens_cancel)) }
        },
    )
}

/** The tabs of the dialog, first one selected; see [showAppLockDialog]. */
internal fun dialogTabs(showTabIndex: Int, requiredHash: String, biometricSupported: Boolean): List<Int> = when {
    showTabIndex == SHOW_ALL_TABS -> buildList {
        add(PROTECTION_PIN)
        add(PROTECTION_PATTERN)
        if (biometricSupported) add(PROTECTION_FINGERPRINT)
    }
    showTabIndex == PROTECTION_FINGERPRINT && requiredHash.isNotEmpty() -> listOf(PROTECTION_FINGERPRINT, PROTECTION_PIN)
    else -> listOf(showTabIndex)
}

@Composable
private fun tabTitle(type: Int): String = stringResource(
    when (type) {
        PROTECTION_PATTERN -> R.string.dialogs_lock_pattern
        PROTECTION_PIN -> R.string.dialogs_lock_pin
        else -> if (isRPlus()) R.string.dialogs_lock_biometrics else R.string.dialogs_lock_fingerprint
    },
)

/**
 * Wrong-attempt counter and the wait it causes, kept in the shared config (so it survives restarts) and shared with
 * the lock screen. From the 3rd wrong try on, each one starts a wait that doubles every time; waiting does not reset
 * the count, only a correct entry does. Setting up a new credential (no stored one) never counts.
 */
private class Lockout(private val config: BaseConfig, private val enabled: Boolean) {
    var seconds by mutableIntStateOf(0)
        private set

    init {
        refresh()
    }

    fun refresh() {
        seconds = if (enabled) {
            AppLockLogic.lockoutSeconds(
                config.passwordRetryCount, config.passwordCountdownStartMs, System.currentTimeMillis(),
                config.passwordCountdownStartElapsedMs, runningClock(),
            )
        } else {
            0
        }
    }

    fun reset() {
        if (!enabled) return
        config.passwordRetryCount = 0
        config.passwordCountdownStartMs = 0
        config.passwordCountdownStartElapsedMs = -1L
    }

    /** Wrong entries still allowed before a wait starts; null when attempts are not counted (setting up). */
    fun triesLeft(): Int? = if (enabled) (AppLockLogic.MAX_RETRIES - config.passwordRetryCount).coerceAtLeast(0) else null

    /** Counts a wrong attempt; true when this starts a lockout. */
    fun wrong(): Boolean {
        if (!enabled) return false
        config.passwordRetryCount += 1
        if (config.passwordRetryCount >= AppLockLogic.MAX_RETRIES) {
            config.passwordCountdownStartElapsedMs = runningClock()
            config.passwordCountdownStartMs = System.currentTimeMillis()
        }
        refresh()
        return seconds > 0
    }
}

@Composable
private fun rememberLockout(requiredHash: String): Lockout {
    val context = LocalContext.current
    val lock = remember { Lockout(context.baseConfig, requiredHash.isNotEmpty()) }
    LaunchedEffect(lock.seconds > 0) {
        while (lock.seconds > 0) {
            delay(1000)
            lock.refresh()
        }
    }
    return lock
}

/** The line above a pad: prompt, transient error, or the lockout notice. */
@Composable
private fun LockTitle(prompt: String, error: String?, lockSeconds: Int) {
    val locked = lockSeconds > 0
    Text(
        text = when {
            locked -> stringResource(R.string.dialogs_lock_locked_out, lockSeconds)
            error != null -> error
            else -> prompt
        },
        style = MaterialTheme.typography.titleMedium,
        color = if (locked || error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
    )
}


// region PIN

@Composable
private fun PinTab(requiredHash: String, onSuccess: (hash: String) -> Unit) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val lock = rememberLockout(requiredHash)
    val entry = remember { CredentialEntry(requiredHash, minLength = AppLockLogic.MIN_PIN_LENGTH) }
    var pin by remember { mutableStateOf("") }
    var repeating by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var done by remember { mutableStateOf(false) }
    val wrongText = stringResource(R.string.dialogs_lock_wrong_pin)
    val emptyText = stringResource(R.string.dialogs_lock_empty_pin)
    val shortText = stringResource(R.string.dialogs_lock_short_pin)
    val resources = LocalContext.current.resources

    fun acting() = !done && lock.seconds == 0

    // An error stays until the user types or deletes, so it can be read.
    fun addDigit(digit: String) {
        if (acting() && pin.length < AppLockLogic.MAX_PIN_LENGTH) {
            pin += digit
            error = null
        }
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }

    fun confirm() {
        if (!acting()) return
        when {
            pin.isEmpty() -> Toast.makeText(context, emptyText, Toast.LENGTH_LONG).show()
            else -> when (val result = entry.submit(AppLockLogic.hashPin(pin), pin.length)) {
                CredentialEntry.Result.TooShort -> {
                    pin = ""
                    error = shortText
                    haptic.performHapticFeedback(HapticFeedbackType.Reject)
                }
                CredentialEntry.Result.Repeat -> {
                    pin = ""
                    repeating = true
                }
                is CredentialEntry.Result.Correct -> {
                    done = true
                    lock.reset()
                    scope.launch {
                        delay(CORRECT_DELAY_MS)
                        onSuccess(result.hash)
                    }
                }
                CredentialEntry.Result.Wrong -> {
                    pin = ""
                    if (!lock.wrong()) error = wrongMessage(wrongText, lock.triesLeft(), resources)
                    repeating = entry.isRepeating
                    haptic.performHapticFeedback(HapticFeedbackType.Reject)
                }
            }
        }
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        LockTitle(
            prompt = stringResource(if (repeating) R.string.dialogs_lock_repeat_pin else R.string.dialogs_lock_enter_pin),
            error = error,
            lockSeconds = lock.seconds,
        )
        val enteredDescription = pluralStringResource(R.plurals.dialogs_lock_pin_entered, pin.length, pin.length)
        Text(
            text = "•".repeat(pin.length),
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier
                .height(40.dp)
                .semantics { contentDescription = enteredDescription },
        )
        if (requiredHash.isEmpty() && !repeating) {
            Text(
                stringResource(
                    R.string.lock_pin_length_hint, AppLockLogic.MIN_PIN_LENGTH, AppLockLogic.MAX_PIN_LENGTH
                ) +
                    "\n" +
                    stringResource(R.string.dialogs_lock_pin_suggestion, AppLockLogic.SUGGESTED_PIN_LENGTH),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Text(
                stringResource(R.string.w2b2_lock_recovery_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        // Keypads keep 1 2 3 from left to right in right-to-left languages too, as phone keypads do.
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9")).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                row.forEach { digit ->
                    PadKey(onClick = { addDigit(digit) }) { PadDigit(digit) }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(vertical = 6.dp)) {
            PadKey(
                description = stringResource(R.string.dialogs_lock_pin_delete),
                onClick = {
                    if (pin.isNotEmpty()) pin = pin.dropLast(1)
                    error = null
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                },
            ) { Icon(Symbols.Backspace, contentDescription = null) }
            PadKey(onClick = { addDigit("0") }) { PadDigit("0") }
            PadKey(
                description = stringResource(R.string.dialogs_lock_pin_confirm),
                container = MaterialTheme.colorScheme.primary,
                onClick = {
                    confirm()
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                },
            ) { Icon(Symbols.Check, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary) }
        }
        }
    }
}

/** "Wrong PIN", plus how many tries are left before a wait when that is few. */
private fun wrongMessage(wrong: String, triesLeft: Int?, resources: android.content.res.Resources): String =
    if (triesLeft == null || triesLeft <= 0) {
        wrong
    } else {
        wrong + ". " + resources.getQuantityString(R.plurals.lock_tries_left, triesLeft, triesLeft)
    }

/** A keypad digit in the app's language (۱ ۲ ۳ in Persian), as on the unlock screen; the PIN itself stays 0-9. */
@Composable
private fun PadDigit(digit: String) {
    Text(localizeDigits(digit, uiLocale()), style = MaterialTheme.typography.titleLarge)
}

@Composable
private fun PadKey(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    container: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
    content: @Composable () -> Unit,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(56.dp)
            .clip(CircleShape)
            .background(container)
            .clickable(role = Role.Button, onClick = onClick)
            .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier),
    ) { content() }
}

// endregion

// region Pattern

private enum class PadMode { Normal, Correct, Wrong }

@Composable
private fun PatternTab(requiredHash: String, onSuccess: (hash: String) -> Unit) {
    val scope = rememberCoroutineScope()
    val lock = rememberLockout(requiredHash)
    val entry = remember { CredentialEntry(requiredHash, minLength = AppLockLogic.MIN_PATTERN_DOTS) }
    var repeating by remember { mutableStateOf(false) }
    var mode by remember { mutableStateOf(PadMode.Normal) }
    var resetKey by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    val wrongText = stringResource(R.string.dialogs_lock_wrong_pattern)
    val shortText = pluralStringResource(R.plurals.dialogs_lock_short_pattern, AppLockLogic.MIN_PATTERN_DOTS, AppLockLogic.MIN_PATTERN_DOTS)
    val haptic = LocalHapticFeedback.current
    val resources = LocalContext.current.resources

    // An error stays until the next pattern is drawn.
    fun onPattern(cells: List<Int>) {
        if (lock.seconds > 0 || mode != PadMode.Normal) return
        error = null
        when (val result = entry.submit(AppLockLogic.hashPattern(cells), cells.size)) {
            CredentialEntry.Result.TooShort -> {
                mode = PadMode.Wrong
                error = shortText
                haptic.performHapticFeedback(HapticFeedbackType.Reject)
                scope.launch {
                    delay(WRONG_PATTERN_CLEAR_MS)
                    resetKey++
                    mode = PadMode.Normal
                }
            }
            CredentialEntry.Result.Repeat -> {
                repeating = true
                resetKey++
            }
            is CredentialEntry.Result.Correct -> {
                mode = PadMode.Correct
                lock.reset()
                scope.launch {
                    delay(CORRECT_DELAY_MS)
                    onSuccess(result.hash)
                }
            }
            CredentialEntry.Result.Wrong -> {
                mode = PadMode.Wrong
                if (!lock.wrong()) error = wrongMessage(wrongText, lock.triesLeft(), resources)
                repeating = entry.isRepeating
                haptic.performHapticFeedback(HapticFeedbackType.Reject)
                scope.launch {
                    delay(WRONG_PATTERN_CLEAR_MS)
                    resetKey++
                    mode = PadMode.Normal
                }
            }
        }
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        LockTitle(
            prompt = stringResource(if (repeating) R.string.dialogs_lock_repeat_pattern else R.string.dialogs_lock_draw_pattern),
            error = error,
            lockSeconds = lock.seconds,
        )
        PatternPad(
            mode = mode,
            enabled = lock.seconds == 0 && mode == PadMode.Normal,
            resetKey = resetKey,
            onComplete = ::onPattern,
        )
        if (requiredHash.isEmpty() && !repeating && lock.seconds == 0) {
            Text(
                stringResource(R.string.w2b2_lock_pattern_hint, AppLockLogic.MIN_PATTERN_DOTS),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 12.dp),
            )
            Text(
                stringResource(R.string.w2b2_lock_recovery_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun PatternPad(mode: PadMode, enabled: Boolean, resetKey: Int, onComplete: (List<Int>) -> Unit) {
    var cells by remember(resetKey) { mutableStateOf(emptyList<Int>()) }
    var finger by remember(resetKey) { mutableStateOf<Offset?>(null) }
    val latestOnComplete by rememberUpdatedState(onComplete)
    val haptic = LocalHapticFeedback.current
    val idle = MaterialTheme.colorScheme.onSurfaceVariant
    val active = when (mode) {
        PadMode.Normal, PadMode.Correct -> MaterialTheme.colorScheme.primary
        PadMode.Wrong -> MaterialTheme.colorScheme.error
    }
    val description = stringResource(R.string.dialogs_lock_pattern_pad)
    Canvas(
        modifier = Modifier
            .widthIn(max = 232.dp)
            .fillMaxWidth()
            .aspectRatio(1f)
            .semantics { contentDescription = description }
            .pointerInput(enabled, resetKey) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var drawn = emptyList<Int>()
                    fun touch(position: Offset) {
                        finger = position
                        val cell = PatternGeometry.cellAt(position.x, position.y, size.width.toFloat(), size.height.toFloat())
                        if (cell != null) {
                            val next = AppLockLogic.addPatternCell(drawn, cell)
                            if (next.size != drawn.size) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
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
        val cell = size.width / AppLockLogic.PATTERN_SIZE
        fun center(index: Int) = Offset(
            (index % AppLockLogic.PATTERN_SIZE + 0.5f) * cell,
            (index / AppLockLogic.PATTERN_SIZE + 0.5f) * cell,
        )
        val lineColor = if (mode == PadMode.Normal) active.copy(alpha = 0.6f) else active
        val stroke = 6.dp.toPx()
        for (i in 1 until cells.size) {
            drawLine(lineColor, center(cells[i - 1]), center(cells[i]), strokeWidth = stroke, cap = StrokeCap.Round)
        }
        val tail = finger
        if (tail != null && cells.isNotEmpty()) {
            drawLine(lineColor, center(cells.last()), tail, strokeWidth = stroke, cap = StrokeCap.Round)
        }
        for (index in 0 until AppLockLogic.PATTERN_SIZE * AppLockLogic.PATTERN_SIZE) {
            if (index in cells) {
                drawCircle(active.copy(alpha = 0.2f), radius = 18.dp.toPx(), center = center(index))
                drawCircle(active, radius = 8.dp.toPx(), center = center(index))
            } else {
                drawCircle(idle, radius = 5.dp.toPx(), center = center(index))
            }
        }
    }
}

// endregion

// region Biometrics

/** Biometrics are set up after a backup PIN; [onSuccess] gets the PIN's stored value. */
@Composable
private fun BiometricSetupTab(onSuccess: (backupHash: String) -> Unit) {
    var backup by remember { mutableStateOf<String?>(null) }
    val pin = backup
    if (pin == null) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            Text(
                stringResource(R.string.dialogs_lock_backup_pin),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            PinTab(requiredHash = "") { hash -> backup = hash }
        }
    } else {
        BiometricTab(autoStart = true) { onSuccess(pin) }
    }
}

@Composable
private fun BiometricTab(autoStart: Boolean, onSuccess: () -> Unit) {
    if (isRPlus()) BiometricPromptTab(autoStart, onSuccess) else LegacyFingerprintTab(onSuccess)
}

@Composable
private fun BiometricPromptTab(autoStart: Boolean, onSuccess: () -> Unit) {
    // The hosting activity, not LocalContext (which may be a wrapper): the system prompt is shown through it.
    val activity = LocalActivity.current as? FragmentActivity
    val latestSuccess by rememberUpdatedState(onSuccess)
    val open = {
        activity?.showBiometricPrompt(successCallback = { _, _ -> latestSuccess() })
        Unit
    }
    LaunchedEffect(Unit) { if (autoStart) open() }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Icon(Symbols.Fingerprint, contentDescription = null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(R.string.dialogs_lock_biometric_hint),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = { open() }) { Text(stringResource(R.string.dialogs_lock_biometric_open)) }
    }
}

/** Android 8-10: fingerprint through Reprint, re-checked every 3 seconds like the old tab. */
@Composable
private fun LegacyFingerprintTab(onSuccess: () -> Unit) {
    val context = LocalContext.current
    val latestSuccess by rememberUpdatedState(onSuccess)
    var registered by remember { mutableStateOf(true) }
    val failedText = stringResource(R.string.dialogs_lock_auth_failed)
    val blockedText = stringResource(R.string.dialogs_lock_auth_blocked)
    val listener = remember {
        object : AuthenticationListener {
            override fun onSuccess(moduleTag: Int) = latestSuccess()

            override fun onFailure(
                failureReason: AuthenticationFailureReason?,
                fatal: Boolean,
                errorMessage: CharSequence?,
                moduleTag: Int,
                errorCode: Int,
            ) {
                when (failureReason) {
                    AuthenticationFailureReason.AUTHENTICATION_FAILED ->
                        Toast.makeText(context, failedText, Toast.LENGTH_SHORT).show()
                    AuthenticationFailureReason.LOCKED_OUT ->
                        Toast.makeText(context, blockedText, Toast.LENGTH_SHORT).show()
                    else -> Unit
                }
            }
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            registered = runCatching { Reprint.hasFingerprintRegistered() }.getOrDefault(false)
            runCatching { Reprint.authenticate(listener) }
            delay(RECHECK_MS)
        }
    }
    DisposableEffect(Unit) { onDispose { runCatching { Reprint.cancelAuthentication() } } }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Icon(Symbols.Fingerprint, contentDescription = null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(if (registered) R.string.dialogs_lock_place_finger else R.string.dialogs_lock_no_fingerprints),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        if (!registered) {
            Spacer(Modifier.height(16.dp))
            Button(onClick = { context.startActivity(Intent(Settings.ACTION_SETTINGS)) }) {
                Text(stringResource(R.string.dialogs_lock_open_settings))
            }
        }
    }
}

// endregion

private const val CORRECT_DELAY_MS = 300L
private const val WRONG_PATTERN_CLEAR_MS = 300L
private const val RECHECK_MS = 3000L
