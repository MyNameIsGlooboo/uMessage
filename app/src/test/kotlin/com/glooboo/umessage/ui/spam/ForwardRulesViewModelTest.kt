package com.glooboo.umessage.ui.spam

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.spam.ForwardRepository
import com.glooboo.umessage.spam.ForwardRule
import com.glooboo.umessage.spam.RuleType
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/** The app-lock gate of the Forward tab, and the sheet's validation and codes warning. */
@RunWith(AndroidJUnit4::class)
class ForwardRulesViewModelTest {

    private class Repo : ForwardRepository {
        val state = MutableStateFlow<List<ForwardRule>>(emptyList())
        var fail = false
        private var nextId = 1L
        override val rules = state
        override fun currentRules() = state.value
        override suspend fun add(rule: ForwardRule): Long {
            if (fail) error("disk full")
            val id = nextId++
            state.update { it + rule.copy(id = id) }
            return id
        }
        override suspend fun update(rule: ForwardRule) = state.update { list -> list.map { if (it.id == rule.id) rule else it } }
        override suspend fun setEnabled(id: Long, enabled: Boolean) =
            state.update { list -> list.map { if (it.id == id) it.copy(enabled = enabled) else it } }
        override suspend fun remove(id: Long) = state.update { list -> list.filterNot { it.id == id } }
    }

    private class Gate(var lock: Boolean, var noteShown: Boolean = false) : ForwardLockGate {
        override fun lockEnabled() = lock
        override fun noLockNoteShown() = noteShown
        override fun markNoLockNoteShown() {
            noteShown = true
        }
    }

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(Job() + Dispatchers.Main)
    private var clock = 1_000_000L
    private lateinit var repo: Repo
    private lateinit var gate: Gate
    private lateinit var vm: ForwardRulesViewModel

    private fun start(lock: Boolean, noteShown: Boolean = false) {
        repo = Repo()
        gate = Gate(lock, noteShown)
        vm = ForwardRulesViewModel(app, repo, gate, now = { clock })
        scope.launch { vm.state.collect { } }
        awaitUntil { !vm.state.value.loading }
    }

    @After
    fun tearDown() = scope.cancel()

    private fun fillValidForm() {
        vm.openAdd()
        vm.setSenderType(RuleType.NUMBER)
        vm.setSenderPattern("+44 712 123 4567")
        vm.setTarget(0, "+1 555 000 1111")
        idleMain()
    }

    @Test
    fun withTheAppLockOn_savingAsksFirstAndSavesOnlyAfterConfirmation() {
        start(lock = true)
        fillValidForm()
        vm.submit()
        idleMain()
        assertThat(vm.state.value.prompt).isEqualTo(ForwardGatePrompt.VERIFY)
        assertThat(repo.state.value).isEmpty()

        vm.onVerifyStarted()
        vm.onVerified(confirmed = true)
        awaitUntil { vm.state.value.form == null }
        val saved = repo.state.value.single()
        assertThat(saved.senderPattern).isEqualTo("+447121234567")
        assertThat(saved.targets).containsExactly("+15550001111")
        assertThat(saved.includeCodes).isFalse()
        assertThat(vm.state.value.prompt).isEqualTo(ForwardGatePrompt.NONE)
    }

    @Test
    fun cancelledConfirmation_savesNothingAndKeepsTheSheet() {
        start(lock = true)
        fillValidForm()
        vm.submit()
        idleMain()
        vm.onVerified(confirmed = false)
        idleMain()
        assertThat(repo.state.value).isEmpty()
        assertThat(vm.state.value.form?.senderPattern).isEqualTo("+44 712 123 4567")
        assertThat(vm.state.value.pending).isNull()
    }

    @Test
    fun enableAndDeleteAsk_disableDoesNot_andAConfirmationCoversAMinute() {
        start(lock = true)
        repo.state.value = listOf(ForwardRule(id = 7, senderType = RuleType.NUMBER, senderPattern = "1234567", targets = listOf("+15550001111")))
        idleMain()
        val rule = repo.state.value.single()

        vm.setEnabled(rule, false)
        awaitUntil { !repo.state.value.single().enabled }
        assertThat(vm.state.value.prompt).isEqualTo(ForwardGatePrompt.NONE)

        vm.setEnabled(rule.copy(enabled = false), true)
        idleMain()
        assertThat(vm.state.value.prompt).isEqualTo(ForwardGatePrompt.VERIFY)
        assertThat(repo.state.value.single().enabled).isFalse()
        vm.onVerified(true)
        awaitUntil { repo.state.value.single().enabled }

        // Within the grace period the next change does not ask again.
        clock += ForwardGatePolicy.GRACE_MS - 1
        vm.openEdit(repo.state.value.single())
        vm.deleteFromForm()
        awaitUntil { repo.state.value.isEmpty() }

        // Later it asks again.
        repo.state.value = listOf(rule.copy(enabled = false))
        idleMain()
        clock += ForwardGatePolicy.GRACE_MS
        vm.openEdit(repo.state.value.single())
        vm.deleteFromForm()
        idleMain()
        assertThat(vm.state.value.prompt).isEqualTo(ForwardGatePrompt.VERIFY)
        assertThat(repo.state.value).hasSize(1)
    }

    @Test
    fun withoutAnAppLock_theNoteIsShownOnceThenChangesGoThrough() {
        start(lock = false)
        fillValidForm()
        vm.submit()
        idleMain()
        assertThat(vm.state.value.prompt).isEqualTo(ForwardGatePrompt.NO_LOCK_NOTE)
        assertThat(repo.state.value).isEmpty()

        vm.onNoLockNote(proceed = true)
        awaitUntil { repo.state.value.size == 1 }
        assertThat(gate.noteShown).isTrue()

        fillValidForm()
        vm.setTarget(0, "+15550002222")
        vm.submit()
        awaitUntil { repo.state.value.size == 2 }
        assertThat(vm.state.value.prompt).isEqualTo(ForwardGatePrompt.NONE)
    }

    @Test
    fun setUpAppLockFromTheNote_doesNotSave() {
        start(lock = false)
        fillValidForm()
        vm.submit()
        idleMain()
        vm.onNoLockNote(proceed = false)
        idleMain()
        assertThat(repo.state.value).isEmpty()
        assertThat(gate.noteShown).isTrue()
        assertThat(vm.state.value.form).isNotNull()
    }

    @Test
    fun includingCodesNeedsTheWarningToBeAccepted() {
        start(lock = false, noteShown = true)
        fillValidForm()
        vm.setIncludeCodes(true)
        idleMain()
        assertThat(vm.state.value.form!!.confirmingCodes).isTrue()
        assertThat(vm.state.value.form!!.includeCodes).isFalse()
        vm.cancelIncludeCodes()
        idleMain()
        assertThat(vm.state.value.form!!.includeCodes).isFalse()

        vm.setIncludeCodes(true)
        vm.confirmIncludeCodes()
        vm.submit()
        awaitUntil { repo.state.value.isNotEmpty() }
        assertThat(repo.state.value.single().includeCodes).isTrue()
    }

    @Test
    fun invalidFormsCannotBeSubmitted() {
        start(lock = false, noteShown = true)
        vm.openAdd()
        vm.setSenderType(null)
        vm.setTarget(0, "+15550001111")
        idleMain()
        assertThat(vm.state.value.form!!.noCondition).isTrue()
        assertThat(vm.state.value.form!!.canSubmit).isFalse()

        vm.setBodyType(RuleType.KEYWORD)
        vm.setBodyPattern("واریز")
        vm.setTarget(0, "12")
        idleMain()
        assertThat(vm.state.value.form!!.targetErrors).containsExactly(TargetError.INVALID)

        vm.setTarget(0, "+15550001111")
        vm.addTarget()
        vm.setTarget(1, "15550001111")
        idleMain()
        assertThat(vm.state.value.form!!.targetErrors).containsExactly(null, TargetError.DUPLICATE).inOrder()
        vm.submit()
        idleMain()
        assertThat(repo.state.value).isEmpty()

        vm.removeTarget(1)
        vm.addTarget()
        vm.addTarget()
        vm.addTarget()
        idleMain()
        assertThat(vm.state.value.form!!.targets).hasSize(3)
        assertThat(vm.state.value.form!!.canSubmit).isTrue()
    }

    @Test
    fun failedSaveKeepsTheSheetOpen() {
        start(lock = false, noteShown = true)
        repo.fail = true
        fillValidForm()
        vm.submit()
        awaitUntil { vm.state.value.form?.saveFailed == true }
        assertThat(vm.state.value.form!!.saving).isFalse()
    }

    /** VS-14: delete from the edit sheet closes it and offers Undo, which brings the rule back. */
    @Test
    fun deleteFromTheSheetOffersUndo() {
        start(lock = false, noteShown = true)
        repo.state.value = listOf(ForwardRule(id = 7, senderType = RuleType.NUMBER, senderPattern = "1234567", targets = listOf("+15550001111")))
        idleMain()
        vm.openEdit(repo.state.value.single())
        vm.deleteFromForm()
        awaitUntil { repo.state.value.isEmpty() }
        assertThat(vm.state.value.form).isNull()
        val deleted = vm.state.value.deleted!!
        assertThat(deleted.id).isEqualTo(7)

        vm.undoDelete(deleted)
        awaitUntil { repo.state.value.size == 1 }
        assertThat(repo.state.value.single().senderPattern).isEqualTo("1234567")
        assertThat(vm.state.value.deleted).isNull()
    }

    /** Bug 7: after "Set up app lock" from the note, the change saved on return does not ask for the new PIN. */
    @Test
    fun afterSettingUpTheLockFromTheNote_theNextSaveDoesNotAskAgain() {
        start(lock = false)
        fillValidForm()
        vm.submit()
        idleMain()
        vm.onNoLockNote(proceed = false)
        idleMain()

        gate.lock = true
        clock += ForwardGatePolicy.SETUP_GRACE_MS - 1
        vm.submit()
        awaitUntil { repo.state.value.size == 1 }
        assertThat(vm.state.value.prompt).isEqualTo(ForwardGatePrompt.NONE)

        // The grace does not last: a later change asks for the lock.
        clock += ForwardGatePolicy.SETUP_GRACE_MS
        vm.setEnabled(repo.state.value.single().copy(enabled = false), true)
        idleMain()
        assertThat(vm.state.value.prompt).isEqualTo(ForwardGatePrompt.VERIFY)
    }

    /** ID-07: the test SMS is asked for first, goes to every number once, and a changed number resets it. */
    @Test
    fun sendTestAsksFirstThenSendsToEveryNumber() {
        repo = Repo()
        gate = Gate(lock = false, noteShown = true)
        val sent = mutableListOf<List<String>>()
        val testSender = ForwardTestSender { targets, _ ->
            sent += targets
            targets.size
        }
        vm = ForwardRulesViewModel(app, repo, gate, now = { clock }, testSender = testSender)
        scope.launch { vm.state.collect { } }
        awaitUntil { !vm.state.value.loading }
        fillValidForm()
        vm.addTarget()
        vm.setTarget(1, "+1 555 000 2222")
        idleMain()

        vm.askTest()
        idleMain()
        assertThat(vm.state.value.form!!.test).isEqualTo(ForwardTestState.CONFIRMING)
        assertThat(sent).isEmpty()
        vm.cancelTest()
        idleMain()
        assertThat(vm.state.value.form!!.test).isEqualTo(ForwardTestState.IDLE)

        vm.askTest()
        vm.confirmTest()
        awaitUntil { vm.state.value.form!!.test == ForwardTestState.SENT }
        assertThat(sent.single()).containsExactly("+15550001111", "+15550002222")

        vm.setTarget(1, "+1 555 000 3333")
        idleMain()
        assertThat(vm.state.value.form!!.test).isEqualTo(ForwardTestState.IDLE)
        assertThat(repo.state.value).isEmpty()
    }

    private fun saveOneRule() {
        fillValidForm()
        vm.submit()
        idleMain()
        vm.onNoLockNote(proceed = true)
        awaitUntil { repo.state.value.isNotEmpty() && vm.state.value.form == null }
    }

    @Test
    fun theFirstRuleShowsTheForwardedTabHintOnce_andIgnoringItKeepsTheTab() {
        val prefs = com.glooboo.umessage.helpers.NavPrefs.get(app)
        start(lock = false)
        saveOneRule()
        assertThat(vm.state.value.forwardedTabAdded).isTrue()
        assertThat(prefs.forwardedTabHintShown).isTrue()

        vm.forwardedTabHintDone()
        idleMain()
        assertThat(vm.state.value.forwardedTabAdded).isFalse()
        assertThat(prefs.showForwardedTab).isNull() // never chosen: the tab stays on

        // Second rule: no message again.
        saveOneRule()
        assertThat(vm.state.value.forwardedTabAdded).isFalse()
    }

    @Test
    fun hideOnTheHintTurnsTheTabOff() {
        val prefs = com.glooboo.umessage.helpers.NavPrefs.get(app)
        start(lock = false)
        saveOneRule()
        vm.hideForwardedTab()
        idleMain()
        assertThat(vm.state.value.forwardedTabAdded).isFalse()
        assertThat(prefs.showForwardedTab).isFalse()
    }

    @Test
    fun noHintWhenTheUserAlreadyHidTheTab_orWhenItWasShownBefore() {
        val prefs = com.glooboo.umessage.helpers.NavPrefs.get(app)
        prefs.showForwardedTab = false
        start(lock = false)
        saveOneRule()
        assertThat(vm.state.value.forwardedTabAdded).isFalse()
        assertThat(prefs.forwardedTabHintShown).isFalse()
    }

    private fun idleMain() = shadowOf(Looper.getMainLooper()).idle()

    private fun awaitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (true) {
            idleMain()
            if (condition()) return
            check(System.currentTimeMillis() < deadline) { "condition not met; state=${vm.state.value}" }
            Thread.sleep(10)
        }
    }
}
