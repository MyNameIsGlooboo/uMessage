package com.glooboo.umessage.ui.spam

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.R
import com.glooboo.umessage.spam.AllowGroup
import com.glooboo.umessage.spam.SpamSettings
import com.glooboo.umessage.testing.ProviderTruthFlagRule
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * Provider truth, step G (answer 18, U11): the filter switch asks first and names the chats that move; nothing changes
 * before the confirm. A count that is unknown gives a confirm without a number. Auto-delete warns before days are set.
 */
@RunWith(AndroidJUnit4::class)
class FilterSwitchConfirmTest {

    @get:Rule
    val flag = ProviderTruthFlagRule(enabled = true)

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val res = app.resources
    private val scope = CoroutineScope(Job() + Dispatchers.Main)
    private lateinit var repo: FakeSpamRepository
    private lateinit var vm: SettingsViewModel

    private fun start(settings: SpamSettings = SpamSettings(), counter: ChatMoveCounter = ChatMoveCounting.UNKNOWN) {
        repo = FakeSpamRepository(initialBlocked = emptyList(), initialRules = emptyList(), initialSettings = settings)
        vm = SettingsViewModel(app, repo, moveCounter = { counter })
        scope.launch { vm.state.collect { } }
        awaitUntil { !vm.state.value.loading }
    }

    @After
    fun tearDown() = scope.cancel()

    private fun stored(): SpamSettings = runBlocking { repo.settings.first() }

    @Test
    fun turningTheFilterOffAsksWithTheCountAndChangesNothingBeforeTheConfirm() {
        start(counter = { change -> if (change == SortChange.Filter(on = false)) 7 else null })
        vm.switches.request(SortChange.Filter(false))
        awaitUntil { vm.state.value.switchConfirm != null }

        assertThat(vm.state.value.switchConfirm).isEqualTo(SwitchConfirm(SortChange.Filter(false), 7))
        assertThat(stored().filterOn).isTrue()

        vm.switches.confirm()
        awaitUntil { !stored().filterOn }
        assertThat(vm.state.value.switchConfirm).isNull()
    }

    @Test
    fun cancelLeavesTheSwitchAsItWas() {
        start(counter = { 3 })
        vm.switches.request(SortChange.Filter(false))
        awaitUntil { vm.state.value.switchConfirm != null }
        vm.switches.dismiss()
        idleMain()
        assertThat(vm.state.value.switchConfirm).isNull()
        assertThat(stored().filterOn).isTrue()
    }

    @Test
    fun turningItOnAlsoAsks() {
        start(settings = SpamSettings(filterOn = false), counter = { 2 })
        vm.switches.request(SortChange.Filter(true))
        awaitUntil { vm.state.value.switchConfirm != null }
        assertThat(stored().filterOn).isFalse()
        vm.switches.confirm()
        awaitUntil { stored().filterOn }
    }

    @Test
    fun anUnknownOrFailedCountStillAsksWithoutANumber() {
        start(counter = { error("sorter not ready") })
        vm.switches.request(SortChange.Filter(false))
        awaitUntil { vm.state.value.switchConfirm != null }
        assertThat(vm.state.value.switchConfirm!!.chats).isNull()
        assertThat(stored().filterOn).isTrue()
    }

    @Test
    fun theConfirmTextNamesTheCountOrNothing() {
        val off = switchConfirmText(res, SwitchConfirm(SortChange.Filter(false), 4))
        assertThat(off.title).isEqualTo(res.getString(R.string.pt_filter_off_confirm_title))
        assertThat(off.body).isEqualTo(res.getQuantityString(R.plurals.pt_filter_off_confirm_body, 4, 4))
        assertThat(off.confirmLabel).isEqualTo(res.getString(R.string.pt_turn_off))

        val on = switchConfirmText(res, SwitchConfirm(SortChange.Filter(true), 1))
        assertThat(on.title).isEqualTo(res.getString(R.string.pt_filter_on_confirm_title))
        assertThat(on.body).isEqualTo(res.getQuantityString(R.plurals.pt_filter_on_confirm_body, 1, 1))
        assertThat(on.body).contains("1 chat ")
        assertThat(on.confirmLabel).isEqualTo(res.getString(R.string.cleanup_bin_confirm))

        val none = switchConfirmText(res, SwitchConfirm(SortChange.Filter(true), 0))
        assertThat(none.body).isEqualTo(res.getString(R.string.pt_confirm_none_move))

        val unknown = switchConfirmText(res, SwitchConfirm(SortChange.Filter(true), null))
        assertThat(unknown.body).isNull()
        assertThat(unknown.title).isEqualTo(res.getString(R.string.pt_filter_on_confirm_title))
    }

    @Test
    fun theGroupConfirmNamesTheGroupAndTheCodeGroupSaysCodesGoToSpam() {
        val code = switchConfirmText(res, SwitchConfirm(SortChange.Group(AllowGroup.CODE, false), 5))
        assertThat(code.title).isEqualTo(
            res.getString(R.string.pt_group_off_title, res.getString(R.string.pt_group_code_title)),
        )
        assertThat(code.body).isEqualTo(
            res.getQuantityString(R.plurals.pt_group_off_body, 5, 5) + "\n\n" +
                res.getString(R.string.pt_group_off_code_note),
        )

        val wrote = switchConfirmText(res, SwitchConfirm(SortChange.Group(AllowGroup.WROTE, false), null))
        assertThat(wrote.title).contains(res.getString(R.string.pt_group_wrote_title))
        assertThat(wrote.body).isNull()
    }

    @Test
    fun choosingDaysForAutoDeleteWarnsFirst() {
        start()
        vm.setPurgeDays(30)
        awaitUntil { vm.state.value.purgeWarning == 30 }
        assertThat(stored().purgeAfterDays).isEqualTo(0)

        vm.purge.confirm()
        awaitUntil { stored().purgeAfterDays == 30 }
        assertThat(vm.state.value.purgeWarning).isNull()
    }

    @Test
    fun keepForeverAndADismissedWarningWriteNoDays() {
        start(settings = SpamSettings(purgeAfterDays = 14))
        vm.setPurgeDays(90)
        awaitUntil { vm.state.value.purgeWarning == 90 }
        vm.purge.dismiss()
        idleMain()
        assertThat(stored().purgeAfterDays).isEqualTo(14)

        vm.setPurgeDays(0)
        awaitUntil { stored().purgeAfterDays == 0 }
        assertThat(vm.state.value.purgeWarning).isNull()
    }

    @Test
    fun withTheFlagOffDaysAreWrittenAtOnceAsIn0414() {
        com.glooboo.umessage.helpers.ProviderTruthFlag.enabled = false
        start(settings = SpamSettings(purgeAfterDays = 30))
        vm.setPurgeDays(7)
        awaitUntil { stored().purgeAfterDays == 7 }
        assertThat(vm.state.value.purgeWarning).isNull()
    }

    @Test
    fun aConfirmedChangeWritesOnlyItsOwnSwitch() {
        val base = SpamSettings(filterOn = true, codeGroupOn = true, wroteGroupOn = true)
        assertThat(base.applying(SortChange.Filter(false))).isEqualTo(base.copy(filterOn = false))
        assertThat(base.applying(SortChange.Group(AllowGroup.CODE, false))).isEqualTo(base.copy(codeGroupOn = false))
        assertThat(base.applying(SortChange.Group(AllowGroup.WROTE, false))).isEqualTo(base.copy(wroteGroupOn = false))
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
