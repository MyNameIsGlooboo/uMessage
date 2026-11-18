package com.glooboo.umessage.ui.spam

import android.content.Context
import com.glooboo.umessage.extensions.config
import com.glooboo.umessage.spam.SpamRule
import com.glooboo.umessage.spam.UMessage
import com.glooboo.umessage.spam.UnknownSenderMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.fossify.commons.extensions.baseConfig

/** Where the two old Fossify settings live. Blocking; call off the main thread. */
interface BlockingMigrationStore {
    /** The old blocked-words list, blank entries included. */
    fun keywords(): Set<String>
    fun removeKeywords(words: Collection<String>)

    /** The old "Block unknown numbers" switch. */
    fun blockUnknownSwitch(): Boolean
    fun clearBlockUnknownSwitch()
}

class AndroidBlockingMigrationStore(context: Context) : BlockingMigrationStore {
    private val app = context.applicationContext

    override fun keywords(): Set<String> = app.config.rawBlockedKeywords()

    override fun removeKeywords(words: Collection<String>) {
        if (words.isEmpty()) return
        app.config.removeBlockedKeywords(words)
    }

    override fun blockUnknownSwitch(): Boolean = app.baseConfig.blockUnknownNumbers

    override fun clearBlockUnknownSwitch() {
        // Remembered for restores of older backups (SettingsBackup.restoredMode); set before the switch is cleared.
        app.config.blockUnknownSwitchWasOn = true
        app.baseConfig.blockUnknownNumbers = false
    }
}

/**
 * Blocking is two lists: the Android block list and the uMessage rules. This moves what the older app versions kept
 * elsewhere into them, without changing what gets blocked:
 * - every old blocked word becomes a block keyword rule (the filter treats both the same: contacts are not held);
 *   words that cannot be a rule (one letter, several lines, too long, or written in quotes) stay on the old list,
 *   still filtering; blank words are dropped;
 * - the old "Block unknown numbers" switch only ever acted while Unknown senders was Off, where it behaved as Strict.
 *   So Off with the switch on becomes Strict; with Smart or Strict the switch did nothing and is just cleared.
 *
 * Safe to run any number of times (at start, and again after a restore of an older backup): an entry leaves its old
 * place only after its replacement is stored, so a failure leaves it where it was, still filtering.
 */
object BlockingMigration {

    /** What the old "Block unknown numbers" switch makes of [mode]. Only Off with the switch on changes (to Strict). */
    fun modeAfterSwitch(mode: UnknownSenderMode, switchOn: Boolean): UnknownSenderMode =
        if (switchOn && mode == UnknownSenderMode.OFF) UnknownSenderMode.STRICT else mode

    data class Result(val wordsMoved: Int = 0, val wordsKept: Int = 0, val modeChanged: Boolean = false)

    /**
     * Runs the move. [existingRules] and [addRule] are the rule store (adding a rule that already exists is a no-op);
     * [updateMode] applies a change to the unknown-senders mode. Never throws for a failed step: that step is skipped.
     */
    suspend fun run(
        store: BlockingMigrationStore,
        existingRules: suspend () -> List<SpamRule>,
        addRule: suspend (SpamRule) -> Unit,
        updateMode: suspend ((UnknownSenderMode) -> UnknownSenderMode) -> Unit,
        keywordNote: String = RuleNotes.OLD_WORD_LIST,
    ): Result {
        var modeChanged = false
        if (runCatching { store.blockUnknownSwitch() }.getOrDefault(false)) {
            val stored = runCatching {
                updateMode { current ->
                    modeAfterSwitch(current, switchOn = true).also { if (it != current) modeChanged = true }
                }
            }.isSuccess
            // Cleared only once the mode is safely stored; otherwise the filter keeps honouring the switch.
            if (stored) runCatching { store.clearBlockUnknownSwitch() } else modeChanged = false
        }

        val stored = runCatching { store.keywords() }.getOrDefault(emptySet())
        // Blank words never filter anything (the filter skips them): they are just dropped.
        val blank = stored.filter { it.isBlank() }
        if (blank.isNotEmpty()) runCatching { store.removeKeywords(blank) }
        val words = stored.filterNot { it.isBlank() }
        if (words.isEmpty()) return Result(modeChanged = modeChanged)
        val existing = runCatching { existingRules() }.getOrNull()
            ?: return Result(modeChanged = modeChanged, wordsKept = words.size)
        val plan = LegacyMigration.plan(
            LegacyLists(keywords = words.sortedBy { it.lowercase() }),
            existing,
            keywordNote = keywordNote,
        )
        val moved = mutableListOf<String>()
        for ((word, rule) in plan.keywordRules) {
            if (runCatching { addRule(rule) }.isSuccess) moved += word
        }
        runCatching { store.removeKeywords(moved) }
        return Result(
            wordsMoved = moved.size,
            wordsKept = words.size - moved.size,
            modeChanged = modeChanged,
        )
    }

    /** Runs [run] against the app's own stores. Never throws. */
    suspend fun runOnce(context: Context): Result = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        runCatching {
            // The real repository only: the debug stand-in must never take a user's words.
            val repository = UMessage.repository(app)
            run(
                store = AndroidBlockingMigrationStore(app),
                existingRules = { repository.rules.first() },
                addRule = { repository.addRule(it) },
                updateMode = { change -> repository.updateSettings { it.copy(mode = change(it.mode)) } },
            )
        }.getOrDefault(Result())
    }
}
