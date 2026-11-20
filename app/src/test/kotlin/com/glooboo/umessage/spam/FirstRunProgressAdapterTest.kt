package com.glooboo.umessage.spam

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.helpers.ProviderTruthFlag
import com.glooboo.umessage.testing.ProviderTruthFlagRule
import com.glooboo.umessage.ui.main.FirstRunPage
import com.glooboo.umessage.ui.main.FirstRunPagePolicy
import com.glooboo.umessage.ui.main.FirstRunPassKind
import com.glooboo.umessage.ui.main.WhatChanged
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import com.glooboo.umessage.ui.main.FirstRunPhase as PagePhase

/**
 * The adapter between step G's first-run pages and step B's runner (3.5, 4.7, D2 A): the page follows the runner's
 * state, the first pass shows before it started (G-B4), "Continue in background" and "What changed" work at once,
 * also during a stalled pass (G-B1), and "What changed" has the update's own numbers (G-B2). Flag off: no page.
 */
@RunWith(AndroidJUnit4::class)
class FirstRunProgressAdapterTest {

    @get:Rule
    val flag = ProviderTruthFlagRule(enabled = true)

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val state = MutableStateFlow(FirstRunState())
    private var onboarded = true
    private var kind: FirstRunTrigger? = null
    private val acks = mutableListOf<Pair<Boolean, Boolean>>()

    private val adapter by lazy {
        FirstRunProgressAdapter(
            state = state,
            onboarded = { onboarded },
            installKind = { kind },
            acknowledge = { whatChanged, inBackground -> acks += whatChanged to inBackground },
            scope = CoroutineScope(Dispatchers.Unconfined),
            clock = { SHOWN_AT },
        )
    }

    private fun page(): FirstRunPage = FirstRunPagePolicy.pageFor(adapter.progress.value)

    private fun pass(trigger: FirstRunTrigger, phase: FirstRunPhase = FirstRunPhase.HISTORY) =
        FirstRunState(phase = phase, trigger = trigger, startedAt = STARTED, sortPublished = phase > FirstRunPhase.SORT)

    private fun prefsStore() =
        FirstRunStore(context.getSharedPreferences(FirstRunStore.PREFS, Context.MODE_PRIVATE))

    @Before
    fun setUp() {
        context.getSharedPreferences(FirstRunStore.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        FirstRun.reload(context)
    }

    @After
    fun tearDown() {
        context.getSharedPreferences(FirstRunStore.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        FirstRun.reload(context)
    }

    @Test
    fun anUpdatePassShowsTheProgressPageAtItsPhase() {
        state.value = pass(FirstRunTrigger.UPDATE)

        val progress = adapter.progress.value
        assertThat(progress.running).isTrue()
        assertThat(progress.passKind).isEqualTo(FirstRunPassKind.UPDATE)
        assertThat(progress.phase).isEqualTo(PagePhase.HISTORY)
        assertThat(progress.startedAtMs).isEqualTo(STARTED)
        assertThat(page()).isEqualTo(FirstRunPage.PROGRESS)
    }

    @Test
    fun everyPhaseOfAPassHasItsPage() {
        FirstRunPhase.entries.filter { pass(FirstRunTrigger.UPDATE, it).active }.forEach { phase ->
            assertThat(adapter.progressOf(pass(FirstRunTrigger.UPDATE, phase)).phase?.name).isEqualTo(phase.name)
        }
        assertThat(adapter.progressOf(pass(FirstRunTrigger.FRESH_INSTALL)).passKind)
            .isEqualTo(FirstRunPassKind.FRESH_INSTALL)
    }

    @Test
    fun anyOtherPassShowsOnlyTheBar() {
        state.value = FirstRunState(sortPublished = true, phase = FirstRunPhase.DONE)
        assertThat(page()).isEqualTo(FirstRunPage.NONE)

        state.value = pass(FirstRunTrigger.SWITCH_ON).copy(sortPublished = true)

        assertThat(adapter.progress.value.passKind).isEqualTo(FirstRunPassKind.OTHER)
        assertThat(page()).isEqualTo(FirstRunPage.BAR)
    }

    @Test
    fun aStalledPassStillRunsAndItsBackgroundButtonIsTheWayOut() {
        state.value = pass(FirstRunTrigger.UPDATE).copy(waiting = true)

        assertThat(page()).isEqualTo(FirstRunPage.PROGRESS)
        assertThat(FirstRunPagePolicy.showsBackgroundButton(adapter.progress.value, STARTED + 15_000L)).isTrue()
        adapter.continueInBackground()
        assertThat(acks).containsExactly(false to true)

        state.value = state.value.copy(inBackground = true)
        assertThat(page()).isEqualTo(FirstRunPage.BAR)
    }

    @Test
    fun theFirstStartAfterTheUpdateShowsTheProgressPageBeforeThePassStarted() {
        // G-B4: still NOT_STARTED, the welcome flow done: the pass starts at the sorting thread's check.
        val progress = adapter.progress.value

        assertThat(progress.running).isTrue()
        assertThat(progress.passKind).isEqualTo(FirstRunPassKind.UPDATE)
        assertThat(progress.phase).isEqualTo(PagePhase.SETTINGS)
        assertThat(progress.startedAtMs).isEqualTo(SHOWN_AT)
        assertThat(page()).isEqualTo(FirstRunPage.PROGRESS)
    }

    @Test
    fun aNewInstallShowsItsPageOnceTheWelcomeFlowEnded() {
        onboarded = false
        kind = FirstRunTrigger.FRESH_INSTALL
        assertThat(page()).isEqualTo(FirstRunPage.NONE)

        onboarded = true
        adapter.refresh()

        assertThat(adapter.progress.value.passKind).isEqualTo(FirstRunPassKind.FRESH_INSTALL)
        assertThat(page()).isEqualTo(FirstRunPage.PROGRESS)
    }

    @Test
    fun withTheFlagOffThereIsNoPage() {
        ProviderTruthFlag.enabled = false

        assertThat(adapter.progress.value.running).isFalse()
        assertThat(page()).isEqualTo(FirstRunPage.NONE)
        state.value = pass(FirstRunTrigger.UPDATE)
        assertThat(page()).isEqualTo(FirstRunPage.NONE)
    }

    @Test
    fun whatChangedShowsTheUpdatesOwnNumbers() {
        state.value = FirstRunState(
            phase = FirstRunPhase.DONE,
            trigger = FirstRunTrigger.UPDATE,
            sortPublished = true,
            movedToSpam = 0,
            whatChangedPending = true,
            whatChangedMoved = 7,
            allowedAutomatically = 2,
        )

        assertThat(adapter.progress.value.whatChanged).isEqualTo(WhatChanged(chatsMoved = 7, sendersAllowed = 2))
        assertThat(page()).isEqualTo(FirstRunPage.WHAT_CHANGED)
        adapter.dismissWhatChanged()
        assertThat(acks).containsExactly(true to false)
    }

    @Test
    fun continueInBackgroundDuringAStalledPassAppliesAtOnce() {
        // G-B1: nothing waits for the sorting thread; the runner's next write keeps it.
        val stalled = pass(FirstRunTrigger.UPDATE).copy(waiting = true)
        prefsStore().write(stalled)
        FirstRun.reload(context)

        FirstRun.acknowledge(context, inBackground = true)

        assertThat(FirstRun.state(context).value.inBackground).isTrue()
        assertThat(FirstRunPagePolicy.pageFor(adapter.progressOf(FirstRun.state(context).value)))
            .isEqualTo(FirstRunPage.BAR)
        prefsStore().write(stalled.copy(phase = FirstRunPhase.SORT))
        FirstRun.reload(context)
        assertThat(FirstRun.state(context).value.inBackground).isTrue()
    }

    @Test
    fun continueInBackgroundBeforeThePassStartedHoldsForIt() {
        FirstRun.acknowledge(context, inBackground = true)

        assertThat(FirstRunPagePolicy.pageFor(adapter.progressOf(FirstRun.state(context).value)))
            .isEqualTo(FirstRunPage.BAR)
        prefsStore().write(pass(FirstRunTrigger.UPDATE))
        FirstRun.reload(context)
        assertThat(FirstRun.state(context).value.inBackground).isTrue()
    }

    @Test
    fun whatChangedAndTheCardCloseAtOnceAndStayClosed() {
        val ended = FirstRunState(
            phase = FirstRunPhase.DONE,
            trigger = FirstRunTrigger.UPDATE,
            sortPublished = true,
            movedToSpam = 3,
            cardPending = true,
            cardSince = STARTED,
            whatChangedPending = true,
            whatChangedSince = STARTED,
            whatChangedMoved = 3,
        )
        prefsStore().write(ended)
        FirstRun.reload(context)

        FirstRun.acknowledge(context, whatChanged = true)
        FirstRun.acknowledge(context, card = true)

        val seen = FirstRun.state(context).value
        assertThat(seen.whatChangedPending).isFalse()
        assertThat(seen.cardPending).isFalse()
        assertThat(adapter.progressOf(seen).whatChanged).isNull()
        // A later pass (a stale copy of the state written by the runner) does not bring them back.
        prefsStore().write(ended.copy(phase = FirstRunPhase.HISTORY, trigger = FirstRunTrigger.NEW_CHATS))
        FirstRun.reload(context)
        assertThat(FirstRun.state(context).value.whatChangedPending).isFalse()
        assertThat(FirstRun.state(context).value.cardPending).isFalse()
    }

    private companion object {
        const val STARTED = 5_000L
        const val SHOWN_AT = 9_000L
    }
}
