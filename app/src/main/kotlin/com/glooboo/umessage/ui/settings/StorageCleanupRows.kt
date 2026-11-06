package com.glooboo.umessage.ui.settings

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.glooboo.umessage.R
import com.glooboo.umessage.helpers.RetentionRunner
import com.glooboo.umessage.helpers.cleanupPrefs
import com.glooboo.umessage.ui.components.ChoiceDialog
import com.glooboo.umessage.ui.components.ConfirmDialog
import com.glooboo.umessage.ui.components.SettingsRow
import com.glooboo.umessage.ui.components.SettingsSwitchRow
import com.glooboo.umessage.ui.icons.Symbols
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.fossify.commons.helpers.ensureBackgroundThread

private const val DAYS_WEEK = 7
private const val DAYS_MONTH = 30
private const val DAYS_QUARTER = 90
private const val YEAR_DAYS = 365
private val CODE_CHOICES = listOf(0, 1, DAYS_WEEK, DAYS_MONTH)
private val KEEP_CHOICES = listOf(0, DAYS_MONTH, DAYS_QUARTER, YEAR_DAYS)

/**
 * I02 and I45: the two rows of Settings > Storage and cleanup that clean up by themselves. Both only move messages to
 * the recycle bin, so the page shows them only while the bin is on (U26); the ask-first dialog stays as a safeguard.
 * They read and write [com.glooboo.umessage.helpers.CleanupPrefs] themselves; [onBinChange] is the host's switch of
 * the recycle bin.
 */
@Composable
fun StorageCleanupRows(binOn: Boolean, onBinChange: (Boolean) -> Unit) {
    CodesCleanupRow(binOn, onBinChange)
    KeepMessagesRow(binOn, onBinChange)
}

/** After an age was chosen: moves what is already older than that, off the main thread. */
private fun moveDueNow(context: Context) {
    ensureBackgroundThread { RetentionRunner(context).runNow(System.currentTimeMillis()) }
}

/** I02: "Delete read codes after" Never / 24 hours / 7 days / 30 days. */
@Composable
private fun CodesCleanupRow(binOn: Boolean, onBinChange: (Boolean) -> Unit) {
    val context = LocalContext.current
    val prefs = remember(context) { context.cleanupPrefs }
    var days by remember { mutableIntStateOf(prefs.codeDays) }
    var picking by remember { mutableStateOf(false) }
    var needBin by remember { mutableStateOf<Int?>(null) }
    val applyChoice = { chosen: Int ->
        prefs.codeDays = chosen
        days = chosen
        if (chosen > 0) moveDueNow(context)
    }
    SettingsRow(
        title = stringResource(R.string.cleanup_codes_title),
        summary = stringResource(R.string.cleanup_codes_summary),
        value = cleanupLabel(days, R.string.cleanup_never),
        icon = Symbols.Code,
        onClick = { picking = true },
        showChevron = true,
    )
    if (picking) {
        ChoiceDialog(
            title = stringResource(R.string.cleanup_codes_title),
            options = CODE_CHOICES.map { cleanupLabel(it, R.string.cleanup_never) },
            selectedIndex = CODE_CHOICES.indexOf(days),
            onSelect = {
                picking = false
                val chosen = CODE_CHOICES[it]
                if (chosen == 0 || binOn) applyChoice(chosen) else needBin = chosen
            },
            onDismiss = { picking = false },
        )
    }
    needBin?.let { chosen ->
        BinNeededDialog(
            onConfirm = {
                onBinChange(true)
                applyChoice(chosen)
                needBin = null
            },
            onDismiss = { needBin = null },
        )
    }
}

/** I45: "Keep messages for" Forever / 30 days / 90 days / 1 year, confirmed with the number that would move now. */
@Composable
private fun KeepMessagesRow(binOn: Boolean, onBinChange: (Boolean) -> Unit) {
    val context = LocalContext.current
    val prefs = remember(context) { context.cleanupPrefs }
    var days by remember { mutableIntStateOf(prefs.oldMessageDays) }
    var picking by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<Int?>(null) }
    var binAsked by remember { mutableStateOf(false) }
    SettingsRow(
        title = stringResource(R.string.cleanup_old_title),
        summary = stringResource(R.string.cleanup_old_summary),
        value = cleanupLabel(days, R.string.cleanup_forever),
        icon = Symbols.Schedule,
        onClick = { picking = true },
        showChevron = true,
    )
    if (picking) {
        ChoiceDialog(
            title = stringResource(R.string.cleanup_old_title),
            options = KEEP_CHOICES.map { cleanupLabel(it, R.string.cleanup_forever) },
            selectedIndex = KEEP_CHOICES.indexOf(days),
            onSelect = {
                picking = false
                val chosen = KEEP_CHOICES[it]
                if (chosen == 0) {
                    prefs.oldMessageDays = 0
                    days = 0
                } else {
                    binAsked = !binOn
                    pending = chosen
                }
            },
            onDismiss = { picking = false },
        )
    }
    val chosen = pending ?: return
    if (binAsked) {
        BinNeededDialog(
            onConfirm = {
                onBinChange(true)
                binAsked = false
            },
            onDismiss = { pending = null },
        )
    } else {
        ConfirmMoveDialog(chosen, onDismiss = { pending = null }) {
            prefs.oldMessageDays = chosen
            days = chosen
            pending = null
            moveDueNow(context)
        }
    }
}

@Composable
private fun BinNeededDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    ConfirmDialog(
        title = stringResource(R.string.cleanup_bin_title),
        message = stringResource(R.string.cleanup_bin_body),
        confirmLabel = stringResource(R.string.cleanup_bin_confirm),
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}

/** Says how many messages are older than [days] and would move to the recycle bin now, then asks. */
@Composable
private fun ConfirmMoveDialog(days: Int, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val context = LocalContext.current
    var count by remember(days) { mutableStateOf<Int?>(null) }
    LaunchedEffect(days) {
        count = withContext(Dispatchers.IO) { RetentionRunner(context).countOld(days, System.currentTimeMillis()) }
    }
    val shown = count ?: return
    ConfirmDialog(
        title = stringResource(R.string.cleanup_old_confirm_title),
        message = if (shown == 0) {
            stringResource(R.string.cleanup_old_confirm_none)
        } else {
            pluralStringResource(R.plurals.cleanup_old_confirm_body, shown, shown)
        },
        confirmLabel = stringResource(R.string.cleanup_old_confirm_action),
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}

@Composable
private fun cleanupLabel(days: Int, @StringRes zeroLabel: Int): String = when (days) {
    0 -> stringResource(zeroLabel)
    1 -> stringResource(R.string.cleanup_24_hours)
    YEAR_DAYS -> stringResource(R.string.cleanup_one_year)
    else -> pluralStringResource(R.plurals.cleanup_days, days, days)
}

/**
 * I08: the switch of the weekly summary of blocked messages, in Settings > Notifications. Off on a new install
 * (U42).
 */
@Composable
fun WeeklyDigestRow() {
    val context = LocalContext.current
    val prefs = remember(context) { context.cleanupPrefs }
    var on by remember { mutableStateOf(prefs.digestOn) }
    SettingsSwitchRow(
        title = stringResource(R.string.digest_row_title),
        summary = stringResource(R.string.digest_row_summary),
        icon = Symbols.CalendarMonth,
        checked = on,
        onCheckedChange = {
            on = it
            prefs.digestOn = it
        },
    )
}
