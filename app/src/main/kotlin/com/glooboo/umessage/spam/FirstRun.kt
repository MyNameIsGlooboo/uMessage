package com.glooboo.umessage.spam

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.core.content.edit
import com.glooboo.umessage.extensions.conversationsDB
import com.glooboo.umessage.extensions.getConversationsOrNull
import com.glooboo.umessage.helpers.DefaultSmsRole
import com.glooboo.umessage.helpers.ProviderTruthFlag
import com.glooboo.umessage.spam.engine.SpamEngine
import com.glooboo.umessage.ui.onboarding.OnboardingState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The steps of a first run (3.5), in their fixed order. Stored by name; never rename. */
enum class FirstRunPhase { NOT_STARTED, SETTINGS, RULES, OLDER_SPAM, HISTORY, SORT, CLEANUP, DONE }

/**
 * Why a first-run pass runs, and which steps it takes. [showsCard]: the "N chats are now in Spam" card follows
 * (answer 15: fresh install, update, contacts granted, switch on). Stored by name; never rename.
 */
enum class FirstRunTrigger(val phases: List<FirstRunPhase>, val showsCard: Boolean) {
    /** An existing user's first start of this version; the only one that shows "What changed". */
    UPDATE(FULL_RUN, showsCard = true),

    /** A new install (also a reinstall), once the welcome flow is done. */
    FRESH_INSTALL(FULL_RUN, showsCard = true),

    /** The spam filter switch went on. */
    SWITCH_ON(RESORT, showsCard = true),

    /** Contacts became readable (U3, R5). */
    CONTACTS_GRANTED(RESORT, showsCard = true),

    /** uMessage became the default SMS app again (section 9): the waiting migration first. */
    DEFAULT_AGAIN(listOf(FirstRunPhase.OLDER_SPAM, FirstRunPhase.HISTORY, FirstRunPhase.SORT), showsCard = false),

    /** A settings backup was restored (its rules and members came back). */
    RESTORE(RESORT, showsCard = false),

    /** A migration that waited has finished: its senders are checked (3.5, last point). */
    MIGRATION_FINISHED(RESORT, showsCard = false),
}

private val FULL_RUN = listOf(
    FirstRunPhase.SETTINGS, FirstRunPhase.RULES, FirstRunPhase.OLDER_SPAM, FirstRunPhase.HISTORY, FirstRunPhase.SORT,
    FirstRunPhase.CLEANUP,
)
private val RESORT = listOf(FirstRunPhase.HISTORY, FirstRunPhase.SORT)

/**
 * The one state object of the first-run runner (D2 A): the progress page, the small bar above Messages, the
 * "What changed" page and the card all read it, so a killed app shows the right page again. Persisted after every
 * step.
 */
data class FirstRunState(
    val phase: FirstRunPhase = FirstRunPhase.NOT_STARTED,
    val trigger: FirstRunTrigger? = null,
    /** The first sort ended once: the lists read spam_threads from now on. Never goes back. */
    val sortPublished: Boolean = false,
    /** Chats the sorts of the passes since the card was last seen moved to Spam (card count, plural string). */
    val movedToSpam: Int = 0,
    /** Senders the history checks allowed automatically ("What changed": "M senders allowed automatically"). */
    val allowedAutomatically: Int = 0,
    val cardPending: Boolean = false,
    val whatChangedPending: Boolean = false,
    /** The older-spam migration waits (not the default app); the progress page can say so. */
    val olderSpamWaiting: Boolean = false,
    /** When the running pass started (the page shows "Continue in background" about 15 s later). */
    val startedAt: Long = 0L,
) {
    /** A pass is running or was cut off (a kill, a failed read) and resumes. */
    val active: Boolean get() = phase != FirstRunPhase.NOT_STARTED && phase != FirstRunPhase.DONE

    /** The pass of an existing user: the progress page lists the "older spam" step and "What changed" follows. */
    val existingUser: Boolean get() = trigger == FirstRunTrigger.UPDATE

    /**
     * R2: the history check has not finished for this pass (or no pass ran yet): a text that arrives now gets the check
     * of its sender on the spot (HistoryCheck.onTheSpot).
     */
    val historyPending: Boolean
        get() = phase == FirstRunPhase.NOT_STARTED || (active && phase.ordinal <= FirstRunPhase.HISTORY.ordinal)

    /** A full sort may run: the first sort was published and no pass is working ("nothing moves before the sort"). */
    val allowsFullSort: Boolean get() = sortPublished && !active
}

/** What one first-run pass does at each step. Each step may be repeated after a kill, so each is idempotent. */
interface FirstRunSteps {
    /** 7.1: the filter switch from the old mode, auto-delete "keep forever" (SpamEngine.applyProviderTruthMapping). */
    fun applySettings()

    /** 7.2: spamless.db 8 to 9 (Room runs it when the database opens). Throws when it cannot open. */
    fun migrateRules()

    /** Section 8 (step F). True when done or nothing to move; false when it must wait (not the default app). */
    fun migrateOlderSpam(): Boolean

    /** 3.5: the history check. Null when a read failed. */
    fun historyCheck(): HistoryResult?

    /** The first sort and publish of spam_threads. */
    fun sort(): SortResult

    /** Auto-delete and other clean-ups, only after the sort. */
    fun cleanUps()
}

/** Step F plugs the message migration in here; until then there is nothing to move. */
fun interface OlderSpamMigration {
    fun run(context: Context): Boolean
}

/** The persisted [FirstRunState], the triggers waiting for the running pass, and the last seen phone states. */
class FirstRunStore(private val prefs: SharedPreferences) {

    fun read(): FirstRunState = FirstRunState(
        phase = prefs.getString(KEY_PHASE, null)
            .let { name -> FirstRunPhase.entries.firstOrNull { it.name == name } } ?: FirstRunPhase.NOT_STARTED,
        trigger = triggerNamed(prefs.getString(KEY_TRIGGER, null)),
        sortPublished = prefs.getBoolean(KEY_PUBLISHED, false),
        movedToSpam = prefs.getInt(KEY_MOVED, 0),
        allowedAutomatically = prefs.getInt(KEY_ALLOWED, 0),
        cardPending = prefs.getBoolean(KEY_CARD, false),
        whatChangedPending = prefs.getBoolean(KEY_WHAT_CHANGED, false),
        olderSpamWaiting = prefs.getBoolean(KEY_OLDER_WAITING, false),
        startedAt = prefs.getLong(KEY_STARTED_AT, 0L),
    )

    /** Written at once (commit): the next start must find the step a kill interrupted. */
    fun write(state: FirstRunState) = prefs.edit(commit = true) {
        putString(KEY_PHASE, state.phase.name)
        putString(KEY_TRIGGER, state.trigger?.name)
        putBoolean(KEY_PUBLISHED, state.sortPublished)
        putInt(KEY_MOVED, state.movedToSpam)
        putInt(KEY_ALLOWED, state.allowedAutomatically)
        putBoolean(KEY_CARD, state.cardPending)
        putBoolean(KEY_WHAT_CHANGED, state.whatChangedPending)
        putBoolean(KEY_OLDER_WAITING, state.olderSpamWaiting)
        putLong(KEY_STARTED_AT, state.startedAt)
    }

    /** Triggers that came while a pass ran; they make one more pass when it ends. */
    var pending: Set<FirstRunTrigger>
        get() = prefs.getStringSet(KEY_PENDING, emptySet()).orEmpty()
            .mapNotNullTo(LinkedHashSet(), ::triggerNamed)
        set(value) = prefs.edit(commit = true) { putStringSet(KEY_PENDING, value.mapTo(HashSet()) { it.name }) }

    /** "UPDATE" or "FRESH_INSTALL": what the first start with the new path found. Null before it. */
    var installKind: FirstRunTrigger?
        get() = triggerNamed(prefs.getString(KEY_INSTALL_KIND, null))
        set(value) = prefs.edit(commit = true) { putString(KEY_INSTALL_KIND, value?.name) }

    /** The phone's state seen last time, for the "became readable" and "became default" triggers. Null at first. */
    fun lastSeen(key: String): Boolean? = if (prefs.contains(key)) prefs.getBoolean(key, false) else null

    fun saveSeen(key: String, value: Boolean) = prefs.edit(commit = true) { putBoolean(key, value) }

    private fun triggerNamed(name: String?): FirstRunTrigger? = FirstRunTrigger.entries.firstOrNull { it.name == name }

    companion object {
        /** New in provider truth; not a `spamless_` name. Never rename: a killed first run resumes from it. */
        const val PREFS = "umessage_first_run"
        const val SEEN_CONTACTS = "seen_contacts_readable"
        const val SEEN_DEFAULT = "seen_default_sms_app"
        private const val KEY_PHASE = "phase"
        private const val KEY_TRIGGER = "trigger"
        private const val KEY_PUBLISHED = "sort_published"
        private const val KEY_MOVED = "moved_to_spam"
        private const val KEY_ALLOWED = "allowed_automatically"
        private const val KEY_CARD = "card_pending"
        private const val KEY_WHAT_CHANGED = "what_changed_pending"
        private const val KEY_OLDER_WAITING = "older_spam_waiting"
        private const val KEY_STARTED_AT = "started_at"
        private const val KEY_PENDING = "pending_triggers"
        private const val KEY_INSTALL_KIND = "install_kind"
    }
}

/**
 * Runs the steps of a pass in their fixed order (3.5): nothing later in the list starts before the earlier step
 * ended, and the sort (the only step that moves chats) comes after the history check. The state is written after
 * every step, so a pass cut off by a kill or a failed read resumes at that step. A step that fails stops the pass
 * where it is ([resume] returns with the state still active); it is tried again on the next start, foreground or
 * trigger. A waiting older-spam migration does not stop the pass (it is marked and rerun later).
 */
class FirstRunRunner(
    private val store: FirstRunStore,
    private val steps: FirstRunSteps,
    private val clock: () -> Long = System::currentTimeMillis,
    private val onChange: (FirstRunState) -> Unit = {},
) {

    /** Asks for a pass. Starts it when none runs; while one runs, it is kept for one more pass after it. */
    fun request(trigger: FirstRunTrigger): FirstRunState {
        val state = store.read()
        if (state.active) {
            store.pending = store.pending + trigger
            return state
        }
        // Before the first full pass, a partial trigger waits: the full pass checks and sorts everything anyway.
        if (state.phase == FirstRunPhase.NOT_STARTED && trigger.phases != FULL_RUN) return state
        save(state.copy(phase = trigger.phases.first(), trigger = trigger, startedAt = clock()))
        return resume()
    }

    /** Goes on from the stored step until the pass ends or a step fails. */
    fun resume(): FirstRunState {
        var state = store.read()
        while (state.active) {
            val next = runCatching { step(state) }
                .onFailure { Log.w(TAG, "First run stopped at ${state.phase}: ${it.javaClass.simpleName}") }
                .getOrNull() ?: return state
            state = next
            save(state)
        }
        return state
    }

    /** Runs the step of [state]; the state after it, or null when it failed. */
    private fun step(state: FirstRunState): FirstRunState? = when (state.phase) {
        FirstRunPhase.SETTINGS -> advance(state.also { steps.applySettings() })
        FirstRunPhase.RULES -> advance(state.also { steps.migrateRules() })
        FirstRunPhase.OLDER_SPAM -> advance(state.copy(olderSpamWaiting = !steps.migrateOlderSpam()))
        FirstRunPhase.HISTORY -> steps.historyCheck()?.let {
            advance(state.copy(allowedAutomatically = state.allowedAutomatically + it.allowed))
        }
        FirstRunPhase.SORT -> (steps.sort() as? SortResult.Applied)?.let { sorted ->
            val counted = if (state.trigger?.showsCard == true) sorted.added else 0
            advance(state.copy(sortPublished = true, movedToSpam = state.movedToSpam + counted))
        }
        FirstRunPhase.CLEANUP -> advance(state.also { steps.cleanUps() })
        FirstRunPhase.NOT_STARTED, FirstRunPhase.DONE -> state
    }

    /** The next step of the pass, or the end of it (card, "What changed", then a waiting trigger's pass). */
    private fun advance(state: FirstRunState): FirstRunState {
        val phases = state.trigger?.phases.orEmpty()
        val next = phases.getOrNull(phases.indexOf(state.phase) + 1)
        if (next != null) return state.copy(phase = next)
        val trigger = state.trigger
        val ended = state.copy(
            phase = FirstRunPhase.DONE,
            cardPending = state.cardPending || trigger?.showsCard == true,
            whatChangedPending = state.whatChangedPending || trigger == FirstRunTrigger.UPDATE,
        )
        // A trigger that came meanwhile makes the next pass, one trigger at a time.
        val waiting = store.pending
        val again = waiting.firstOrNull() ?: return ended
        store.pending = waiting - again
        return ended.copy(phase = again.phases.first(), trigger = again, startedAt = clock())
    }

    private fun save(state: FirstRunState) {
        store.write(state)
        onChange(state)
    }

    private companion object {
        const val TAG = "uMessageSort"
    }
}

/**
 * Provider truth's first run (3.5, S5, D2 A) for this app: the runner on the sorting thread ([SpamSorting]), its
 * state for the pages of step G, and the five triggers of the history check: the update, the switch going on, a
 * reinstall (a fresh install) or a restore, uMessage becoming the default app again, and contacts becoming readable.
 * Nothing runs while [ProviderTruthFlag] is off.
 */
object FirstRun {
    private const val RETRY_MS = 30_000L
    private const val MAX_RETRIES = 5

    /** Step F sets its migration here (section 8). */
    @Volatile var olderSpamMigration: OlderSpamMigration = OlderSpamMigration { true }

    private val flow = MutableStateFlow(FirstRunState())
    @Volatile private var loaded = false
    @Volatile private var retries = 0

    /** The runner's state; the pages and the card follow it. */
    fun state(context: Context): StateFlow<FirstRunState> {
        if (!loaded) {
            flow.value = store(context).read()
            loaded = true
        }
        return flow.asStateFlow()
    }

    /**
     * App start, every return to the foreground, the end of the welcome flow and a contacts grant: resumes a pass a
     * kill cut off, starts the update's or the fresh install's pass, and turns the phone's changes (contacts readable,
     * default app again) into triggers. Off the main thread, on the sorting thread.
     */
    fun check(context: Context) {
        if (!ProviderTruthFlag.enabled) return
        val app = context.applicationContext
        SpamSorting.execute(Runnable { runCatching { checkNow(app) } })
    }

    /** Reads the stored state again (tests that write it directly). */
    @VisibleForTesting
    internal fun reload(context: Context) {
        flow.value = store(context).read()
        loaded = true
    }

    /** A trigger (3.5). The pass runs on the sorting thread. */
    fun request(context: Context, trigger: FirstRunTrigger) {
        if (!ProviderTruthFlag.enabled) return
        val app = context.applicationContext
        SpamSorting.execute(Runnable { runCatching { finished(app, runner(app).request(trigger)) } })
    }

    /**
     * The card or the "What changed" page was seen (tapped): it does not show again until the next pass. Written on
     * the sorting thread, so it never races a step's write.
     */
    fun acknowledge(context: Context, card: Boolean, whatChanged: Boolean) {
        val app = context.applicationContext
        SpamSorting.execute(
            Runnable {
                val store = store(app)
                val state = store.read()
                val next = state.copy(
                    cardPending = state.cardPending && !card,
                    movedToSpam = if (card) 0 else state.movedToSpam,
                    whatChangedPending = state.whatChangedPending && !whatChanged,
                    allowedAutomatically = if (whatChanged) 0 else state.allowedAutomatically,
                )
                runCatching { store.write(next) }
                flow.value = next
            }
        )
    }

    private fun checkNow(app: Context) {
        val store = store(app)
        val onboarded = OnboardingState.isCompleted(app)
        // The first start with the new path tells an update (welcome already done) from a new install.
        val kind = store.installKind ?: (if (onboarded) FirstRunTrigger.UPDATE else FirstRunTrigger.FRESH_INSTALL)
            .also { store.installKind = it }
        val state = store.read()
        when {
            state.active -> finished(app, runner(app).resume())
            state.phase == FirstRunPhase.NOT_STARTED && onboarded -> finished(app, runner(app).request(kind))
        }
        val contacts = runCatching { SpamEngine.contactChecker(app).canReadContacts() }.getOrDefault(false)
        val isDefault = runCatching { DefaultSmsRole.status(app) == DefaultSmsRole.Status.Held }.getOrDefault(false)
        val runner = runner(app)
        if (store.lastSeen(FirstRunStore.SEEN_CONTACTS) == false && contacts) {
            finished(app, runner.request(FirstRunTrigger.CONTACTS_GRANTED))
        }
        if (store.lastSeen(FirstRunStore.SEEN_DEFAULT) == false && isDefault) {
            finished(app, runner.request(FirstRunTrigger.DEFAULT_AGAIN))
        }
        store.saveSeen(FirstRunStore.SEEN_CONTACTS, contacts)
        store.saveSeen(FirstRunStore.SEEN_DEFAULT, isDefault)
    }

    /** After a pass returned: a full sort that waited may run; a stopped pass is tried again a little later. */
    private fun finished(app: Context, state: FirstRunState) {
        if (!state.active) {
            retries = 0
            SpamSorting.releaseGate(app)
        } else if (retries < MAX_RETRIES) {
            retries++
            SpamSorting.execute(Runnable { runCatching { finished(app, runner(app).resume()) } }, RETRY_MS)
        }
    }

    private fun runner(app: Context) = FirstRunRunner(
        store = store(app),
        steps = AppFirstRunSteps(app),
        onChange = { flow.value = it },
    )

    private fun store(context: Context) =
        FirstRunStore(context.applicationContext.getSharedPreferences(FirstRunStore.PREFS, Context.MODE_PRIVATE))
}

/** The steps of [FirstRunSteps] on the app's own stores. */
internal class AppFirstRunSteps(private val app: Context) : FirstRunSteps {
    override fun applySettings() {
        SpamEngine.applyProviderTruthMapping(app)
    }

    override fun migrateRules() {
        SpamEngine.repository(app).currentRules()
    }

    override fun migrateOlderSpam(): Boolean = FirstRun.olderSpamMigration.run(app)

    /** With a fresh snapshot: the steps before may have changed the settings and the rules a moment ago. */
    override fun historyCheck(): HistoryResult? {
        ChatSorter.snapshots(app).invalidate()
        return HistoryCheck.forApp(app).run()
    }

    /**
     * The chats of the phone's store that the list has not cached yet (a fresh install, before the list opened) are
     * cached first, so the sort and its card count see every chat. A failed read of the store fails the sort.
     */
    override fun sort(): SortResult {
        val chats = app.getConversationsOrNull() ?: return SortResult.Failed("store")
        val dao = app.conversationsDB
        val cached = dao.getSortableChats().mapTo(HashSet()) { it.threadId }
        chats.filter { it.threadId !in cached }.forEach { dao.insertOrUpdate(it) }
        return SpamSorting.sortNow(app)
    }

    override fun cleanUps() {
        SpamEngine.purgeExpired(app)
    }
}
