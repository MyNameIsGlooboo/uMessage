package com.glooboo.umessage.ui.main

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.glooboo.umessage.helpers.DefaultSmsRole
import com.glooboo.umessage.helpers.W3cPrefs
import com.glooboo.umessage.spam.ForwardingAvailability
import com.glooboo.umessage.ui.setup.setupScreenIntent

/** What the card read from the phone this time: the steps it lists, the ones done, and the ones that cannot be done. */
internal class ChecklistSnapshot(
    val steps: List<ChecklistStep>,
    val done: Set<ChecklistStep>,
    val impossible: Set<ChecklistStep>,
)

/**
 * The optional battery step is listed only where the battery banner would show too ([batteryListed]), and it is done
 * exactly when that guide is no longer needed, so a plain phone can finish and a vendor step left open is not hidden.
 */
internal fun readChecklist(
    context: Context,
    prefs: W3cPrefs,
    forwardRuleCount: Int,
    batteryListed: Boolean = batteryGuideNeeded(context),
): ChecklistSnapshot {
    val role = DefaultSmsRole.status(context)
    val forwardingAvailable = ForwardingAvailability.isAvailable(context)
    val inputs = DoneInputs(
        smsHeld = role is DefaultSmsRole.Status.Held,
        notificationsOn = context.currentNotificationsBlock() == null,
        contactsOn = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED,
        blockedSeen = prefs.blockedTabVisited,
        batteryDone = !batteryListed,
    )
    return ChecklistSnapshot(
        steps = listedSteps(forwardingAvailable, batteryListed),
        done = doneSteps(inputs, forwardingDone(forwardRuleCount, forwardingAvailable)),
        // Where Android has no SMS role to give, the default-app step can never be done on this phone.
        impossible = if (role is DefaultSmsRole.Status.Unavailable) setOf(ChecklistStep.DefaultSms) else emptySet(),
    )
}

private fun listedSteps(forwardingAvailable: Boolean, batteryListed: Boolean) = ChecklistStep.entries.filter {
    when (it) {
        ChecklistStep.Forwarding -> forwardingAvailable
        ChecklistStep.Battery -> batteryListed
        else -> true
    }
}

/** Without forwarding the optional step counts as done, so it cannot hold the card. */
private fun forwardingDone(forwardRuleCount: Int, forwardingAvailable: Boolean) =
    forwardRuleCount > 0 || !forwardingAvailable

/** What the card read from the phone this time. */
private class DoneInputs(
    val smsHeld: Boolean,
    val notificationsOn: Boolean,
    val contactsOn: Boolean,
    val blockedSeen: Boolean,
    val batteryDone: Boolean,
)

private fun doneSteps(inputs: DoneInputs, forwarding: Boolean) = buildSet {
    if (inputs.smsHeld) add(ChecklistStep.DefaultSms)
    if (inputs.notificationsOn) add(ChecklistStep.Notifications)
    if (inputs.contactsOn) add(ChecklistStep.Contacts)
    if (inputs.blockedSeen) add(ChecklistStep.BlockedTab)
    if (inputs.batteryDone) add(ChecklistStep.Battery)
    if (forwarding) add(ChecklistStep.Forwarding)
}

/** What a tap on a step's button needs. */
internal class StepActions(
    val context: Context,
    val launchRole: (Intent) -> Unit,
    val onOpenBlockedTab: () -> Unit,
    val onOpenForwarding: () -> Unit,
    /** The role dialog was already refused: the Set button is "Open settings" and goes to the settings page. */
    val roleRefused: Boolean = false,
)

internal fun runStepAction(step: ChecklistStep, actions: StepActions) {
    val context = actions.context
    when (step) {
        ChecklistStep.DefaultSms -> requestDefaultSms(actions)
        ChecklistStep.Notifications -> runCatching {
            context.startActivity(context.notificationsFixIntent(currentBlock(context)))
        }
        ChecklistStep.Contacts, ChecklistStep.Battery ->
            runCatching { context.startActivity(context.setupScreenIntent()) }
        ChecklistStep.BlockedTab -> actions.onOpenBlockedTab()
        ChecklistStep.Forwarding -> actions.onOpenForwarding()
    }
}

/** What a tap on the default-SMS step does. */
internal enum class RoleStepAction { ASK, OPEN_SETTINGS, NOTHING }

/** Ask while the dialog can be shown and was not refused; otherwise open the settings page. */
internal fun roleStepAction(status: DefaultSmsRole.Status, refused: Boolean): RoleStepAction = when {
    status is DefaultSmsRole.Status.Held -> RoleStepAction.NOTHING
    refused || status !is DefaultSmsRole.Status.Request -> RoleStepAction.OPEN_SETTINGS
    else -> RoleStepAction.ASK
}

/** Asks for the role; where it cannot be asked (refused, not offered, or the dialog cannot open) settings open. */
private fun requestDefaultSms(actions: StepActions) {
    val status = DefaultSmsRole.status(actions.context)
    val launched = when (roleStepAction(status, actions.roleRefused)) {
        RoleStepAction.NOTHING -> return
        RoleStepAction.ASK -> status is DefaultSmsRole.Status.Request &&
            runCatching { actions.launchRole(status.intent) }.isSuccess
        RoleStepAction.OPEN_SETTINGS -> false
    }
    if (!launched) actions.context.openDefaultAppsSettings()
}

/** The system page where the default SMS app is chosen; the general settings page when that one is missing. */
internal fun Context.openDefaultAppsSettings() {
    val flags = Intent.FLAG_ACTIVITY_NEW_TASK
    val opened = runCatching {
        startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS).addFlags(flags))
    }.isSuccess
    if (!opened) runCatching { startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(flags)) }
}

private fun currentBlock(context: Context): NotificationsBlock =
    context.currentNotificationsBlock() ?: NotificationsBlock.APP
