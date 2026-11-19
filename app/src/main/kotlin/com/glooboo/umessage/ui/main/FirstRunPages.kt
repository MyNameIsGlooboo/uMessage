package com.glooboo.umessage.ui.main

import android.content.res.Resources
import android.os.SystemClock
import androidx.annotation.StringRes
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
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
import com.glooboo.umessage.R
import com.glooboo.umessage.helpers.ProviderTruthFlag
import com.glooboo.umessage.ui.icons.Symbols
import com.glooboo.umessage.ui.theme.success
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Provider truth (3.5, 4.7, D2 A): the pages of the first run. They draw a [FirstRunProgress] that the first-run
 * runner (step B) publishes through a [FirstRunProgressSource]; the runner keeps that state across a killed app, so
 * the right page shows again. Nothing here starts or orders any work.
 */

/** The steps the progress page names, in the order of section 3.5. */
enum class FirstRunStep(@StringRes val label: Int) {
    RULES(R.string.pt_progress_step_rules),

    /** The message migration; left out on a fresh install (nothing to move). */
    OLDER_SPAM(R.string.pt_progress_step_older),
    HISTORY(R.string.pt_progress_step_history),
    SORT(R.string.pt_progress_step_sort),
}

/** What the "What changed" page counts (existing users only, once). */
data class WhatChanged(val chatsMoved: Int, val sendersAllowed: Int)

/**
 * The first run as the pages see it. [steps] are the steps this run has; [done] the finished ones; [current] the one
 * running. [startedAtMs] is on the [SystemClock.elapsedRealtime] clock, which keeps running while the app is killed,
 * so the "Continue in background" button does not wait another 15 s after a restart. [inBackground]: the user chose
 * "Continue in background". [whatChanged] is set once the work ended for an existing user, until the page is closed.
 */
data class FirstRunProgress(
    val running: Boolean = false,
    val steps: List<FirstRunStep> = FirstRunStep.entries,
    val current: FirstRunStep? = null,
    val done: Set<FirstRunStep> = emptySet(),
    val startedAtMs: Long = 0L,
    val inBackground: Boolean = false,
    val whatChanged: WhatChanged? = null,
)

/** The runner's side of the pages (step B implements it). */
interface FirstRunProgressSource {
    val progress: StateFlow<FirstRunProgress>

    /** "Continue in background": Messages shows as before the sort, with the small bar; still nothing moves. */
    fun continueInBackground()

    /** The "What changed" page was closed (either button); it is not shown again. */
    fun dismissWhatChanged()
}

/** No first run: the state until the runner sets a source. */
object NoFirstRun : FirstRunProgressSource {
    private val idle = MutableStateFlow(FirstRunProgress())
    override val progress: StateFlow<FirstRunProgress> = idle.asStateFlow()
    override fun continueInBackground() = Unit
    override fun dismissWhatChanged() = Unit
}

/** Where the main screen finds the runner's [FirstRunProgressSource]; step B sets [source]. */
object FirstRunPagesHost {
    @Volatile
    var source: FirstRunProgressSource = NoFirstRun
}

/** Which first-run page the main screen shows. */
enum class FirstRunPage { NONE, PROGRESS, BAR, WHAT_CHANGED }

/** How a step of the progress page is drawn. */
enum class FirstRunStepState { DONE, RUNNING, WAITING }

/** The rules of the pages, kept apart from the drawing so they can be tested. */
object FirstRunPagePolicy {
    /** "Continue in background" appears after this long (4.7). */
    const val BACKGROUND_AFTER_MS = 15_000L

    /**
     * The page for [progress]: the full-screen progress page while the work runs, the small bar once the user chose to
     * go on in the background, then "What changed" when it has something to show. Nothing while the flag is off.
     */
    fun pageFor(progress: FirstRunProgress, flagOn: Boolean = ProviderTruthFlag.enabled): FirstRunPage = when {
        !flagOn -> FirstRunPage.NONE
        progress.running && !progress.inBackground -> FirstRunPage.PROGRESS
        progress.running -> FirstRunPage.BAR
        progress.whatChanged != null -> FirstRunPage.WHAT_CHANGED
        else -> FirstRunPage.NONE
    }

    /** Whether "Continue in background" shows at [nowMs] (same clock as [FirstRunProgress.startedAtMs]). */
    fun showsBackgroundButton(progress: FirstRunProgress, nowMs: Long): Boolean =
        progress.running && nowMs - progress.startedAtMs >= BACKGROUND_AFTER_MS

    /** How long until the button shows; 0 when it shows already. */
    fun msUntilBackgroundButton(progress: FirstRunProgress, nowMs: Long): Long =
        (BACKGROUND_AFTER_MS - (nowMs - progress.startedAtMs)).coerceAtLeast(0L)

    fun stepState(progress: FirstRunProgress, step: FirstRunStep): FirstRunStepState = when {
        step in progress.done -> FirstRunStepState.DONE
        step == progress.current -> FirstRunStepState.RUNNING
        else -> FirstRunStepState.WAITING
    }

    /** The part done, 0 to 1, for the small bar. */
    fun fraction(progress: FirstRunProgress): Float {
        if (progress.steps.isEmpty()) return 0f
        return progress.done.count { it in progress.steps } / progress.steps.size.toFloat()
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
    now: () -> Long = SystemClock::elapsedRealtime,
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

/** One step: a tick when done, a spinner while it runs, an empty ring while it waits. TalkBack hears "Done". */
@Composable
private fun ProgressStepRow(step: FirstRunStep, state: FirstRunStepState) {
    val doneText = stringResource(R.string.w3c_checklist_step_done)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .semantics(mergeDescendants = true) {
                if (state == FirstRunStepState.DONE) stateDescription = doneText
                if (state == FirstRunStepState.RUNNING) liveRegion = LiveRegionMode.Polite
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            when (state) {
                FirstRunStepState.DONE ->
                    Icon(Symbols.CheckCircleFilled, contentDescription = null, tint = MaterialTheme.colorScheme.success)
                FirstRunStepState.RUNNING -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                FirstRunStepState.WAITING -> Box(
                    Modifier.size(20.dp).border(2.dp, MaterialTheme.colorScheme.outline, CircleShape),
                )
            }
        }
        Text(
            stringResource(step.label),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = if (state == FirstRunStepState.RUNNING) FontWeight.SemiBold else FontWeight.Normal,
            color = if (state == FirstRunStepState.WAITING) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * The small bar above Messages after "Continue in background": the page's title and how far the work is. Messages
 * shows as before the sort meanwhile.
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
 * "What changed" (4.7): for existing users, once, after the first sort. The six lines of the spec, then "Got it" and
 * "Review Spam". Either button closes it for good ([onDismiss]); "Review Spam" also opens the Spam tab.
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
