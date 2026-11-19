package com.glooboo.umessage.ui.main

import android.content.res.Resources
import androidx.annotation.StringRes
import androidx.annotation.VisibleForTesting
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.glooboo.umessage.R
import com.glooboo.umessage.helpers.ProviderTruthFlag
import com.glooboo.umessage.ui.icons.Symbols
import com.glooboo.umessage.ui.theme.success
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/*
 * Provider truth (3.5, 4.7, D2 A): the pages of the first run.
 *
 * Contract for the runner (step B). The runner implements [FirstRunProgressSource] and calls
 * [FirstRunPagesHost.install] once it exists (App.onCreate is fine; a late install still shows the pages, the host is
 * observed). It publishes one [FirstRunProgress] for the pass that runs, and keeps it across a killed app, so the same
 * page shows again:
 * - [FirstRunProgress.running] while any phase of a pass runs; [FirstRunProgress.phase] is the phase running now
 *   ([FirstRunPhase], in the order of 3.5; phases before it count as done, so no separate "done" list is kept).
 * - [FirstRunProgress.passKind]: UPDATE (first start of this version over 0.4.14 data) and FRESH_INSTALL get the
 *   full-screen progress page; every other pass (switch on, contacts granted, restore, default app again, a waiting
 *   migration that finished) is OTHER and shows only the small bar (G-4).
 * - [FirstRunProgress.olderSpamWaiting]: the message migration waits because uMessage is not the default app; the
 *   page says so on the "older spam" step instead of a spinner, and the pass goes on.
 * - [FirstRunProgress.startedAtMs]: the pass's start on the wall clock (System.currentTimeMillis), 0 when unknown.
 * - [FirstRunProgress.inBackground]: set and STORED by the source when [FirstRunProgressSource.continueInBackground]
 *   is called, so a restart during the same pass shows the bar again, not the full page.
 * - [FirstRunProgress.whatChanged]: set only when an UPDATE pass ends, kept (also across later passes) until
 *   [FirstRunProgressSource.dismissWhatChanged].
 */

/** The steps the progress page names, in the order of section 3.5. */
enum class FirstRunStep(@StringRes val label: Int) {
    RULES(R.string.pt_progress_step_rules),

    /** The message migration; left out on a fresh install (nothing to move). */
    OLDER_SPAM(R.string.pt_progress_step_older),
    HISTORY(R.string.pt_progress_step_history),
    SORT(R.string.pt_progress_step_sort),
}

/**
 * The phases of a pass as the runner knows them, in order (3.5). [step] is the visible step a phase belongs to: the
 * settings step is the start of "Updating your rules" (it takes no time of its own); the clean-ups come after the sort,
 * when every visible step is done.
 */
enum class FirstRunPhase(val step: FirstRunStep?) {
    SETTINGS(FirstRunStep.RULES),
    RULES(FirstRunStep.RULES),
    OLDER_SPAM(FirstRunStep.OLDER_SPAM),
    HISTORY(FirstRunStep.HISTORY),
    SORT(FirstRunStep.SORT),
    CLEANUP(null),
}

/** Which kind of pass runs: only the first two get the full-screen page (G-4). */
enum class FirstRunPassKind { UPDATE, FRESH_INSTALL, OTHER }

/** What the "What changed" page counts (existing users only, once). */
data class WhatChanged(val chatsMoved: Int, val sendersAllowed: Int)

/** The first run as the pages see it; see the contract at the top of this file. */
data class FirstRunProgress(
    val running: Boolean = false,
    val passKind: FirstRunPassKind = FirstRunPassKind.OTHER,
    val phase: FirstRunPhase? = null,
    val olderSpamWaiting: Boolean = false,
    val startedAtMs: Long = 0L,
    val inBackground: Boolean = false,
    val whatChanged: WhatChanged? = null,
) {
    /** The steps this pass names: a fresh install has no older spam to move. */
    val steps: List<FirstRunStep>
        get() = FirstRunStep.entries.filter {
            it != FirstRunStep.OLDER_SPAM || passKind != FirstRunPassKind.FRESH_INSTALL
        }
}

/** The runner's side of the pages (step B implements it). */
interface FirstRunProgressSource {
    val progress: StateFlow<FirstRunProgress>

    /**
     * "Continue in background": the source sets [FirstRunProgress.inBackground] and stores it. Messages then shows as
     * before the sort, with the small bar; still nothing moves.
     */
    fun continueInBackground()

    /** "What changed" was closed (either button): the source clears and stores [FirstRunProgress.whatChanged]. */
    fun dismissWhatChanged()
}

/** No first run: the state until the runner installs a source. */
object NoFirstRun : FirstRunProgressSource {
    private val idle = MutableStateFlow(FirstRunProgress())
    override val progress: StateFlow<FirstRunProgress> = idle.asStateFlow()
    override fun continueInBackground() = Unit
    override fun dismissWhatChanged() = Unit
}

/** Where the main screen finds the runner's [FirstRunProgressSource]. Observed, so a late [install] still counts. */
object FirstRunPagesHost {
    private val current = MutableStateFlow<FirstRunProgressSource>(NoFirstRun)
    val source: StateFlow<FirstRunProgressSource> = current.asStateFlow()

    fun install(source: FirstRunProgressSource) {
        current.value = source
    }

    @VisibleForTesting
    fun reset() {
        current.value = NoFirstRun
    }
}

/** Which first-run page the main screen shows. */
enum class FirstRunPage { NONE, PROGRESS, BAR, WHAT_CHANGED }

/** How a step of the progress page is drawn. [DEFERRED]: the older spam waits for the default app. */
enum class FirstRunStepState { DONE, RUNNING, WAITING, DEFERRED }

/** The rules of the pages, kept apart from the drawing so they can be tested. */
object FirstRunPagePolicy {
    /** "Continue in background" appears after this long (4.7). */
    const val BACKGROUND_AFTER_MS = 15_000L

    /**
     * The page for [progress]. The full page while an UPDATE or FRESH_INSTALL pass runs, the small bar for any other
     * pass or once the user went on in the background, then "What changed" when it has something to show, unless
     * [deferWhatChanged] (the user was using the app meanwhile: it waits for the next start or resume, G-4).
     * Nothing while the flag is off.
     */
    fun pageFor(
        progress: FirstRunProgress,
        flagOn: Boolean = ProviderTruthFlag.enabled,
        deferWhatChanged: Boolean = false,
    ): FirstRunPage = when {
        !flagOn -> FirstRunPage.NONE
        progress.running && progress.passKind != FirstRunPassKind.OTHER && !progress.inBackground ->
            FirstRunPage.PROGRESS
        progress.running -> FirstRunPage.BAR
        progress.whatChanged != null && !deferWhatChanged -> FirstRunPage.WHAT_CHANGED
        else -> FirstRunPage.NONE
    }

    /**
     * Whether "Continue in background" shows at [nowMs] (wall clock). A start time that is unknown (0) or later than
     * now (the clock was set back, a reboot) counts as "15 s have passed": the button never waits longer than that.
     */
    fun showsBackgroundButton(progress: FirstRunProgress, nowMs: Long): Boolean =
        progress.running && msUntilBackgroundButton(progress, nowMs) == 0L

    /** How long until the button shows: 0 when it shows already, never more than [BACKGROUND_AFTER_MS]. */
    fun msUntilBackgroundButton(progress: FirstRunProgress, nowMs: Long): Long {
        val elapsed = nowMs - progress.startedAtMs
        if (progress.startedAtMs <= 0L || elapsed < 0L) return 0L
        return (BACKGROUND_AFTER_MS - elapsed).coerceIn(0L, BACKGROUND_AFTER_MS)
    }

    /** A step is done once the running phase is past it; the waiting older spam is [FirstRunStepState.DEFERRED]. */
    fun stepState(progress: FirstRunProgress, step: FirstRunStep): FirstRunStepState {
        val phase = progress.phase
        val current = phase?.step
        return when {
            step == FirstRunStep.OLDER_SPAM && progress.olderSpamWaiting -> FirstRunStepState.DEFERRED
            !progress.running -> FirstRunStepState.DONE
            phase == null -> FirstRunStepState.WAITING
            current == null || step.ordinal < current.ordinal -> FirstRunStepState.DONE
            step == current -> FirstRunStepState.RUNNING
            else -> FirstRunStepState.WAITING
        }
    }

    /** The part done, 0 to 1, for the small bar. */
    fun fraction(progress: FirstRunProgress): Float {
        val steps = progress.steps
        if (steps.isEmpty()) return 0f
        return steps.count { stepState(progress, it) == FirstRunStepState.DONE } / steps.size.toFloat()
    }

    /** The lines of the "What changed" page, in the order of 4.7. */
    fun whatChangedLines(resources: Resources, changed: WhatChanged): List<String> = listOf(
        resources.getQuantityString(R.plurals.pt_changed_moved, changed.chatsMoved, changed.chatsMoved),
        resources.getQuantityString(R.plurals.pt_changed_allowed, changed.sendersAllowed, changed.sendersAllowed),
        resources.getString(R.string.pt_changed_switch),
        resources.getString(R.string.pt_changed_sims),
        resources.getString(R.string.pt_changed_store),
        resources.getString(R.string.pt_changed_purge),
    )
}

/**
 * The main screen with the first run's pages (MainActivity): the full progress page, "What changed", or [content]
 * with the small bar to place (null when none shows). "What changed" waits for the next resume when the bar showed
 * during this one, so it never replaces the screen while the app is in use (G-4).
 */
@Composable
fun FirstRunHost(
    onReviewSpam: () -> Unit,
    modifier: Modifier = Modifier,
    host: StateFlow<FirstRunProgressSource> = FirstRunPagesHost.source,
    now: () -> Long = System::currentTimeMillis,
    content: @Composable (bar: (@Composable () -> Unit)?) -> Unit,
) {
    val source by host.collectAsState()
    val progress by source.progress.collectAsState()
    var barShown by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { barShown = false }
    val page = FirstRunPagePolicy.pageFor(progress, deferWhatChanged = barShown)
    if (page == FirstRunPage.BAR) LaunchedEffect(Unit) { barShown = true }
    val changed = progress.whatChanged
    when {
        page == FirstRunPage.PROGRESS ->
            FirstRunProgressPage(progress, source::continueInBackground, modifier, now)
        page == FirstRunPage.WHAT_CHANGED && changed != null -> FirstRunWhatChangedPage(
            changed = changed,
            onDismiss = source::dismissWhatChanged,
            onReviewSpam = {
                source.dismissWhatChanged()
                onReviewSpam()
            },
            modifier = modifier,
        )
        page == FirstRunPage.BAR -> content { FirstRunProgressBar(progress) }
        else -> content(null)
    }
}

/** Test tag of the "Continue in background" button. */
internal const val FIRST_RUN_BACKGROUND_TAG = "first_run_background"

/**
 * The full-screen progress page (D2 A): what runs once, the four named steps (three on a fresh install), and after
 * about 15 seconds "Continue in background". No chat moves while it shows. It scrolls at large font sizes.
 */
@Composable
fun FirstRunProgressPage(
    progress: FirstRunProgress,
    onContinueInBackground: () -> Unit,
    modifier: Modifier = Modifier,
    now: () -> Long = System::currentTimeMillis,
) {
    val clockNow by rememberUpdatedState(now)
    var clock by remember { mutableLongStateOf(now()) }
    LaunchedEffect(progress.startedAtMs, progress.running) {
        val wait = FirstRunPagePolicy.msUntilBackgroundButton(progress, clockNow())
        if (wait > 0) delay(wait)
        clock = clockNow()
    }
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                stringResource(R.string.pt_progress_title),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                stringResource(R.string.pt_progress_body),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                progress.steps.forEach { step ->
                    ProgressStepRow(step, FirstRunPagePolicy.stepState(progress, step))
                }
            }
            if (FirstRunPagePolicy.showsBackgroundButton(progress, clock)) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onContinueInBackground,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(FIRST_RUN_BACKGROUND_TAG),
                ) { Text(stringResource(R.string.pt_progress_background)) }
            }
        }
    }
}

/** What TalkBack says for a step's state (G-19). */
@StringRes
internal fun stepStateText(state: FirstRunStepState): Int = when (state) {
    FirstRunStepState.DONE -> R.string.w3c_checklist_step_done
    FirstRunStepState.RUNNING -> R.string.pt_progress_step_running
    FirstRunStepState.WAITING -> R.string.oem2_state_todo
    FirstRunStepState.DEFERRED -> R.string.pt_progress_older_waiting
}

/**
 * One step: a tick when done, a spinner while it runs, an empty ring while it waits; the older spam that waits for the
 * default app says so under its name. TalkBack hears the step and its state.
 */
@Composable
private fun ProgressStepRow(step: FirstRunStep, state: FirstRunStepState) {
    val stateText = stringResource(stepStateText(state))
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .semantics(mergeDescendants = true) {
                stateDescription = stateText
                if (state == FirstRunStepState.RUNNING) liveRegion = LiveRegionMode.Polite
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            when (state) {
                FirstRunStepState.DONE ->
                    Icon(Symbols.CheckCircleFilled, contentDescription = null, tint = colors.success)
                FirstRunStepState.RUNNING -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                FirstRunStepState.WAITING, FirstRunStepState.DEFERRED ->
                    Box(Modifier.size(20.dp).border(2.dp, colors.outline, CircleShape))
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                stringResource(step.label),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = if (state == FirstRunStepState.RUNNING) FontWeight.SemiBold else FontWeight.Normal,
                color = if (state == FirstRunStepState.DONE || state == FirstRunStepState.RUNNING) {
                    colors.onSurface
                } else {
                    colors.onSurfaceVariant
                },
            )
            if (state == FirstRunStepState.DEFERRED) {
                Text(stateText, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            }
        }
    }
}

/**
 * The small bar above Messages after "Continue in background", and during passes that get no full page: the page's
 * title and how far the work is. Messages shows as before the sort meanwhile.
 */
@Composable
fun FirstRunProgressBar(progress: FirstRunProgress, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            stringResource(R.string.pt_progress_title),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        LinearProgressIndicator(
            progress = { FirstRunPagePolicy.fraction(progress) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Test tag of the "Review Spam" button. */
internal const val WHAT_CHANGED_REVIEW_TAG = "what_changed_review"

/**
 * "What changed" (4.7): for existing users, once, after the update's sort. The six lines of the spec, then "Got it"
 * and "Review Spam". Either button closes it for good ([onDismiss]); "Review Spam" also opens the Spam tab.
 */
@Composable
fun FirstRunWhatChangedPage(
    changed: WhatChanged,
    onDismiss: () -> Unit,
    onReviewSpam: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val lines = FirstRunPagePolicy.whatChangedLines(LocalResources.current, changed)
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 24.dp, vertical = 32.dp),
        ) {
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    stringResource(R.string.pt_changed_title),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.semantics { heading() },
                )
                // The two counts first, in bold; then what is new.
                lines.forEachIndexed { index, line ->
                    val count = index < 2
                    Text(
                        line,
                        style = if (count) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
                        fontWeight = if (count) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.heightIn(min = 48.dp).weight(1f, fill = false),
                ) { Text(stringResource(R.string.ux8c_tip_dismiss)) }
                Button(
                    onClick = onReviewSpam,
                    modifier = Modifier.heightIn(min = 48.dp).weight(1f, fill = false).testTag(WHAT_CHANGED_REVIEW_TAG),
                ) { Text(stringResource(R.string.pt_changed_open_spam)) }
            }
        }
    }
}
