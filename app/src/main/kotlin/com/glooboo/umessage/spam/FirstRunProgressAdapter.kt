package com.glooboo.umessage.spam

import android.content.Context
import com.glooboo.umessage.ui.main.FirstRunPassKind
import com.glooboo.umessage.ui.main.FirstRunProgress
import com.glooboo.umessage.ui.main.FirstRunProgressSource
import com.glooboo.umessage.ui.main.WhatChanged
import com.glooboo.umessage.ui.onboarding.OnboardingState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import com.glooboo.umessage.ui.main.FirstRunPhase as PagePhase

/**
 * Step G's first-run pages on step B's runner (3.5, 4.7, D2 A): the [FirstRunProgressSource] of the pages, read from
 * the runner's [FirstRunState] (FirstRun.state), which is stored after every step, so a killed app shows the same page.
 *
 * - A pass that works, or one that stalled ([FirstRunState.waiting]), is "running": G has no stalled state, the
 *   "Continue in background" button is the way out (lead decision). AD-2: a stalled pass of any other kind shows only
 *   the bar, which has no such button, so it is reported as not running and does not hold back "What changed".
 * - G-B4: on the first start after the update (or after a new install's welcome) the state is still NOT_STARTED until
 *   the sorting thread checked it; the pass is due, so it is reported as running already ([firstPassDue]) and the
 *   main screen does not show before the progress page.
 * - G-B1: [continueInBackground] and [dismissWhatChanged] are the user's marks, kept at once and outside the runner's
 *   state write ([acknowledge], FirstRun.acknowledge), so they work while a pass works or is stalled.
 * - G-B2: "What changed" shows the update's own numbers ([FirstRunState.whatChangedMoved]), not the card's.
 */
class FirstRunProgressAdapter(
    private val state: StateFlow<FirstRunState>,
    /** The welcome flow is done: the first full pass starts as soon as the sorting thread checks. */
    private val onboarded: () -> Boolean,
    /** What the first start with the new path found (update or new install); null before it was checked. */
    private val installKind: () -> FirstRunTrigger?,
    /** Keeps the user's mark: "What changed" closed, or "Continue in background". */
    private val acknowledge: (whatChanged: Boolean, inBackground: Boolean) -> Unit,
    scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) : FirstRunProgressSource {

    /**
     * G-B4, AD-1: when the page before the pass started first showed (0 before); "Continue in background" counts its
     * 15 s from it. The pass that follows keeps the earlier of this and its own start, so the button does not go away.
     */
    @Volatile private var shownAt = 0L

    /** The start of the pass that followed the page shown before it ([shownAt]); 0 before it started. */
    @Volatile private var followedPass = 0L

    private val lock = Any()
    private val current = MutableStateFlow(progressOf(state.value))
    override val progress: StateFlow<FirstRunProgress> = current.asStateFlow()

    init {
        scope.launch { state.collect { publish() } }
    }

    /**
     * Reads again what the runner's state does not carry (the welcome flow just ended). The main screen's resume
     * calls it (App.checkFirstRun), before its first frame.
     */
    fun refresh() = publish()

    /**
     * The collector and [refresh] (main thread) publish in turn, each from the latest state: a value worked out from an
     * older state never replaces a newer one.
     */
    private fun publish() {
        synchronized(lock) { current.value = progressOf(state.value) }
    }

    override fun continueInBackground() = acknowledge(false, true)

    override fun dismissWhatChanged() = acknowledge(true, false)

    /** The pages' view of [first]. */
    internal fun progressOf(first: FirstRunState): FirstRunProgress = when {
        first.active && !(first.waiting && kindOf(first.trigger) == FirstRunPassKind.OTHER) -> FirstRunProgress(
            running = true,
            passKind = kindOf(first.trigger),
            phase = PagePhase.entries.firstOrNull { it.name == first.phase.name },
            olderSpamWaiting = first.olderSpamWaiting,
            startedAtMs = startedAtOf(first),
            inBackground = first.inBackground,
            whatChanged = whatChangedOf(first),
        )
        firstPassDue(first) -> FirstRunProgress(
            running = true,
            passKind = kindOf(installKind() ?: FirstRunTrigger.UPDATE),
            phase = PagePhase.SETTINGS,
            startedAtMs = shownAtNow(),
            inBackground = first.inBackground,
        )
        else -> FirstRunProgress(olderSpamWaiting = first.olderSpamWaiting, whatChanged = whatChangedOf(first))
    }

    /** AD-1: [shownAt], stamped the first time the pass is reported as due. */
    private fun shownAtNow(): Long = synchronized(lock) {
        if (shownAt == 0L) shownAt = clock()
        shownAt
    }

    /** AD-1: the pass that followed the page shown before it counts from the earlier of the two. */
    private fun startedAtOf(first: FirstRunState): Long = synchronized(lock) {
        if (shownAt == 0L) return first.startedAt
        if (followedPass == 0L) followedPass = first.startedAt
        if (first.startedAt == followedPass) minOf(shownAt, first.startedAt) else first.startedAt
    }

    /** G-B4: no pass ran yet, but the first one starts at the sorting thread's next check (FirstRun.checkNow). */
    private fun firstPassDue(first: FirstRunState): Boolean =
        first.phase == FirstRunPhase.NOT_STARTED && runCatching { onboarded() }.getOrDefault(false)

    private fun kindOf(trigger: FirstRunTrigger?): FirstRunPassKind = when (trigger) {
        FirstRunTrigger.UPDATE -> FirstRunPassKind.UPDATE
        FirstRunTrigger.FRESH_INSTALL -> FirstRunPassKind.FRESH_INSTALL
        else -> FirstRunPassKind.OTHER
    }

    private fun whatChangedOf(first: FirstRunState): WhatChanged? =
        if (first.whatChangedPending) WhatChanged(first.whatChangedMoved, first.allowedAutomatically) else null

    companion object {
        /** The adapter of this app, on FirstRun's state; App installs it with FirstRunPagesHost (flag on only). */
        fun forApp(context: Context): FirstRunProgressAdapter {
            val app = context.applicationContext
            val store = FirstRunStore(app.getSharedPreferences(FirstRunStore.PREFS, Context.MODE_PRIVATE))
            return FirstRunProgressAdapter(
                state = FirstRun.state(app),
                onboarded = { OnboardingState.isCompleted(app) },
                installKind = { store.installKind },
                acknowledge = { whatChanged, inBackground ->
                    FirstRun.acknowledge(app, whatChanged = whatChanged, inBackground = inBackground)
                },
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            )
        }
    }
}
