package com.glooboo.umessage.ui.spam

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.glooboo.umessage.spam.ListKind
import com.glooboo.umessage.spam.RuleType
import com.glooboo.umessage.spam.SpamRepository
import com.glooboo.umessage.spam.SpamRule

/**
 * State of the add/edit-rule bottom sheet. [validation] and [warnings] are recomputed on every edit. The sheet stays
 * open while [saving] and after a failed save ([saveFailed]), so the typed rule is never lost. [confirming] shows the
 * warnings dialog before a rule with [warnings] is saved. [editing] is the stored rule being changed (SU-09), null when
 * adding a new one.
 */
data class RuleForm(
    val kind: ListKind,
    val type: RuleType = RuleType.NUMBER,
    val pattern: String = "",
    val subscriptionId: Int? = null,
    val note: String = "",
    val validation: RuleValidation = RuleValidation.Invalid(RuleError.EMPTY),
    val warnings: List<RuleWarning> = emptyList(),
    val saving: Boolean = false,
    val saveFailed: Boolean = false,
    val confirming: Boolean = false,
    val editing: SpamRule? = null,
) {
    /** Errors are only shown once the user has typed something. */
    val visibleError: RuleValidation.Invalid?
        get() = (validation as? RuleValidation.Invalid)?.takeIf { pattern.isNotEmpty() }
    val canSubmit: Boolean get() = validation is RuleValidation.Valid && !saving
    val isEdit: Boolean get() = editing != null

    /**
     * The note to store. A note the app wrote ([RuleNotes]) is not put in the field, where it would turn into fixed
     * words; an empty field keeps it, and typing a note replaces it.
     */
    val storedNote: String
        get() = note.trim().ifEmpty { editing?.note?.takeIf(RuleNotes::isAutomatic).orEmpty() }

    /** An edit that changes nothing just closes the sheet. */
    val unchanged: Boolean
        get() = editing != null && editing.kind == kind && editing.type == type &&
            editing.subscriptionId == subscriptionId && editing.note == storedNote &&
            editing.pattern == (validation as? RuleValidation.Valid)?.pattern
}

/** The move-old-lists dialog (SU-33). Numbers are only moved, and only removed from the system list, when ticked. */
data class MigrationDialog(
    val plan: LegacyMigrationPlan,
    val importNumbers: Boolean = false,
    val removeFromSystem: Boolean = false,
    val running: Boolean = false,
)

data class RulesUiState(
    val loading: Boolean = true,
    val tab: ListKind = ListKind.ALLOW,
    val allowed: List<SpamRule> = emptyList(),
    val blocked: List<SpamRule> = emptyList(),
    val sims: List<SimInfo> = emptyList(),
    val form: RuleForm? = null,
    /** Search text and type filter (SU-09). */
    val query: String = "",
    val typeFilter: RuleType? = null,
    /** Rules picked with a long press, all on the current tab. */
    val selected: Set<Long> = emptySet(),
    /** The old Fossify lists not yet moved into rules, shown read-only on the Blocked tab. */
    val legacy: LegacyLists = LegacyLists(offerDismissed = true),
    val migration: MigrationDialog? = null,
) {
    val current: List<SpamRule> get() = if (tab == ListKind.ALLOW) allowed else blocked
    val filtering: Boolean get() = query.isNotBlank() || typeFilter != null
    val selecting: Boolean get() = selected.isNotEmpty()

    val visibleAllowed: List<SpamRule> get() = allowed.filter { RuleSearch.matches(it, query, typeFilter) }
    val visibleBlocked: List<SpamRule> get() = blocked.filter { RuleSearch.matches(it, query, typeFilter) }

    /** The rules of the current tab that pass the search and type filter. */
    val visible: List<SpamRule> get() = if (tab == ListKind.ALLOW) visibleAllowed else visibleBlocked

    /** Old-list words shown on the Blocked tab (they are block-only keywords). */
    val visibleLegacyKeywords: List<String>
        get() = if (typeFilter == null || typeFilter == RuleType.KEYWORD) {
            legacy.keywords.filter { RuleSearch.matchesText(listOf(it), query, numeric = false) }
        } else {
            emptyList()
        }

    val visibleLegacyNumbers: List<BlockedNumberItem>
        get() = if (typeFilter == null || typeFilter == RuleType.NUMBER || typeFilter == RuleType.PREFIX) {
            legacy.numbers.filter { RuleSearch.matchesText(listOfNotNull(it.number, it.contactName), query, numeric = true) }
        } else {
            emptyList()
        }

    /** The banner offering to move the old lists, until it is answered. */
    val showLegacyBanner: Boolean get() = !legacy.offerDismissed && !legacy.isEmpty
}

sealed interface RulesEvent {
    /** Rules were removed; [rules] lets the UI offer Undo. */
    data class Deleted(val rules: List<SpamRule>) : RulesEvent

    /** The old lists were moved: words and numbers now rules, numbers taken off the system list, entries left behind. */
    data class Migrated(val keywords: Int, val numbers: Int, val removedFromSystem: Int, val kept: Int) : RulesEvent

    /** Rules made from contacts picked in the add sheet; [skipped] numbers already had a rule or were not usable. */
    data class AddedFromContacts(val added: Int, val skipped: Int) : RulesEvent
}

class RulesViewModel(
    application: Application,
    private val repository: SpamRepository,
    private val legacyStore: LegacyBlockStore = NoLegacyBlockStore,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : AndroidViewModel(application) {

    private data class View(
        val tab: ListKind = ListKind.ALLOW,
        val query: String = "",
        val typeFilter: RuleType? = null,
        val selected: Set<Long> = emptySet(),
    )

    private data class Extras(
        val sims: List<SimInfo> = emptyList(),
        val legacy: LegacyLists = LegacyLists(offerDismissed = true),
        val migration: MigrationDialog? = null,
    )

    private val view = MutableStateFlow(View())
    private val form = MutableStateFlow<RuleForm?>(null)
    private val extras = MutableStateFlow(Extras())
    private val events = Channel<RulesEvent>(Channel.BUFFERED)
    val eventFlow = events.receiveAsFlow()

    val state: StateFlow<RulesUiState> = combine(repository.rules, view, form, extras) { rules, view, form, extras ->
        val sorted = rules.sortedByDescending { it.createdAt }
        val ids = sorted.map { it.id }.toSet()
        RulesUiState(
            loading = false,
            tab = view.tab,
            allowed = sorted.filter { it.kind == ListKind.ALLOW },
            blocked = sorted.filter { it.kind == ListKind.BLOCK },
            sims = extras.sims,
            form = form,
            query = view.query,
            typeFilter = view.typeFilter,
            selected = view.selected intersect ids,
            legacy = extras.legacy,
            migration = extras.migration,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RulesUiState())

    init {
        viewModelScope.launch {
            val sims = withContext(io) { runCatching { SimDirectory.load(getApplication()) }.getOrDefault(emptyList()) }
            extras.update { it.copy(sims = sims) }
        }
        refreshLegacy()
    }

    fun selectTab(kind: ListKind) = view.update {
        if (it.tab == kind) it else it.copy(tab = kind, selected = emptySet())
    }

    // region Search and selection (SU-09)

    fun setQuery(query: String) = view.update { it.copy(query = query.take(MAX_QUERY)) }
    fun setTypeFilter(type: RuleType?) = view.update { it.copy(typeFilter = type) }

    fun toggleSelected(id: Long) = view.update {
        it.copy(selected = if (id in it.selected) it.selected - id else it.selected + id)
    }

    fun selectAllVisible() = view.update { it.copy(selected = state.value.visible.map { rule -> rule.id }.toSet()) }
    fun clearSelection() = view.update { it.copy(selected = emptySet()) }

    private fun selectedRules(): List<SpamRule> = state.value.let { s -> s.current.filter { it.id in s.selected } }

    /** Deletes the picked rules in one go; the snackbar's Undo puts them back with their ids. */
    fun deleteSelected() {
        val rules = selectedRules()
        if (rules.isEmpty()) return
        clearSelection()
        viewModelScope.launch {
            runCatching { repository.removeRules(rules.map { it.id }) }.onSuccess { events.trySend(RulesEvent.Deleted(rules)) }
        }
    }

    // endregion

    // region Add and edit

    fun openForm() { form.value = RuleForm(kind = view.value.tab) }

    /**
     * The starter examples of the empty tabs (ID-53): the add sheet, already set to [kind] and [type], with a
     * concrete [value] filled in when the example has one.
     */
    fun openExample(kind: ListKind, type: RuleType, value: String = "") {
        val blank = RuleForm(kind = kind, type = type)
        form.value = if (value.isEmpty()) blank else revalidate(blank.copy(pattern = value))
    }

    /** Opens the same sheet as Add, filled in with [rule]; saving changes the rule in place. */
    fun openEdit(rule: SpamRule) {
        form.value = revalidate(
            RuleForm(
                kind = rule.kind,
                type = rule.type,
                pattern = rule.pattern,
                subscriptionId = rule.subscriptionId,
                // An app-written note stays a code; the sheet shows it as the field's placeholder.
                note = if (RuleNotes.isAutomatic(rule.note)) "" else rule.note,
                editing = rule,
            ),
        )
    }

    fun dismissForm() { form.value = null }

    /**
     * One rule per picked contact number in the sheet's list (Always allow or Always block), in the sheet's SIM
     * scope. Numbers that already have a rule are skipped; the snackbar says how many were added.
     */
    fun addFromContacts(picks: List<PickedNumber>) {
        val f = form.value ?: return
        val known = allRules().map { it.id }.toSet()
        val plan = ContactNumberPicks.plan(f.kind, f.subscriptionId, allRules(), picks)
        form.value = f.copy(saving = true, saveFailed = false)
        viewModelScope.launch {
            var added = 0
            var skipped = plan.skipped
            for (rule in plan.rules) {
                val id = runCatching { repository.addRule(rule) }.getOrNull()
                if (id != null && id !in known) added++ else skipped++
            }
            form.value = null
            events.trySend(RulesEvent.AddedFromContacts(added, skipped))
        }
    }

    /** Moves the rule being added or edited to the other list (Always allow / Always block). */
    fun setKind(kind: ListKind) = editForm { it.copy(kind = kind) }

    /** Deletes the rule open in the edit sheet; the snackbar offers Undo like any delete. */
    fun deleteEditing() {
        val rule = form.value?.editing ?: return
        form.value = null
        delete(rule)
    }

    fun setType(type: RuleType) = editForm { it.copy(type = type) }
    fun setPattern(pattern: String) = editForm { it.copy(pattern = pattern) }
    fun setSim(subscriptionId: Int?) = editForm { it.copy(subscriptionId = subscriptionId) }
    fun setNote(note: String) = editForm { it.copy(note = note.take(80)) }

    private fun editForm(change: (RuleForm) -> RuleForm) = form.update { current ->
        current?.let { revalidate(change(it).copy(saveFailed = false)) }
    }

    /** The form still holds [editing]'s own non-pattern value, unchanged. */
    private fun keepsStoredValue(f: RuleForm, editing: SpamRule): Boolean =
        f.type != RuleType.REGEX && editing.kind == f.kind && editing.type == f.type &&
            f.pattern.isNotBlank() && editing.pattern == f.pattern.trim()

    private fun revalidate(f: RuleForm): RuleForm {
        val editing = f.editing
        val checkedFormat = RuleValidator.validate(f.kind, f.type, f.pattern)
        // A value saved earlier and left as it is stays valid, so an old rule (e.g. a sender name with a space) can
        // still be edited. Only a changed value is held to today's format. Patterns are always checked.
        val result = if (checkedFormat is RuleValidation.Invalid && editing != null && keepsStoredValue(f, editing)) {
            RuleValidation.Valid(editing.pattern)
        } else {
            checkedFormat
        }
        // The rule being edited is not a duplicate of itself.
        val existing = allRules().filterNot { it.id == f.editing?.id }
        val checked = if (result is RuleValidation.Valid &&
            RuleValidator.isDuplicate(existing, f.kind, f.type, result.pattern, f.subscriptionId)
        ) {
            RuleValidation.Invalid(RuleError.DUPLICATE)
        } else {
            result
        }
        val warnings = (checked as? RuleValidation.Valid)?.let {
            RuleValidator.warnings(f.kind, f.type, it.pattern, existing, f.subscriptionId)
        }.orEmpty()
        return f.copy(validation = checked, warnings = warnings)
    }

    private fun allRules(): List<SpamRule> = state.value.allowed + state.value.blocked

    /** Saves the rule, or first asks to confirm its warnings. The sheet closes only once the rule is stored. */
    fun submitForm() {
        val f = form.value ?: return
        if (!f.canSubmit) return
        if (f.unchanged) {
            form.value = null
            return
        }
        if (f.warnings.isNotEmpty() && !f.confirming) {
            form.value = f.copy(confirming = true)
            return
        }
        save(f)
    }

    /** The user accepted the warnings. For a block of an allowed value the allow rule is removed too, as the dialog says. */
    fun confirmSubmit() {
        val f = form.value ?: return
        if (!f.canSubmit) return
        save(f)
    }

    fun cancelConfirm() = form.update { it?.copy(confirming = false) }

    private fun save(f: RuleForm) {
        val valid = f.validation as? RuleValidation.Valid ?: return
        val before = allRules()
        val editing = f.editing
        val knownIds = before.map { it.id }.toSet() - setOfNotNull(editing?.id)
        val conflicts = if (f.kind == ListKind.BLOCK && RuleWarning.ALLOWED_CONFLICT in f.warnings) {
            RuleValidator.conflictingAllows(before, f.type, valid.pattern, f.subscriptionId)
        } else {
            emptyList()
        }
        form.value = f.copy(saving = true, saveFailed = false, confirming = false)
        viewModelScope.launch {
            val rule = SpamRule(
                id = editing?.id ?: 0,
                kind = f.kind,
                type = f.type,
                pattern = valid.pattern,
                subscriptionId = f.subscriptionId,
                note = f.storedNote,
                createdAt = editing?.createdAt ?: System.currentTimeMillis(),
            )
            val id = runCatching { if (editing != null) repository.updateRule(rule) else repository.addRule(rule) }.getOrNull()
            when {
                id == null -> form.update { it?.copy(saving = false, saveFailed = true) }
                // The repository found the same rule after normalizing the number (e.g. 0555... and +1555...).
                id in knownIds -> form.update {
                    it?.copy(saving = false, validation = RuleValidation.Invalid(RuleError.DUPLICATE), warnings = emptyList())
                }
                else -> {
                    conflicts.forEach { runCatching { repository.removeRule(it.id) } }
                    form.value = null
                }
            }
        }
    }

    // endregion

    fun delete(rule: SpamRule) {
        viewModelScope.launch {
            runCatching { repository.removeRule(rule.id) }.onSuccess { events.trySend(RulesEvent.Deleted(listOf(rule))) }
        }
    }

    /** Undo of a delete: the rules come back with their old ids, so blocked messages' reasons find them again. */
    fun restore(rules: List<SpamRule>) {
        viewModelScope.launch { runCatching { repository.restoreRules(rules) } }
    }

    // region Old block lists (SU-33)

    /** Reloads the old lists; also called on resume, since the phone app can change the system list. */
    fun refreshLegacy() {
        viewModelScope.launch {
            val lists = withContext(io) { runCatching { legacyStore.load() }.getOrNull() } ?: return@launch
            extras.update { it.copy(legacy = lists) }
        }
    }

    fun openMigration() {
        val plan = LegacyMigration.plan(
            extras.value.legacy,
            allRules(),
            keywordNote = RuleNotes.OLD_WORD_LIST,
            numberNote = RuleNotes.SYSTEM_LIST,
        )
        extras.update { it.copy(migration = MigrationDialog(plan)) }
    }

    /**
     * The move dialog over the Android block list page (bug 9): the lists are read again first, since that page has
     * just changed them, and the rules must be loaded so numbers already covered are not moved twice.
     */
    fun openMigrationFresh() {
        viewModelScope.launch {
            val lists = withContext(io) { runCatching { legacyStore.load() }.getOrNull() } ?: return@launch
            extras.update { it.copy(legacy = lists) }
            state.first { !it.loading }
            openMigration()
        }
    }

    fun setMigrateNumbers(enabled: Boolean) =extras.update { e ->
        e.copy(migration = e.migration?.let { it.copy(importNumbers = enabled, removeFromSystem = enabled && it.removeFromSystem) })
    }

    /** Only offered once the numbers are being moved: removing them without a uMessage rule would unblock them. */
    fun setRemoveFromSystem(enabled: Boolean) = extras.update { e ->
        e.copy(migration = e.migration?.let { it.copy(removeFromSystem = enabled && it.importNumbers) })
    }

    fun cancelMigration() = extras.update { it.copy(migration = null) }

    /** "Not now" on the banner: it is not shown again, and the old lists stay on the Blocked tab with a Move button. */
    fun dismissLegacyBanner() {
        extras.update { it.copy(legacy = it.legacy.copy(offerDismissed = true)) }
        viewModelScope.launch { withContext(io) { runCatching { legacyStore.setOfferDismissed(true) } } }
    }

    /**
     * Runs the move the user confirmed. Each old entry leaves its list only after its rule is stored, so a failure
     * leaves it where it was (still filtering). A number leaves the system list only when the user ticked that, and
     * only once its uMessage rule exists, so it is never left unblocked.
     */
    fun confirmMigration() {
        val dialog = extras.value.migration ?: return
        if (dialog.running) return
        extras.update { it.copy(migration = dialog.copy(running = true)) }
        val plan = dialog.plan
        viewModelScope.launch {
            val movedKeywords = mutableListOf<String>()
            for ((word, rule) in plan.keywordRules) {
                if (runCatching { repository.addRule(rule) }.isSuccess) movedKeywords += word
            }
            var movedNumbers = 0
            var removed = 0
            var keptNumbers = if (dialog.importNumbers) plan.keptNumbers.size else 0
            if (dialog.importNumbers) {
                val covered = plan.coveredNumbers.toMutableList()
                for ((item, rule) in plan.numberRules) {
                    if (runCatching { repository.addRule(rule) }.isSuccess) {
                        movedNumbers++
                        covered += item
                    } else {
                        keptNumbers++
                    }
                }
                if (dialog.removeFromSystem) {
                    removed = withContext(io) { covered.count { runCatching { legacyStore.removeSystemNumber(it.id) }.getOrDefault(false) } }
                }
            }
            withContext(io) {
                runCatching { legacyStore.removeKeywords(movedKeywords) }
                runCatching { legacyStore.setOfferDismissed(true) }
            }
            val kept = plan.keptKeywords.size + (plan.keywordRules.size - movedKeywords.size) + keptNumbers
            extras.update { it.copy(migration = null) }
            refreshLegacy()
            events.trySend(RulesEvent.Migrated(movedKeywords.size, movedNumbers, removed, kept))
        }
    }

    // endregion

    companion object {
        private const val MAX_QUERY = 100

        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as Application
                RulesViewModel(app, SpamRepositoryProvider.get(app), AndroidLegacyBlockStore(app))
            }
        }
    }
}
