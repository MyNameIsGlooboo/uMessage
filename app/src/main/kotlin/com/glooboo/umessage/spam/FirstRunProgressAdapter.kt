package com.glooboo.umessage.spam

import android.content.Context
import com.glooboo.umessage.helpers.ProviderTruthFlag
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
 *   "Continue in background" button is the way out (lead decision).
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
    clock: () -> Long = System::currentTimeMillis,
) : FirstRunProgressSource {

    /** G-B4: the start of a page shown before the pass started; "Continue in background" counts its 15 s from it. */
    private val shownAt = clock()

    private val current = MutableStateFlow(progressOf(state.value))
    override val progress: StateFlow<FirstRunProgress> = current.asStateFlow()

    init {
        scope.launch { state.collect { current.value = progressOf(it) } }
    }

    /**
     * Reads again what the runner's state does not carry (the welcome flow just ended). The main screen's resume
     * calls it (App.checkFirstRun), before its first frame.
     */
    fun refresh() {
        current.value = progressOf(state.value)
    }

    override fun continueInBackground() = acknowledge(false, true)

    override fun dismissWhatChanged() = acknowledge(true, false)

    /** The pages' view of [first]. */
    internal fun progressOf(first: FirstRunState): FirstRunProgress = when {
        first.active -> FirstRunProgress(
            running = true,
            passKind = kindOf(first.trigger),
            phase = PagePhase.entries.firstOrNull { it.name == first.phase.name },
            olderSpamWaiting = first.olderSpamWaiting,
            startedAtMs = first.startedAt,
            inBackground = first.inBackground,
            whatChanged = whatChangedOf(first),
        )
        firstPassDue(first) -> FirstRunProgress(
            running = true,
            passKind = kindOf(installKind() ?: FirstRunTrigger.UPDATE),
            phase = PagePhase.SETTINGS,
            startedAtMs = shownAt,
            inBackground = first.inBackground,
        )
        else -> FirstRunProgress(olderSpamWaiting = first.olderSpamWaiting, whatChanged = whatChangedOf(first))
    }

    /** G-B4: no pass ran yet, but the first one starts at the sorting thread's next check (FirstRun.checkNow). */
    private fun firstPassDue(first: FirstRunState): Boolean =
        ProviderTruthFlag.enabled && first.phase == FirstRunPhase.NOT_STARTED &&
            runCatching { onboarded() }.getOrDefault(false)

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
