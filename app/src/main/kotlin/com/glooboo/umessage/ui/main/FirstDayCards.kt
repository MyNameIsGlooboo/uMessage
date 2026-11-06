package com.glooboo.umessage.ui.main

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.glooboo.umessage.R
import com.glooboo.umessage.helpers.DefaultSmsRole
import com.glooboo.umessage.helpers.W3cPrefs
import com.glooboo.umessage.spam.ForwardingAvailability
import com.glooboo.umessage.ui.components.localizedNumber
import com.glooboo.umessage.ui.components.readableOn
import com.glooboo.umessage.ui.components.uiLocale
import com.glooboo.umessage.ui.icons.Symbols
import com.glooboo.umessage.ui.theme.success

/**
 * ID-10: a dismissible card at the top of the inbox with the few steps that make the app trustworthy: the default SMS
 * role, notifications, a look at the Blocked tab and (optional) forwarding. Each step reads its real state again when
 * the screen resumes, so a step ticks itself once the user comes back from the system page. Gone for good once closed.
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
    val roleLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { tick++ }
    if (dismissed) return

    val smsHeld = remember(tick) { DefaultSmsRole.status(context) is DefaultSmsRole.Status.Held }
    val notificationsOn = remember(tick) { context.currentNotificationsBlock() == null }
    val blockedSeen = remember(tick) { prefs.blockedTabVisited }
    // The Google Play build has no forwarding: the optional step is not listed or counted, and cannot hold the card.
    val forwardingAvailable = remember(context) { ForwardingAvailability.isAvailable(context) }
    val steps = listedSteps(forwardingAvailable)
    val done = doneSteps(smsHeld, notificationsOn, blockedSeen, forwardingDone(forwardRuleCount, forwardingAvailable))
    val count = shownDoneCount(checklistProgress(done).first, forwardingAvailable)
    val total = steps.size
    val title = titleLabel(checklistTitle(done))
    val locale = uiLocale()

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.large,
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp)) {
            Row(
                Modifier.clickable(
                    onClickLabel = stringResource(
                        if (expanded) R.string.ux8a_checklist_hide else R.string.ux8a_checklist_show,
                    ),
                    role = Role.Button,
                ) { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(title),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
                Text(
                    stringResource(
                        R.string.w3c_checklist_progress,
                        localizedNumber(count, locale),
                        localizedNumber(total, locale),
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Icon(
                    if (expanded) Symbols.ExpandLess else Symbols.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                IconButton(onClick = {
                    prefs.checklistDismissed = true
                    dismissed = true
                    onDone()
                }) {
                    Icon(Symbols.Close, contentDescription = stringResource(R.string.w3c_checklist_dismiss))
                }
            }
            if (expanded) {
                LinearProgressIndicator(
                    progress = { count.toFloat() / total },
                    modifier = Modifier.fillMaxWidth().padding(end = 12.dp, bottom = 4.dp),
                )
            }
            if (expanded) steps.forEach { step ->
                StepRow(
                    label = stringResource(stepLabel(step)),
                    done = step in done,
                    action = stringResource(
                        if (step == ChecklistStep.DefaultSms) {
                            R.string.w3c_checklist_action_set
                        } else {
                            R.string.w3c_checklist_action_open
                        },
                    ),
                    onAction = {
                        when (step) {
                            ChecklistStep.DefaultSms ->
                                (DefaultSmsRole.status(context) as? DefaultSmsRole.Status.Request)
                                    ?.let { roleLauncher.launch(it.intent) }
                            ChecklistStep.Notifications -> runCatching {
                                context.startActivity(context.notificationsFixIntent(currentBlock(context)))
                            }
                            ChecklistStep.BlockedTab -> onOpenBlockedTab()
                            ChecklistStep.Forwarding -> onOpenForwarding()
                        }
                    },
                )
            }
        }
    }
}

private fun listedSteps(forwardingAvailable: Boolean) =
    ChecklistStep.entries.filter { forwardingAvailable || it != ChecklistStep.Forwarding }

/** Without forwarding the optional step counts as done, so it cannot hold the card. */
private fun forwardingDone(forwardRuleCount: Int, forwardingAvailable: Boolean) =
    forwardRuleCount > 0 || !forwardingAvailable

/** The unlisted forwarding step is taken off the count again. */
private fun shownDoneCount(doneCount: Int, forwardingAvailable: Boolean) =
    if (forwardingAvailable) doneCount else doneCount - 1

private fun doneSteps(smsHeld: Boolean, notificationsOn: Boolean, blockedSeen: Boolean, forwarding: Boolean) =
    buildSet {
        if (smsHeld) add(ChecklistStep.DefaultSms)
        if (notificationsOn) add(ChecklistStep.Notifications)
        if (blockedSeen) add(ChecklistStep.BlockedTab)
        if (forwarding) add(ChecklistStep.Forwarding)
    }

private fun currentBlock(context: Context): NotificationsBlock =
    context.currentNotificationsBlock() ?: NotificationsBlock.APP

private fun titleLabel(title: ChecklistTitle): Int = when (title) {
    ChecklistTitle.Steps -> R.string.w3c_checklist_title
    ChecklistTitle.BasicsDone -> R.string.w3c_checklist_basics_title
    ChecklistTitle.AllDone -> R.string.w3c_checklist_done_title
}

private fun stepLabel(step: ChecklistStep): Int = when (step) {
    ChecklistStep.DefaultSms -> R.string.w3c_checklist_step_sms
    ChecklistStep.Notifications -> R.string.w3c_checklist_step_notifications
    ChecklistStep.BlockedTab -> R.string.w3c_checklist_step_blocked
    ChecklistStep.Forwarding -> R.string.w3c_checklist_step_forward
}

@Composable
private fun StepRow(label: String, done: Boolean, action: String, onAction: () -> Unit) {
    val doneLabel = stringResource(R.string.w3c_checklist_step_done)
    val state = if (done) {
        Modifier.semantics { stateDescription = doneLabel }
    } else {
        Modifier.clickable(role = Role.Button, onClick = onAction)
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
        if (!done) {
            TextButton(onClick = onAction) { Text(action) }
        }
    }
}

/**
 * ID-11: shown once, the first time a message is held. Says what happened and that nothing is lost: it waits in
 * Blocked, where it can be reviewed or restored. [newBlocked] is the count of held messages not yet looked at.
 */
@Composable
fun FirstBlockReceipt(
    newBlocked: Int,
    onReview: () -> Unit,
    modifier: Modifier = Modifier,
    onDone: () -> Unit = {},
) {
    val context = LocalContext.current
    val prefs = remember(context) { W3cPrefs.get(context) }
    var answered by remember { mutableStateOf(prefs.firstBlockReceiptShown) }
    if (answered || newBlocked <= 0) return
    val finish = {
        prefs.firstBlockReceiptShown = true
        answered = true
        onDone()
    }
    val review = {
        finish()
        onReview()
    }
    // U03: the same quiet surface as the other cards; the shield alone says what it is about.
    val container = MaterialTheme.colorScheme.surfaceContainerHigh
    // B7: text and buttons take the card's own on-colour (checked against the fill), not the page's primary, which was
    // light blue on a pale card in dark mode.
    val content = readableOn(container, MaterialTheme.colorScheme.onSurface)
    val buttonColors = ButtonDefaults.textButtonColors(contentColor = content)
    // B7: the title says "tap to review", so the whole card opens Blocked.
    Surface(
        onClick = review,
        color = container,
        contentColor = content,
        shape = MaterialTheme.shapes.large,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(
                    Symbols.Shield,
                    contentDescription = null,
                    tint = readableOn(container, MaterialTheme.colorScheme.primary),
                    modifier = Modifier.size(24.dp),
                )
                Text(
                    stringResource(R.string.w3c_receipt_title),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.semantics { heading() },
                )
            }
            Text(
                stringResource(R.string.w3c_receipt_body),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp, end = 8.dp),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = finish, colors = buttonColors) {
                    Text(stringResource(R.string.w3c_receipt_got_it))
                }
                TextButton(onClick = review, colors = buttonColors) {
                    Text(stringResource(R.string.w3c_receipt_review), fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
