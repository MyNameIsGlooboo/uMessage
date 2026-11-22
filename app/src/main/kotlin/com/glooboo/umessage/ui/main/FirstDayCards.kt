package com.glooboo.umessage.ui.main

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.glooboo.umessage.R
import com.glooboo.umessage.helpers.DefaultSmsRole
import com.glooboo.umessage.helpers.W3cPrefs
import com.glooboo.umessage.ui.components.localizedNumber
import com.glooboo.umessage.ui.components.uiLocale
import com.glooboo.umessage.ui.icons.Symbols
import com.glooboo.umessage.ui.theme.success

/**
 * ID-10: a dismissible card at the top of the inbox with the few steps that make the app trustworthy: the default SMS
 * role, notifications, contacts, a look at the Blocked tab, (optional) battery and (optional) forwarding. Each step
 * reads its real state again when the screen resumes, so a step ticks itself once the user comes back from the system
 * page. It can be closed for good only once every required step is done ([checklistCanClose]).
 * U03: it opens as one line (title and count); a tap shows the steps.
 */
@Composable
fun FirstDayChecklist(
    forwardRuleCount: Int,
    onOpenBlockedTab: () -> Unit,
    onOpenForwarding: () -> Unit,
    modifier: Modifier = Modifier,
    onDone: () -> Unit = {},
) {
    val context = LocalContext.current
    val prefs = remember(context) { W3cPrefs.get(context) }
    var dismissed by remember { mutableStateOf(prefs.checklistDismissed) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    var tick by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        tick++
        onPauseOrDispose { }
    }
    // A request that comes back refused (or cancelled, or "don't ask again") would leave the step stuck: the button
    // then reads "Open settings" and the next tap opens the settings page. Nothing opens by itself.
    var roleRefused by rememberSaveable { mutableStateOf(false) }
    val roleLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        roleRefused = DefaultSmsRole.status(context) !is DefaultSmsRole.Status.Held
        tick++
    }
    if (dismissed) return

    val snapshot = remember(tick, forwardRuleCount) { readChecklist(context, prefs, forwardRuleCount) }
    // A step that cannot be done on this phone is left out of the count.
    val shown = snapshot.steps.filter { it !in snapshot.impossible }
    val count = shown.count { it in snapshot.done }
    val total = shown.size
    val title = checklistHeaderTitle(checklistTitle(snapshot.done, snapshot.impossible), count)
    val shownForMs = remember(tick) { shownForMillis(prefs, System.currentTimeMillis()) }
    val locale = uiLocale()

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.large,
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp)) {
            ChecklistHeader(
                title = titleLabel(title),
                titleArgs = listOf(localizedNumber(count, locale), localizedNumber(total, locale)),
                showProgress = title != ChecklistTitle.Count,
                expanded = expanded,
                canClose = checklistCanClose(snapshot.done, snapshot.impossible, shownForMs),
                onToggle = { expanded = !expanded },
                onClose = {
                    prefs.checklistDismissed = true
                    dismissed = true
                    onDone()
                },
            )
            if (expanded) {
                LinearProgressIndicator(
                    progress = { count.toFloat() / total },
                    modifier = Modifier.fillMaxWidth().padding(end = 12.dp, bottom = 4.dp),
                )
                val actions = StepActions(
                    context,
                    { roleLauncher.launch(it) },
                    onOpenBlockedTab,
                    onOpenForwarding,
                    roleRefused,
                )
                shown.forEach { step ->
                    StepRow(
                        label = stringResource(stepLabel(step)),
                        done = step in snapshot.done,
                        action = stringResource(actionLabel(step, roleRefused)),
                        onAction = { runStepAction(step, actions) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ChecklistHeader(
    @StringRes title: Int,
    titleArgs: List<String>,
    showProgress: Boolean,
    expanded: Boolean,
    canClose: Boolean,
    onToggle: () -> Unit,
    onClose: () -> Unit,
) {
    Row(
        Modifier.clickable(
            onClickLabel = stringResource(if (expanded) R.string.ux8a_checklist_hide else R.string.ux8a_checklist_show),
            role = Role.Button,
            onClick = onToggle,
        ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (title == R.string.setup_checklist_title_count) {
                stringResource(title, titleArgs[0], titleArgs[1])
            } else {
                stringResource(title)
            },
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        if (showProgress) {
            Text(
                stringResource(R.string.w3c_checklist_progress, titleArgs[0], titleArgs[1]),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            if (expanded) Symbols.ExpandLess else Symbols.ExpandMore,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (canClose) {
            IconButton(onClick = onClose) {
                Icon(Symbols.Close, contentDescription = stringResource(R.string.w3c_checklist_dismiss))
            }
        } else {
            Spacer(Modifier.width(12.dp))
        }
    }
}

@StringRes
internal fun actionLabel(step: ChecklistStep, roleRefused: Boolean = false): Int = when {
    step == ChecklistStep.DefaultSms && roleRefused -> R.string.onb_perm_open_settings
    step == ChecklistStep.DefaultSms -> R.string.w3c_checklist_action_set
    else -> R.string.w3c_checklist_action_open
}

/**
 * How long the card has been on screen: the first-shown time is stored on the first call. A stored time in the future
 * (the clock was ahead then) is replaced by [now], so the close button is never held back by a wrong clock for good.
 */
internal fun shownForMillis(prefs: W3cPrefs, now: Long): Long {
    if (prefs.checklistFirstShown == 0L || prefs.checklistFirstShown > now) prefs.checklistFirstShown = now
    return now - prefs.checklistFirstShown
}

private fun titleLabel(title: ChecklistTitle): Int = when (title) {
    ChecklistTitle.Steps -> R.string.w3c_checklist_title
    ChecklistTitle.BasicsDone -> R.string.w3c_checklist_basics_title
    ChecklistTitle.AllDone -> R.string.w3c_checklist_done_title
    ChecklistTitle.Count -> R.string.setup_checklist_title_count
}

private fun stepLabel(step: ChecklistStep): Int = when (step) {
    ChecklistStep.DefaultSms -> R.string.w3c_checklist_step_sms
    ChecklistStep.Notifications -> R.string.w3c_checklist_step_notifications
    ChecklistStep.Contacts -> R.string.setup_checklist_step_contacts
    ChecklistStep.Battery -> R.string.setup_checklist_step_battery
    ChecklistStep.BlockedTab -> R.string.w3c_checklist_step_blocked
    ChecklistStep.Forwarding -> R.string.w3c_checklist_step_forward
}

@Composable
private fun StepRow(label: String, done: Boolean, action: String, onAction: (() -> Unit)?) {
    val doneLabel = stringResource(R.string.w3c_checklist_step_done)
    val state = if (done) {
        Modifier.semantics { stateDescription = doneLabel }
    } else if (onAction != null) {
        Modifier.clickable(role = Role.Button, onClick = onAction)
    } else {
        Modifier
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .then(state),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (done) {
            Icon(
                Symbols.CheckCircleFilled,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.success,
                modifier = Modifier.size(24.dp),
            )
        } else {
            Box(Modifier.size(24.dp).border(2.dp, MaterialTheme.colorScheme.outline, CircleShape))
        }
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (!done && onAction != null) {
            TextButton(onClick = onAction) { Text(action) }
        }
    }
}
