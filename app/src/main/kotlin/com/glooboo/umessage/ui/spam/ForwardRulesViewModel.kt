package com.glooboo.umessage.ui.spam

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.glooboo.umessage.R
import com.glooboo.umessage.helpers.NavPrefs
import com.glooboo.umessage.spam.ForwardLogEntry
import com.glooboo.umessage.spam.ForwardRepository
import com.glooboo.umessage.spam.ForwardRule
import com.glooboo.umessage.spam.ForwardSim
import com.glooboo.umessage.spam.RuleType
import com.glooboo.umessage.spam.data.DefaultForwardLogRepository
import com.glooboo.umessage.spam.data.ForwardResend
import com.glooboo.umessage.spam.data.SpamDatabase
import com.glooboo.umessage.spam.engine.SpamEngine
import com.glooboo.umessage.ui.forwarded.ForwardedTabHint
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fossify.commons.extensions.baseConfig

data class ForwardUiState(
    val loading: Boolean = true,
    val rules: List<ForwardRule> = emptyList(),
    val sims: List<SimInfo> = emptyList(),
    val form: ForwardForm? = null,
    /** What the screen must ask before [pending] is applied. */
    val prompt: ForwardGatePrompt = ForwardGatePrompt.NONE,
    val pending: ForwardChange? = null,
    /** A change could not be stored. */
    val changeFailed: Boolean = false,
    /** The lock screen for [pending] is open (so it is not opened twice, e.g. after rotation). */
    val verifying: Boolean = false,
    /** The first forward rule was just saved: the screen says once "Forwarded tab added. Hide it?". */
    val forwardedTabAdded: Boolean = false,
    /** The last forward of each rule and its count this week, by rule id (ID-04). */
    val stats: Map<Long, ForwardRuleStats> = emptyMap(),
    /** A rule was just deleted: the screen offers Undo once, then calls [ForwardRulesViewModel.deletedShown]. */
    val deleted: ForwardRule? = null,
)

/** Sends the editor's test SMS to the targets from a SIM (null = default); returns how many went out. */
fun interface ForwardTestSender {
    fun send(targets: List<String>, subscriptionId: Int?): Int
}

/**
 * The Forward tab of the Rules screen. Every change that could start sending messages away (create, edit, enable,
 * delete) goes through [ForwardGatePolicy]: the app-lock check, or once a note when there is no app lock.
 */
class ForwardRulesViewModel(
    application: Application,
    private val repository: ForwardRepository,
    private val gate: ForwardLockGate,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val now: () -> Long = System::currentTimeMillis,
    log: Flow<List<ForwardLogEntry>> = flowOf(emptyList()),
    private val testSender: ForwardTestSender = ForwardTestSender { _, _ -> 0 },
) : AndroidViewModel(application) {

    private data class Ui(
        val sims: List<SimInfo> = emptyList(),
        val ownNumbers: List<String> = emptyList(),
        val form: ForwardForm? = null,
        val prompt: ForwardGatePrompt = ForwardGatePrompt.NONE,
        val pending: ForwardChange? = null,
        val changeFailed: Boolean = false,
        val verifying: Boolean = false,
        val forwardedTabAdded: Boolean = false,
        val deleted: ForwardRule? = null,
    )

    private val ui = MutableStateFlow(Ui())
    private var lastVerifiedAt: Long? = null

    /** When the user went to set up the app lock from the no-lock note (bug 7). */
    private var lockSetUpAt: Long? = null

    val state: StateFlow<ForwardUiState> = combine(
        repository.rules, ui, log.catch { emit(emptyList()) },
    ) { rules, ui, entries ->
        ForwardUiState(
            loading = false,
            rules = rules,
            sims = ui.sims,
            form = ui.form,
            prompt = ui.prompt,
            pending = ui.pending,
            changeFailed = ui.changeFailed,
            verifying = ui.verifying,
            forwardedTabAdded = ui.forwardedTabAdded,
            stats = ForwardRuleStats.of(entries, now()),
            deleted = ui.deleted,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ForwardUiState())

    init {
        viewModelScope.launch {
            val sims = withContext(io) { runCatching { SimDirectory.load(getApplication()) }.getOrDefault(emptyList()) }
            val own = withContext(io) {
                runCatching { ForwardOwnNumbers.load(getApplication()) }.getOrDefault(emptyList())
            }
            // A form opened before this finished (a thread's "Always forward") gets the own numbers too.
            ui.update { it.copy(sims = sims, ownNumbers = own, form = it.form?.copy(ownNumbers = own)) }
        }
    }

    // region Sheet

    fun openAdd() = ui.update { it.copy(form = ForwardForm(ownNumbers = it.ownNumbers), changeFailed = false) }

    /** The "Forward bank alerts" start of the empty state: any sender, a text to look for already filled in. */
    fun openAddForText(word: String) = ui.update {
        it.copy(
            form = ForwardForm(
                senderType = null, bodyType = RuleType.KEYWORD, bodyPattern = word, ownNumbers = it.ownNumbers,
            ),
            changeFailed = false,
        )
    }

    /** A new rule for one sender, from a thread's "Always forward from this sender": a number or a sender name. */
    fun openAddForSender(address: String) = ui.update {
        val type = if (address.any { c -> c.isLetter() }) RuleType.SENDER_ID else RuleType.NUMBER
        it.copy(
            form = ForwardForm(senderType = type, senderPattern = address.trim(), ownNumbers = it.ownNumbers),
            changeFailed = false,
        )
    }

    fun openEdit(rule: ForwardRule) =
        ui.update { it.copy(form = ForwardForm.of(rule).copy(ownNumbers = it.ownNumbers), changeFailed = false) }

    fun dismissForm() = ui.update { it.copy(form = null) }

    // A changed condition has to be accepted again if it is still very broad.
    fun setSenderType(type: RuleType?) = edit { it.copy(senderType = type, broadAccepted = false) }
    fun setSenderPattern(value: String) =
        edit { it.copy(senderPattern = value.take(RuleValidator.MAX_PATTERN + 1), broadAccepted = false) }
    fun setBodyType(type: RuleType?) = edit { it.copy(bodyType = type, broadAccepted = false) }
    fun setBodyPattern(value: String) =
        edit { it.copy(bodyPattern = value.take(RuleValidator.MAX_PATTERN + 1), broadAccepted = false) }
    fun setUseWindow(on: Boolean) = edit { it.copy(useWindow = on) }
    fun setWindowStart(minutes: Int) = edit { it.copy(windowStart = minutes.coerceIn(0, ForwardRuleMinutes.DAY - 1)) }
    fun setWindowEnd(minutes: Int) = edit { it.copy(windowEnd = minutes.coerceIn(0, ForwardRuleMinutes.DAY - 1)) }
    fun setScope(subscriptionId: Int?) = edit { it.copy(subscriptionId = subscriptionId) }
    fun setSendFrom(sim: ForwardSim) = edit { it.copy(sendFrom = sim) }
    fun setNote(note: String) = edit { it.copy(note = note.take(MAX_NOTE)) }

    /** A number chosen from the contacts: the rule matches that sender, and the contact's name becomes the note. */
    fun pickSender(pick: PickedNumber) = edit {
        it.copy(
            senderType = RuleType.NUMBER,
            senderPattern = pick.number.take(RuleValidator.MAX_PATTERN + 1),
            note = it.note.ifBlank { pick.name.take(MAX_NOTE) },
            broadAccepted = false,
        )
    }

    // A changed number needs its own test again.
    fun setTarget(index: Int, value: String) = edit { form ->
        if (index !in form.targets.indices) {
            form
        } else {
            form.copy(targets = form.targets.toMutableList().also { it[index] = value.take(MAX_TARGET) }).testReset()
        }
    }

    fun addTarget() = edit { if (it.canAddTarget) it.copy(targets = it.targets + "").testReset() else it }

    fun removeTarget(index: Int) = edit { form ->
        if (index !in form.targets.indices || form.targets.size <= 1) {
            form
        } else {
            form.copy(targets = form.targets.toMutableList().also { it.removeAt(index) }).testReset()
        }
    }

    private fun ForwardForm.testReset() =
        if (test == ForwardTestState.SENDING) this else copy(test = ForwardTestState.IDLE)

    /** Turning codes on first shows the warning; turning them off is immediate. */
    fun setIncludeCodes(on: Boolean) =
        edit { if (on) it.copy(confirmingCodes = true) else it.copy(includeCodes = false) }
    fun confirmIncludeCodes() = edit { it.copy(includeCodes = true, confirmingCodes = false) }
    fun cancelIncludeCodes() = edit { it.copy(confirmingCodes = false) }

    private fun edit(change: (ForwardForm) -> ForwardForm) = ui.update { u ->
        u.copy(form = u.form?.let { change(it).copy(saveFailed = false) })
    }

    /** Saves the sheet, after the app-lock check. The sheet closes only once the rule is stored. */
    fun submit() {
        val form = ui.value.form ?: return
        if (!form.canSubmit) return
        // A rule that would forward very many messages asks once more before it can cost anything.
        if (form.isBroad && !form.broadAccepted) {
            edit { it.copy(confirmingBroad = true) }
            return
        }
        request(ForwardChange.Save(form.toRule(now())))
    }

    /** The user accepted the very-many-messages warning: the rule goes on to the app-lock check and is saved. */
    fun confirmBroad() {
        edit { it.copy(confirmingBroad = false, broadAccepted = true) }
        submit()
    }

    fun cancelBroad() = edit { it.copy(confirmingBroad = false) }

    /** "Delete rule" in the edit sheet (VS-14): after the app-lock check; the list then offers Undo. */
    fun deleteFromForm() {
        val rule = ui.value.form?.editing ?: return
        request(ForwardChange.Delete(rule))
    }

    // endregion

    // region Send test (ID-07)

    /** "Send test" asks first: it is a paid SMS to every number. */
    fun askTest() = edit { if (it.canSendTest) it.copy(test = ForwardTestState.CONFIRMING) else it }

    fun cancelTest() =
        edit { if (it.test == ForwardTestState.CONFIRMING) it.copy(test = ForwardTestState.IDLE) else it }

    /** Sends one test SMS to each number, from the SIM the rule sends from (the default SIM for "same SIM"). */
    fun confirmTest() {
        val form = ui.value.form ?: return
        if (form.test != ForwardTestState.CONFIRMING) return
        val targets = form.cleanTargets
        val sim = testSim(form, ui.value.sims)
        edit { it.copy(test = ForwardTestState.SENDING) }
        viewModelScope.launch {
            val sent = withContext(io) { runCatching { testSender.send(targets, sim) }.getOrDefault(0) }
            val result = if (sent == targets.size) ForwardTestState.SENT else ForwardTestState.FAILED
            edit { if (it.test == ForwardTestState.SENDING) it.copy(test = result) else it }
        }
    }

    private fun testSim(form: ForwardForm, sims: List<SimInfo>): Int? = when (form.sendFrom) {
        ForwardSim.SIM1 -> sims.firstOrNull { it.slot == 1 }?.subscriptionId
        ForwardSim.SIM2 -> sims.firstOrNull { it.slot == 2 }?.subscriptionId
        ForwardSim.SAME -> form.subscriptionId
    }

    // endregion

    // region List

    /** Turning a rule on asks for the app lock; turning it off does not. */
    fun setEnabled(rule: ForwardRule, enabled: Boolean) {
        if (rule.enabled == enabled) return
        request(ForwardChange.SetEnabled(rule, enabled))
    }

    /** Puts a just-deleted rule back (the Undo of the snackbar), through the same check as any new rule. */
    fun undoDelete(rule: ForwardRule) {
        ui.update { it.copy(deleted = null) }
        request(ForwardChange.Save(rule.copy(id = 0)))
    }

    /** The Undo snackbar for [ForwardUiState.deleted] was shown. */
    fun deletedShown() = ui.update { it.copy(deleted = null) }

    // endregion

    // region App-lock gate

    private fun request(change: ForwardChange) {
        val prompt = ForwardGatePolicy.prompt(change, gate, lastVerifiedAt, now(), lockSetUpAt)
        if (prompt == ForwardGatePrompt.NONE) {
            apply(change)
        } else {
            ui.update { it.copy(prompt = prompt, pending = change) }
        }
    }

    /** The screen opened the lock screen for the pending change. */
    fun onVerifyStarted() = ui.update { it.copy(verifying = true) }

    /** The lock screen answered: [confirmed] applies the pending change, otherwise it is dropped (the sheet stays). */
    fun onVerified(confirmed: Boolean) {
        val change = ui.value.pending
        ui.update { it.copy(prompt = ForwardGatePrompt.NONE, pending = null, verifying = false) }
        if (!confirmed || change == null) return
        lastVerifiedAt = now()
        apply(change)
    }

    /**
     * The no-app-lock note was answered. It is shown once; [proceed] applies the change. Otherwise the user goes to
     * set up the app lock, and the change they come back to save does not ask for the new PIN at once.
     */
    fun onNoLockNote(proceed: Boolean) {
        val change = ui.value.pending
        runCatching { gate.markNoLockNoteShown() }
        ui.update { it.copy(prompt = ForwardGatePrompt.NONE, pending = null) }
        if (proceed && change != null) apply(change)
        if (!proceed) lockSetUpAt = now()
    }

    private fun apply(change: ForwardChange) {
        val priorRuleCount = state.value.rules.size
        if (change is ForwardChange.Save) {
            ui.update { u -> u.copy(form = u.form?.copy(saving = true, saveFailed = false)) }
        }
        viewModelScope.launch {
            val ok = runCatching {
                when (change) {
                    is ForwardChange.Save ->
                        if (change.rule.id == 0L) repository.add(change.rule) else repository.update(change.rule)
                    is ForwardChange.SetEnabled -> repository.setEnabled(change.rule.id, change.enabled)
                    is ForwardChange.Delete -> repository.remove(change.rule.id)
                }
            }.isSuccess
            ui.update { u ->
                when {
                    change is ForwardChange.Save && ok -> u.copy(
                        form = null,
                        forwardedTabAdded = u.forwardedTabAdded ||
                            (change.rule.id == 0L && claimForwardedTabHint(priorRuleCount)),
                    )
                    change is ForwardChange.Save -> u.copy(form = u.form?.copy(saving = false, saveFailed = true))
                    change is ForwardChange.Delete && ok -> u.copy(
                        form = u.form?.takeIf { it.editing?.id != change.rule.id },
                        deleted = change.rule,
                    )
                    else -> u.copy(changeFailed = !ok)
                }
            }
        }
    }

    /** True once per install, for the first rule when the user has not hidden the tab: marks the message as shown. */
    private fun claimForwardedTabHint(priorRuleCount: Int): Boolean = runCatching {
        val prefs = NavPrefs.get(getApplication())
        val show = ForwardedTabHint.shouldShow(priorRuleCount, prefs.showForwardedTab, prefs.forwardedTabHintShown)
        if (show) prefs.forwardedTabHintShown = true
        show
    }.getOrDefault(false)

    /** "Hide" on the one-time message: the Forwarded tab goes away (the Settings switch follows). */
    fun hideForwardedTab() {
        runCatching { NavPrefs.get(getApplication()).showForwardedTab = false }
        ui.update { it.copy(forwardedTabAdded = false) }
    }

    /** The one-time message went away without "Hide": the tab stays. */
    fun forwardedTabHintDone() = ui.update { it.copy(forwardedTabAdded = false) }

    fun dismissChangeFailed() = ui.update { it.copy(changeFailed = false) }

    // endregion

    companion object {
        private const val STOP_TIMEOUT_MS = 5_000L
        private const val MAX_NOTE = 80
        private const val MAX_TARGET = 24

        /** The name a test SMS says it is from: "[from uMessage] …". */
        private const val TEST_SENDER = "uMessage"

        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as Application
                ForwardRulesViewModel(
                    app,
                    SpamEngine.forwardRepository(app),
                    AndroidForwardLockGate(app),
                    log = flow {
                        emitAll(DefaultForwardLogRepository(SpamDatabase.getInstance(app).forwardLogDao()).entries)
                    }.flowOn(Dispatchers.IO),
                    testSender = { targets, sim ->
                        val text = app.getString(R.string.fwdfix_test_text)
                        ForwardResend.sendTest(app, targets, sim, TEST_SENDER, text)
                    },
                )
            }
        }
    }
}

/** The commons app-lock switch, and a one-time flag for the no-lock note in the app's own preferences. */
class AndroidForwardLockGate(context: Context) : ForwardLockGate {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun lockEnabled(): Boolean = runCatching { app.baseConfig.isAppPasswordProtectionOn }.getOrDefault(false)
    override fun noLockNoteShown(): Boolean = prefs.getBoolean(KEY_NOTE_SHOWN, false)
    override fun markNoLockNoteShown() = prefs.edit().putBoolean(KEY_NOTE_SHOWN, true).apply()

    private companion object {
        const val PREFS = "spamless_forward"
        const val KEY_NOTE_SHOWN = "no_lock_note_shown"
    }
}
