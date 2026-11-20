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
import com.glooboo.umessage.helpers.refreshConversations
import com.glooboo.umessage.models.Conversation
import com.glooboo.umessage.spam.engine.SpamEngine
import com.glooboo.umessage.ui.onboarding.OnboardingState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.fossify.commons.helpers.ensureBackgroundThread

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

    /** B-6: many chat rows were cached at once after the first run (a restore, a rebuilt store). */
    NEW_CHATS(RESORT, showsCard = false),
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
    /**
     * The first sort ended once. Never goes back. The lists always read spam_threads (B-4: the sort's transaction is
     * the publish); this gates full sorts, the pages, Android-blocked chats in the list, and the clean-ups.
     */
    val sortPublished: Boolean = false,
    /** B-12: chats in Spam that entered it since [cardSince] (card count, plural string); counted from the table. */
    val movedToSpam: Int = 0,
    /**
     * B-12: senders the history checks allowed since [whatChangedSince] ("M senders allowed automatically"). G-B2:
     * counted once, when the update's pass ends; later passes keep it.
     */
    val allowedAutomatically: Int = 0,
    /**
     * G-B2: "N chats moved to Spam" of "What changed": counted once, when the update's pass ends, from
     * [whatChangedSince]. Not the card's number (that one is zeroed when the card is seen and counted again later).
     */
    val whatChangedMoved: Int = 0,
    /** The card shows: a pass that shows it ended with at least one chat moved, and the card was not tapped yet. */
    val cardPending: Boolean = false,
    val whatChangedPending: Boolean = false,
    /** The older-spam migration waits (not the default app); the progress page can say so. */
    val olderSpamWaiting: Boolean = false,
    /** Wall-clock start of the running pass (the page shows "Continue in background" about 15 s later). */
    val startedAt: Long = 0L,
    /** Start of the first pass whose card is not seen yet: the card counts the chats moved since (no double count). */
    val cardSince: Long = 0L,
    /** Start of the update's pass, for "What changed". */
    val whatChangedSince: Long = 0L,
    /** The user chose "Continue in background" on the progress page (the small bar shows). */
    val inBackground: Boolean = false,
    /** B-22: the pass failed again and again; it waits for the next start, foreground or trigger. */
    val waiting: Boolean = false,
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

    /**
     * B2-4: a sort of single chats (a block, "Not spam", a new chat) may run: the first sort was published. A later
     * pass that works or is stalled ([waiting]) does not hold it back.
     */
    val allowsThreadSort: Boolean get() = sortPublished
}

/** What one first-run pass does at each step. Each step may be repeated after a kill, so each is idempotent. */
interface FirstRunSteps {
    /** A pass starts (also one chained after another): what a step keeps for its pass is read again. */
    fun passStarted() = Unit

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

    /** B-12: chats shown in Spam that entered it at or after [since] (the table's spam_since). */
    fun countMoved(since: Long): Int

    /** B-12: group members the history check added at or after [since]. */
    fun countAllowed(since: Long): Int
}

/** Step F plugs the message migration in here; until then there is nothing to move. */
fun interface OlderSpamMigration {
    fun run(context: Context): Boolean
}

/**
 * The persisted [FirstRunState], the triggers waiting for the running pass, and the last seen phone states.
 *
 * G-B1: what the user saw ("Continue in background", "What changed" closed, the card tapped) is kept in marks of their
 * own, written at once from any thread ([markSeen]) and never by the runner. A mark names what it was set for (the
 * pass's start, the card's start, the update's start), so it ends by itself when a new pass, card or page begins.
 * [read] and [marked] apply them; the runner's own state write cannot undo them.
 */
class FirstRunStore(private val prefs: SharedPreferences) {

    fun read(): FirstRunState = marked(readRaw())

    private fun readRaw(): FirstRunState = FirstRunState(
        phase = prefs.getString(KEY_PHASE, null)
            .let { name -> FirstRunPhase.entries.firstOrNull { it.name == name } } ?: FirstRunPhase.NOT_STARTED,
        trigger = triggerNamed(prefs.getString(KEY_TRIGGER, null)),
        sortPublished = prefs.getBoolean(KEY_PUBLISHED, false),
        movedToSpam = prefs.getInt(KEY_MOVED, 0),
        allowedAutomatically = prefs.getInt(KEY_ALLOWED, 0),
        whatChangedMoved = prefs.getInt(KEY_WHAT_CHANGED_MOVED, 0),
        cardPending = prefs.getBoolean(KEY_CARD, false),
        whatChangedPending = prefs.getBoolean(KEY_WHAT_CHANGED, false),
        olderSpamWaiting = prefs.getBoolean(KEY_OLDER_WAITING, false),
        startedAt = prefs.getLong(KEY_STARTED_AT, 0L),
        cardSince = prefs.getLong(KEY_CARD_SINCE, 0L),
        whatChangedSince = prefs.getLong(KEY_WHAT_CHANGED_SINCE, 0L),
        inBackground = prefs.getBoolean(KEY_IN_BACKGROUND, false),
        waiting = prefs.getBoolean(KEY_WAITING, false),
    )

    /** Written at once (commit): the next start must find the step a kill interrupted. */
    fun write(state: FirstRunState) = prefs.edit(commit = true) {
        putString(KEY_PHASE, state.phase.name)
        putString(KEY_TRIGGER, state.trigger?.name)
        putBoolean(KEY_PUBLISHED, state.sortPublished)
        putInt(KEY_MOVED, state.movedToSpam)
        putInt(KEY_ALLOWED, state.allowedAutomatically)
        putInt(KEY_WHAT_CHANGED_MOVED, state.whatChangedMoved)
        putBoolean(KEY_CARD, state.cardPending)
        putBoolean(KEY_WHAT_CHANGED, state.whatChangedPending)
        putBoolean(KEY_OLDER_WAITING, state.olderSpamWaiting)
        putLong(KEY_STARTED_AT, state.startedAt)
        putLong(KEY_CARD_SINCE, state.cardSince)
        putLong(KEY_WHAT_CHANGED_SINCE, state.whatChangedSince)
        putBoolean(KEY_IN_BACKGROUND, state.inBackground)
        putBoolean(KEY_WAITING, state.waiting)
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

    /**
     * G-B1: the user saw something. [backgroundPass]: "Continue in background" for the pass that started then
     * ([FirstRunState.startedAt]; [BEFORE_START] while the first pass has not started yet). [card]: the card that
     * counts from this [FirstRunState.cardSince], seen at [at]. [whatChanged]: "What changed" of this
     * [FirstRunState.whatChangedSince]. In memory at once (apply): fine on the main thread, and never queued behind a
     * pass. Then the file is written at once (an empty commit writes what is in memory) off the main thread, so a kill
     * right after the tap keeps it; the commit puts nothing again, so it cannot bring back a mark cleared meanwhile.
     */
    fun markSeen(backgroundPass: Long? = null, card: Long? = null, whatChanged: Long? = null, at: Long = 0L) {
        prefs.edit {
            backgroundPass?.let { putLong(KEY_SEEN_BACKGROUND, it) }
            card?.let {
                putLong(KEY_SEEN_CARD, it)
                putLong(KEY_SEEN_CARD_AT, at)
            }
            whatChanged?.let { putLong(KEY_SEEN_WHAT_CHANGED, it) }
        }
        ensureBackgroundThread { runCatching { prefs.edit(commit = true) {} } }
    }

    /** [state] with the user's marks ([markSeen]) applied; without a mark that fits, [state] as it is. */
    fun marked(state: FirstRunState): FirstRunState {
        val cardSeen = mark(KEY_SEEN_CARD) == state.cardSince
        val inBackground = when (val pass = mark(KEY_SEEN_BACKGROUND)) {
            null -> false
            // Only the first full pass can follow a page that showed before it started (G-B4).
            BEFORE_START -> state.phase == FirstRunPhase.NOT_STARTED ||
                (state.active && state.trigger?.phases == FULL_RUN)
            else -> state.active && pass == state.startedAt
        }
        return state.copy(
            inBackground = state.inBackground || inBackground,
            cardPending = state.cardPending && !cardSeen,
            movedToSpam = if (cardSeen) 0 else state.movedToSpam,
            whatChangedPending = state.whatChangedPending && mark(KEY_SEEN_WHAT_CHANGED) != state.whatChangedSince,
        )
    }

    /** When the card that counts from [cardSince] was seen; null when it was not. */
    fun cardSeenAt(cardSince: Long): Long? =
        if (mark(KEY_SEEN_CARD) == cardSince) prefs.getLong(KEY_SEEN_CARD_AT, cardSince) else null

    private fun mark(key: String): Long? = if (prefs.contains(key)) prefs.getLong(key, 0L) else null

    private fun triggerNamed(name: String?): FirstRunTrigger? = FirstRunTrigger.entries.firstOrNull { it.name == name }

    companion object {
        /** New in provider truth; not a `spamless_` name. Never rename: a killed first run resumes from it. */
        const val PREFS = "umessage_first_run"
        const val SEEN_CONTACTS = "seen_contacts_readable"
        const val SEEN_DEFAULT = "seen_default_sms_app"

        /** The "Continue in background" mark of a page that showed before the first pass started (G-B4). */
        const val BEFORE_START = -1L
        private const val KEY_PHASE = "phase"
        private const val KEY_TRIGGER = "trigger"
        private const val KEY_PUBLISHED = "sort_published"
        private const val KEY_MOVED = "moved_to_spam"
        private const val KEY_ALLOWED = "allowed_automatically"
        private const val KEY_CARD = "card_pending"
        private const val KEY_WHAT_CHANGED = "what_changed_pending"
        private const val KEY_OLDER_WAITING = "older_spam_waiting"
        private const val KEY_STARTED_AT = "started_at"
        private const val KEY_CARD_SINCE = "card_since"
        private const val KEY_WHAT_CHANGED_SINCE = "what_changed_since"
        private const val KEY_IN_BACKGROUND = "in_background"
        private const val KEY_WAITING = "waiting"
        private const val KEY_PENDING = "pending_triggers"
        private const val KEY_INSTALL_KIND = "install_kind"
        private const val KEY_WHAT_CHANGED_MOVED = "what_changed_moved"
        private const val KEY_SEEN_BACKGROUND = "seen_background_pass"
        private const val KEY_SEEN_CARD = "seen_card_since"
        private const val KEY_SEEN_CARD_AT = "seen_card_at"
        private const val KEY_SEEN_WHAT_CHANGED = "seen_what_changed_since"
    }
}

/**
 * Runs the steps of a pass in their fixed order (3.5): nothing later in the list starts before the earlier step
 * ended, and the sort (the only step that moves chats) comes after the history check. The state is written after
 * every step, so a pass cut off by a kill or a failed read resumes at that step. A step that fails stops the pass
 * where it is ([resume] returns with the state still active); it is tried again on the next start, foreground or
 * trigger. A waiting older-spam migration does not stop the pass (it is marked and rerun later). The card and "What
 * changed" counts are read from the table and the members when a pass ends (B-12), so a sort repeated after a kill
 * counts nothing twice.
 */
class FirstRunRunner(
    private val store: FirstRunStore,
    private val steps: FirstRunSteps,
    private val clock: () -> Long = System::currentTimeMillis,
    /** After the first save that says "published" (the list then reads Android-blocked chats too). */
    private val onFirstPublish: () -> Unit = {},
    private val onChange: (FirstRunState) -> Unit = {},
) {
    /** A pending trigger whose pass [advance] started; it leaves the pending set only after that state is saved. */
    private var startedFromPending: FirstRunTrigger? = null

    /** Asks for a pass. Starts it when none runs; while one runs, it is kept for one more pass after it. */
    fun request(trigger: FirstRunTrigger): FirstRunState {
        val state = store.read()
        if (state.active) {
            store.pending = store.pending + trigger
            return state
        }
        // Before the first full pass, a partial trigger waits: the full pass checks and sorts everything anyway.
        if (state.phase == FirstRunPhase.NOT_STARTED && trigger.phases != FULL_RUN) return state
        save(begin(state, trigger))
        return resume()
    }

    /** Goes on from the stored step until the pass ends or a step fails. */
    fun resume(): FirstRunState {
        var state = store.read()
        while (state.active) {
            val before = state
            val next = runCatching { step(state) }
                .onFailure { Log.w(TAG, "First run stopped at ${state.phase}: ${it.javaClass.simpleName}") }
                .getOrNull() ?: return state
            state = next.copy(waiting = false)
            save(state)
            // B-18: a waiting trigger leaves the pending set only once its pass is saved.
            startedFromPending?.let { store.pending = store.pending - it }
            startedFromPending = null
            if (state.sortPublished && !before.sortPublished) runCatching { onFirstPublish() }
        }
        return state
    }

    /** The first state of a pass of [trigger]: where the card and "What changed" start counting. */
    private fun begin(state: FirstRunState, trigger: FirstRunTrigger): FirstRunState {
        steps.passStarted()
        val at = clock()
        return state.copy(
            phase = trigger.phases.first(),
            trigger = trigger,
            startedAt = at,
            inBackground = false,
            cardSince = if (trigger.showsCard && !state.cardPending) at else state.cardSince,
            whatChangedSince = if (trigger == FirstRunTrigger.UPDATE) at else state.whatChangedSince,
        )
    }

    /** Runs the step of [state]; the state after it, or null when it failed. */
    private fun step(state: FirstRunState): FirstRunState? = when (state.phase) {
        FirstRunPhase.SETTINGS -> advance(state.also { steps.applySettings() })
        FirstRunPhase.RULES -> advance(state.also { steps.migrateRules() })
        FirstRunPhase.OLDER_SPAM -> advance(state.copy(olderSpamWaiting = !steps.migrateOlderSpam()))
        FirstRunPhase.HISTORY -> steps.historyCheck()?.let { advance(state) }
        FirstRunPhase.SORT -> (steps.sort() as? SortResult.Applied)?.let { advance(state.copy(sortPublished = true)) }
        FirstRunPhase.CLEANUP -> advance(state.also { steps.cleanUps() })
        FirstRunPhase.NOT_STARTED, FirstRunPhase.DONE -> state
    }

    /** The next step of the pass, or the end of it (card, "What changed", then a waiting trigger's pass). */
    private fun advance(state: FirstRunState): FirstRunState {
        val phases = state.trigger?.phases.orEmpty()
        val next = phases.getOrNull(phases.indexOf(state.phase) + 1)
        if (next != null) return state.copy(phase = next)
        val ended = counted(state).copy(phase = FirstRunPhase.DONE)
        // A trigger that came meanwhile makes the next pass, one trigger at a time.
        val again = store.pending.firstOrNull() ?: return ended
        startedFromPending = again
        return begin(ended, again)
    }

    /**
     * B-12: the card shows only when the passes since it was last seen moved a chat; counts come from the table.
     * G-B1: what the user saw during the pass counts (the marks are read again here); a card seen during the pass
     * counts again from the moment it was seen. G-B2: "What changed" gets its own numbers, once, at the update's end.
     */
    private fun counted(running: FirstRunState): FirstRunState {
        val state = store.marked(running)
        val card = state.cardPending || state.trigger?.showsCard == true
        val cardSince = if (card) store.cardSeenAt(state.cardSince) ?: state.cardSince else state.cardSince
        val moved = if (card) steps.countMoved(cardSince) else state.movedToSpam
        val update = state.trigger == FirstRunTrigger.UPDATE
        val since = state.whatChangedSince
        return state.copy(
            cardSince = cardSince,
            movedToSpam = moved,
            cardPending = card && moved > 0,
            whatChangedPending = state.whatChangedPending || update,
            whatChangedMoved = if (update) steps.countMoved(since) else state.whatChangedMoved,
            allowedAutomatically = if (update) steps.countAllowed(since) else state.allowedAutomatically,
        )
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
            synchronized(this) {
                if (!loaded) {
                    flow.value = store(context).read()
                    loaded = true
                }
            }
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
        publishState(context.applicationContext, store(context).read())
    }

    /** A trigger (3.5). The pass runs on the sorting thread. */
    fun request(context: Context, trigger: FirstRunTrigger) {
        if (!ProviderTruthFlag.enabled) return
        val app = context.applicationContext
        SpamSorting.execute(Runnable { runCatching { finished(app, runner(app).request(trigger)) } })
    }

    /**
     * The card or the "What changed" page was seen (tapped): it does not show again until the next pass. With
     * [inBackground] the progress page's "Continue in background" is kept too (before the first pass started, for that
     * pass). G-B1: kept in marks of their own at once, from any thread, also while a pass works or is stalled: never
     * queued behind the pass on the sorting thread, and the runner's state write cannot undo them.
     */
    fun acknowledge(
        context: Context,
        card: Boolean = false,
        whatChanged: Boolean = false,
        inBackground: Boolean = false,
    ) {
        val app = context.applicationContext
        val shown = state(app).value
        val pass = if (shown.active) shown.startedAt else FirstRunStore.BEFORE_START
        runCatching {
            store(app).markSeen(
                backgroundPass = pass.takeIf { inBackground },
                card = shown.cardSince.takeIf { card },
                whatChanged = shown.whatChangedSince.takeIf { whatChanged },
                at = System.currentTimeMillis(),
            )
        }
        // The state shown now, read under the lock: a step published meanwhile is not replaced by an older one.
        synchronized(this) { publishState(app, flow.value) }
    }

    private fun checkNow(app: Context) {
        val store = store(app)
        val onboarded = OnboardingState.isCompleted(app)
        // The first start with the new path tells an update (welcome already done) from a new install.
        val kind = store.installKind ?: (if (onboarded) FirstRunTrigger.UPDATE else FirstRunTrigger.FRESH_INSTALL)
            .also { store.installKind = it }
        val state = store.read()
        val contacts = runCatching { SpamEngine.contactChecker(app).canReadContacts() }.getOrDefault(false)
        val isDefault = runCatching { DefaultSmsRole.status(app) == DefaultSmsRole.Status.Held }.getOrDefault(false)
        val startsFull = state.phase == FirstRunPhase.NOT_STARTED && onboarded
        when {
            state.active -> finished(app, runner(app).resume())
            startsFull -> finished(app, runner(app).request(kind))
        }
        // B-11: the full pass just checked and sorted everything; the phone's changes up to now are part of it.
        if (!startsFull) {
            val runner = runner(app)
            if (store.lastSeen(FirstRunStore.SEEN_CONTACTS) == false && contacts) {
                finished(app, runner.request(FirstRunTrigger.CONTACTS_GRANTED))
            }
            if (store.lastSeen(FirstRunStore.SEEN_DEFAULT) == false && isDefault) {
                finished(app, runner.request(FirstRunTrigger.DEFAULT_AGAIN))
            }
        }
        store.saveSeen(FirstRunStore.SEEN_CONTACTS, contacts)
        store.saveSeen(FirstRunStore.SEEN_DEFAULT, isDefault)
    }

    /**
     * After a pass returned: the sorts that waited may run; a stopped pass is tried again a little later, and after
     * [MAX_RETRIES] it is marked waiting (B-22) until the next start, foreground or trigger.
     */
    private fun finished(app: Context, state: FirstRunState) {
        when {
            !state.active -> {
                retries = 0
                SpamSorting.releaseGate(app)
            }
            retries < MAX_RETRIES -> {
                retries++
                SpamSorting.execute(Runnable { runCatching { finished(app, runner(app).resume()) } }, RETRY_MS)
            }
            else -> {
                retries = 0
                val waiting = state.copy(waiting = true)
                runCatching { store(app).write(waiting) }
                publishState(app, waiting)
            }
        }
    }

    /**
     * G-B1: the published state carries the user's marks. Under the lock, so a runner's step and a tap that publish at
     * the same moment cannot hide the tap again (the tap writes its mark before it publishes).
     */
    private fun publishState(app: Context, state: FirstRunState) = synchronized(this) {
        flow.value = runCatching { store(app).marked(state) }.getOrDefault(state)
        loaded = true
    }

    private fun runner(app: Context) = FirstRunRunner(
        store = store(app),
        steps = AppFirstRunSteps(app),
        // The list sync reads every thread again: chats of numbers on Android's block list are listed now (B-16).
        onFirstPublish = { refreshConversations() },
        onChange = { publishState(app, it) },
    )

    private fun store(context: Context) =
        FirstRunStore(context.applicationContext.getSharedPreferences(FirstRunStore.PREFS, Context.MODE_PRIVATE))
}

/** The steps of [FirstRunSteps] on the app's own stores. The store's chats are read once per pass. */
internal class AppFirstRunSteps(private val app: Context) : FirstRunSteps {
    /** The store's chats read for the running pass; null until read. */
    private var kept: List<Conversation>? = null

    /**
     * B-16: with the chats of numbers on Android's block list, which are judged like any other. Read once per pass, so
     * the history check and the sort see the same chats; B2-2: a pass chained after another reads them again. A failed
     * read (null) is not kept.
     */
    private fun storeChats(): List<Conversation>? =
        kept ?: app.getConversationsOrNull(includeBlocked = true).also { kept = it }

    override fun passStarted() {
        kept = null
    }

    override fun applySettings() {
        SpamEngine.applyProviderTruthMapping(app)
    }

    override fun migrateRules() {
        SpamEngine.repository(app).currentRules()
    }

    override fun migrateOlderSpam(): Boolean = FirstRun.olderSpamMigration.run(app)

    /** With a fresh snapshot: the steps before may have changed the settings and the rules a moment ago. */
    override fun historyCheck(): HistoryResult? {
        val chats = storeChats() ?: return null
        ChatSorter.snapshots(app).invalidate()
        val sortable = chats.map { SortableChat(it.threadId, it.phoneNumber, it.isGroupConversation, false) }
        return HistoryCheck.forApp(app, sortable).run()
    }

    /**
     * The chats of the phone's store that the list has not cached yet (a fresh install, before the list opened; the
     * chats of blocked numbers) are cached in the sort's own transaction, so the sort and its card see every chat and
     * none shows in Messages before it is sorted. A failed read of the store fails the sort.
     */
    override fun sort(): SortResult {
        val chats = storeChats() ?: return SortResult.Failed("store")
        return SpamSorting.sortNow(app, newRows = chats)
    }

    override fun cleanUps() {
        SpamEngine.purgeExpired(app)
    }

    override fun countMoved(since: Long): Int = app.conversationsDB.countSpamSince(since)

    override fun countAllowed(since: Long): Int = SpamEngine.repository(app).groupMembership.members()
        .count { it.source == MemberSource.HISTORY && it.addedAt >= since }
}
