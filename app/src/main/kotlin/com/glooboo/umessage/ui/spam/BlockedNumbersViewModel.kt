package com.glooboo.umessage.ui.spam

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fossify.commons.extensions.baseConfig
import com.glooboo.umessage.spam.SpamRepository
import com.glooboo.umessage.spam.UnknownSenderMode

/** Add or edit dialog: [original] is null when adding a new number. */
data class BlockedNumberDialog(val original: BlockedNumberItem?)

data class BlockedNumbersUiState(
    val loading: Boolean = true,
    /** False when the app may not use the system block list (not the default SMS app). */
    val available: Boolean = true,
    val numbers: List<BlockedNumberItem> = emptyList(),
    val selected: Set<Long> = emptySet(),
    val blockUnknown: Boolean = false,
    val dialog: BlockedNumberDialog? = null,
    /** "Block unknown numbers" only does something in Off mode, so the switch shows only then (SE-28 / VS-30). */
    val mode: UnknownSenderMode = UnknownSenderMode.SMART,
    /** How many listed numbers "Move N numbers to rules" would add as rules; 0 hides the button. */
    val moveCount: Int = 0,
) {
    val selecting: Boolean get() = selected.isNotEmpty()
}

sealed interface BlockedNumbersEvent {
    /** Shown with an Undo action that blocks [numbers] again. */
    data class Unblocked(val numbers: List<String>) : BlockedNumbersEvent
    /** [skipped]: entries that are not phone numbers or were already blocked. */
    data class Imported(val count: Int, val skipped: Int = 0) : BlockedNumbersEvent
    data object ImportEmpty : BlockedNumbersEvent
    data object ImportFailed : BlockedNumbersEvent
    data class Exported(val count: Int) : BlockedNumbersEvent
    data object ExportEmpty : BlockedNumbersEvent
    data object ExportFailed : BlockedNumbersEvent
    data object Failed : BlockedNumbersEvent

    /**
     * [number] left the Android list and is now a uMessage block rule [ruleId] (SE-31); Undo puts it back. Calls from
     * it are no longer blocked, which the snackbar says.
     */
    data class MovedToRules(val number: String, val ruleId: Long) : BlockedNumbersEvent

    /** Added as a uMessage rule instead of to the Android list (a short code, BR-28). */
    data class AddedAsRule(val number: String) : BlockedNumbersEvent
}

class BlockedNumbersViewModel @JvmOverloads constructor(
    application: Application,
    private val repository: SpamRepository = SpamRepositoryProvider.get(application),
) : AndroidViewModel(application) {
    private val app get() = getApplication<Application>()
    private val mutableState = MutableStateFlow(BlockedNumbersUiState(blockUnknown = app.baseConfig.blockUnknownNumbers))
    val state: StateFlow<BlockedNumbersUiState> = mutableState.asStateFlow()

    private val events = Channel<BlockedNumbersEvent>(Channel.BUFFERED)
    val eventFlow = events.receiveAsFlow()

    init {
        refresh()
        viewModelScope.launch {
            runCatching { repository.settings.collect { s -> mutableState.update { it.copy(mode = s.mode) } } }
        }
    }

    /** Reloads the list; also called on resume, since the phone app can change the same list. */
    fun refresh() {
        viewModelScope.launch {
            val (available, numbers) = withContext(Dispatchers.IO) {
                val ok = SystemBlockList.isAvailable(app)
                ok to if (ok) SystemBlockList.load(app) else emptyList()
            }
            mutableState.update { state ->
                val ids = numbers.map { it.id }.toSet()
                state.copy(
                    loading = false,
                    available = available,
                    numbers = numbers,
                    selected = state.selected intersect ids,
                    blockUnknown = app.baseConfig.blockUnknownNumbers,
                )
            }
        }
    }

    fun toggleSelected(id: Long) = mutableState.update {
        it.copy(selected = if (id in it.selected) it.selected - id else it.selected + id)
    }

    fun clearSelection() = mutableState.update { it.copy(selected = emptySet()) }
    fun selectAll() = mutableState.update { state -> state.copy(selected = state.numbers.map { it.id }.toSet()) }

    fun openAdd() = mutableState.update { it.copy(dialog = BlockedNumberDialog(null)) }
    fun openEdit(item: BlockedNumberItem) = mutableState.update { it.copy(dialog = BlockedNumberDialog(item)) }
    fun dismissDialog() = mutableState.update { it.copy(dialog = null) }

    /** Saves the dialog. Editing replaces the old entry; an unchanged number just closes the dialog. */
    fun submit(raw: String) {
        val original = mutableState.value.dialog?.original
        val number = BlockedNumberFormat.normalizeInput(raw)
        mutableState.update { it.copy(dialog = null) }
        if (number.isEmpty() || number == original?.number || !BlockedNumberFormat.isValidEntry(number)) return
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                val added = SystemBlockList.add(app, number)
                if (added && original != null) SystemBlockList.remove(app, original.id)
                added
            }
            if (!ok) events.trySend(BlockedNumbersEvent.Failed)
            refresh()
        }
    }

    /** Numbers chosen from the contacts in the add dialog: each goes on the Android list unless it is already there. */
    fun addFromContacts(picks: List<PickedNumber>) {
        mutableState.update { it.copy(dialog = null) }
        viewModelScope.launch {
            val event = withContext(Dispatchers.IO) {
                val known = SystemBlockList.load(app).map { it.number }.toMutableList()
                var added = 0
                val numbers = ContactNumberPicks.distinct(picks).map { BlockedNumberFormat.normalizeInput(it.number) }
                for (number in numbers) {
                    val fresh = known.none { it == number || RuleValidator.sameNumber(it, number) }
                    val usable = BlockedNumberFormat.isValidEntry(number) && fresh
                    if (usable && SystemBlockList.add(app, number)) {
                        added++
                        known += number
                    }
                }
                BlockedNumbersEvent.Imported(added, picks.size - added)
            }
            events.trySend(event)
            refresh()
        }
    }

    fun unblock(ids: Set<Long>) {
        val items = mutableState.value.numbers.filter { it.id in ids }
        if (items.isEmpty()) return
        mutableState.update { it.copy(selected = emptySet()) }
        viewModelScope.launch {
            val removed = withContext(Dispatchers.IO) { items.filter { SystemBlockList.remove(app, it.id) } }
            if (removed.isEmpty()) {
                events.trySend(BlockedNumbersEvent.Failed)
            } else {
                events.trySend(BlockedNumbersEvent.Unblocked(removed.map { it.number }))
            }
            refresh()
        }
    }

    fun undoUnblock(numbers: List<String>) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { numbers.forEach { SystemBlockList.add(app, it) } }
            refresh()
        }
    }

    /**
     * "Move to uMessage rules" for one number (SE-31): a uMessage block rule takes its place, so its texts land in
     * Blocked where they can be read and restored, and it leaves the Android list. Undo reverses both.
     */
    fun moveToRules(item: BlockedNumberItem) {
        val rule = LegacyMigration.numberRule(item.number, RuleNotes.SYSTEM_LIST) ?: run {
            events.trySend(BlockedNumbersEvent.Failed)
            return
        }
        mutableState.update { it.copy(selected = emptySet()) }
        viewModelScope.launch {
            val ruleId = runCatching { repository.addRule(rule) }.getOrNull()
            val removed = ruleId != null && withContext(Dispatchers.IO) { SystemBlockList.remove(app, item.id) }
            when {
                ruleId == null -> events.trySend(BlockedNumbersEvent.Failed)
                !removed -> {
                    runCatching { repository.removeRule(ruleId) }
                    events.trySend(BlockedNumbersEvent.Failed)
                }
                else -> events.trySend(BlockedNumbersEvent.MovedToRules(item.number, ruleId))
            }
            refresh()
        }
    }

    fun undoMove(number: String, ruleId: Long) {
        viewModelScope.launch {
            runCatching { repository.removeRule(ruleId) }
            withContext(Dispatchers.IO) { SystemBlockList.add(app, number) }
            refresh()
        }
    }

    /** The short-code warning's "Add as a uMessage rule instead" (BR-28): a rule, not an Android list entry. */
    fun addAsRule(raw: String) {
        val number = BlockedNumberFormat.normalizeInput(raw)
        mutableState.update { it.copy(dialog = null) }
        val rule = LegacyMigration.numberRule(number, RuleNotes.BLOCKED_BY_YOU) ?: run {
            events.trySend(BlockedNumbersEvent.Failed)
            return
        }
        viewModelScope.launch {
            runCatching { repository.addRule(rule) }
                .onSuccess { events.trySend(BlockedNumbersEvent.AddedAsRule(rule.pattern)) }
                .onFailure { events.trySend(BlockedNumbersEvent.Failed) }
        }
    }

    fun setBlockUnknown(enabled: Boolean) {
        app.baseConfig.blockUnknownNumbers = enabled
        mutableState.update { it.copy(blockUnknown = enabled) }
    }

    fun importFrom(uri: Uri) {
        viewModelScope.launch {
            val event = withContext(Dispatchers.IO) {
                val text = runCatching {
                    app.contentResolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
                        // A wrong or huge file is refused instead of read whole into memory.
                        val buffer = CharArray(MAX_IMPORT_CHARS + 1)
                        var total = 0
                        while (total < buffer.size) {
                            val n = reader.read(buffer, total, buffer.size - total)
                            if (n < 0) break
                            total += n
                        }
                        if (total > MAX_IMPORT_CHARS) null else String(buffer, 0, total)
                    }
                }.getOrNull() ?: return@withContext BlockedNumbersEvent.ImportFailed
                val parsed = BlockedNumberFormat.parseImportChecked(text)
                if (parsed.numbers.isEmpty()) return@withContext BlockedNumbersEvent.ImportEmpty
                val existing = SystemBlockList.load(app).map { it.number }.toSet()
                val added = parsed.numbers.filter { it !in existing }.count { SystemBlockList.add(app, it) }
                BlockedNumbersEvent.Imported(added, parsed.skipped + parsed.numbers.size - added)
            }
            events.trySend(event)
            refresh()
        }
    }

    fun exportTo(uri: Uri) {
        viewModelScope.launch {
            val event = withContext(Dispatchers.IO) {
                val numbers = SystemBlockList.load(app).map { it.number }
                if (numbers.isEmpty()) return@withContext BlockedNumbersEvent.ExportEmpty
                runCatching {
                    app.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use {
                        it.write(BlockedNumberFormat.exportText(numbers))
                    } ?: error("No output stream")
                }.fold(
                    onSuccess = { BlockedNumbersEvent.Exported(numbers.size) },
                    onFailure = { BlockedNumbersEvent.ExportFailed },
                )
            }
            events.trySend(event)
        }
    }

    private companion object {
        /** About 1 MB of text: far more than any real block list. */
        const val MAX_IMPORT_CHARS = 1_000_000
    }
}
