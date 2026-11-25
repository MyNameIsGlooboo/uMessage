package com.glooboo.umessage.ui.setup

import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.glooboo.umessage.R
import com.glooboo.umessage.helpers.ChannelHealth
import com.glooboo.umessage.helpers.ChannelProblem
import com.glooboo.umessage.helpers.OemHelper
import com.glooboo.umessage.helpers.OemStepKind
import com.glooboo.umessage.helpers.StepState
import com.glooboo.umessage.ui.icons.Symbols
import com.glooboo.umessage.ui.main.NotificationsBlock
import com.glooboo.umessage.ui.main.notificationsBlock
import com.glooboo.umessage.ui.main.notificationsFixIntent
import com.glooboo.umessage.ui.main.openDefaultAppsSettings
import com.glooboo.umessage.ui.onboarding.OemStepsSection
import com.glooboo.umessage.ui.onboarding.OnboardingActivity
import com.glooboo.umessage.ui.onboarding.openAppSettings
import com.glooboo.umessage.ui.onboarding.permissionItems
import com.glooboo.umessage.ui.theme.success


@Composable
private fun rememberPermissionAsker(onResult: () -> Unit): PermissionAsker {
    val context = LocalContext.current
    val denied = rememberSaveable { mutableStateOf(emptySet<String>()) }
    val rationale = rememberSaveable { mutableStateOf(emptySet<String>()) }
    val asker = remember(context) { PermissionAsker(context, denied, rationale) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        asker.onResult(it)
        onResult()
    }
    asker.launch = { launcher.launch(it) }
    return asker
}

/**
 * "Setup and permissions" (a page of Settings): the default SMS app, each permission and notifications, then the
 * "Keep uMessage running" steps of the first-run guide. Every status is read from Android again on each resume; the
 * only stored state is the guide's "marked done by you", which the card says is not verified.
 */
@Composable
fun SetupPage(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    // Bumped on resume and after every system dialog so every status re-reads live state.
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh++ }
    // The role request came back and uMessage is still not the default: the button then opens the settings page.
    var roleDenied by rememberSaveable { mutableStateOf(false) }
    val roleLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        roleDenied = !OnboardingActivity.isDefaultSmsApp(context)
        refresh++
    }
    val asker = rememberPermissionAsker { refresh++ }

    // R7: one read of the message channels, for the row's mark and its note.
    val live = refresh.let { setupLive(context, roleDenied, asker.denied) }
    val actions = SetupActions(
        requestPermissions = asker::request,
        requestDefaultSms = { requestDefaultSms(context, roleDenied) { roleLauncher.launch(it) } },
        openNotifications = { openNotifications(context, it) },
    )
    val batteryOpen = refresh.let { batteryStepOpen(context) }
    SetupBody(buildSetupRows(context, live, actions), batteryOpen, refresh, modifier)
}

/** The live facts the Setup page reads. Both the page and the Setup row on the main list build from this one read. */
internal fun setupLive(context: Context, roleDenied: Boolean, denied: Set<String>): SetupLive {
    val problem = ChannelHealth.current(context)
    return SetupLive(
        items = permissionItems(),
        isDefault = OnboardingActivity.isDefaultSmsApp(context),
        roleDenied = roleDenied,
        block = notificationsBlockOf(context, problem),
        denied = denied,
        channelProblem = problem,
    )
}

/**
 * B2: true when the Setup page has something the user should fix (the same rows the page builds, plus its battery
 * step). The Setup row on the main list uses it for its summary and its dot; the page shows the same as its headline.
 */
fun setupAttentionNow(context: Context): Boolean = runCatching {
    val live = setupLive(context, roleDenied = false, denied = emptySet())
    val noActions = SetupActions(
        requestPermissions = {},
        requestDefaultSms = {},
        openNotifications = { _ -> },
    )
    val rows = buildSetupRows(context, live, noActions).map { it.headlineState() }
    setupNeedsAttention(rows, backgroundLimited(context))
}.getOrDefault(false)

/** As currentNotificationsBlock, from a channel [problem] this composition has read already. */
private fun notificationsBlockOf(context: Context, problem: ChannelProblem?): NotificationsBlock? = runCatching {
    notificationsBlock(NotificationManagerCompat.from(context).areNotificationsEnabled(), problem)
}.getOrNull()

/**
 * Android itself limits uMessage in the background (Restricted, or the restricted standby bucket). Merely not being
 * on "Unrestricted" is the default for most phones and is not a problem to flag on the main list.
 */
private fun backgroundLimited(context: Context): Boolean =
    OemHelper.backgroundStatus(context).let { it.backgroundRestricted || it.restrictedBucket }

/** The optional battery card is open until Android reports uMessage as unrestricted. */
private fun batteryStepOpen(context: Context): Boolean =
    OemHelper.stateOf(OemStepKind.IGNORE_BATTERY, OemHelper.backgroundStatus(context), false) !=
        StepState.DONE_VERIFIED

private fun requestDefaultSms(context: Context, denied: Boolean, launch: (Intent) -> Unit) {
    val intent = if (denied) null else OnboardingActivity.defaultSmsRequestIntent(context)
    val launched = intent != null && runCatching { launch(intent) }.isSuccess
    if (!launched) context.openDefaultAppsSettings()
}

private fun openNotifications(context: Context, block: NotificationsBlock?) {
    val intent = context.notificationsFixIntent(block ?: NotificationsBlock.APP)
    if (runCatching { context.startActivity(intent) }.isFailure) context.openAppSettings()
}


@Composable
private fun SetupBody(rows: List<SetupRowUi>, batteryOpen: Boolean, refresh: Int, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            stringResource(R.string.setup_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val headline = setupHeadline(rows.map { it.headlineState() }, batteryOpen)
        Text(
            stringResource(
                when (headline) {
                    SetupHeadline.ALL_SET -> R.string.setup_all_set
                    SetupHeadline.OPTIONAL_LEFT -> R.string.setup_optional_left
                    SetupHeadline.SOME_OPEN -> R.string.setup_some_open
                },
            ),
            style = MaterialTheme.typography.titleMedium,
            color = if (headline == SetupHeadline.SOME_OPEN) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.success
            },
        )
        SetupHeading(stringResource(R.string.setup_group_permissions))
        rows.forEach { SetupRowCard(it) }
        SetupHeading(stringResource(R.string.onb_oem_title))
        Text(
            stringResource(R.string.ux8f_onb_battery_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OemStepsSection(refresh, showStatus = true)
        Text(
            stringResource(R.string.onb_oem_manual_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}


@Composable
private fun SetupHeading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp).semantics { heading() },
    )
}

@StringRes
private fun statusText(mark: SetupMark, optional: Boolean): Int = when (mark) {
    SetupMark.DONE -> R.string.oem2_state_done
    SetupMark.NOT_DONE -> if (optional) R.string.setup_status_optional else R.string.oem2_state_todo
    SetupMark.CANNOT_CHECK -> R.string.setup_status_unknown
    SetupMark.MARKED_BY_YOU -> R.string.oem2_state_marked
}

@Composable
private fun SetupRowCard(row: SetupRowUi) {
    // A step this phone cannot do is not "to do": a neutral status, the note, and no button.
    val status = stringResource(statusText(row.mark, row.optional || row.unavailable))
    val done = row.mark == SetupMark.DONE
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top,
                modifier = Modifier.semantics(mergeDescendants = true) { stateDescription = status },
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(row.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        row.reason,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (done) {
                    Icon(Symbols.CheckCircleFilled, null, tint = MaterialTheme.colorScheme.success)
                } else {
                    Box(Modifier.size(24.dp).border(2.dp, MaterialTheme.colorScheme.outline, CircleShape))
                }
            }
            Text(
                status,
                style = MaterialTheme.typography.labelLarge,
                color = if (done) MaterialTheme.colorScheme.success else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clearAndSetSemantics { },
            )
            row.note?.let {
                val color = MaterialTheme.colorScheme.let { if (row.unavailable) it.onSurfaceVariant else it.error }
                Text(it, style = MaterialTheme.typography.bodyMedium, color = color)
            }
            if (!done && !row.unavailable) {
                Button(onClick = row.onAction, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(row.actionRes))
                }
            }
        }
    }
}
