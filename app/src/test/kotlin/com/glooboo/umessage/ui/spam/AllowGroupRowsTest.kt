package com.glooboo.umessage.ui.spam

import android.app.Application
import android.os.Looper
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.R
import com.glooboo.umessage.spam.AllowGroup
import com.glooboo.umessage.spam.AllowGroupMember
import com.glooboo.umessage.spam.ListKind
import com.glooboo.umessage.spam.MemberSource
import com.glooboo.umessage.spam.SpamSettings
import com.glooboo.umessage.testing.ProviderTruthFlagRule
import com.glooboo.umessage.ui.components.displayNumber
import com.glooboo.umessage.ui.components.isolateNumbers
import com.glooboo.umessage.ui.theme.UMessageTheme
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
import org.robolectric.annotation.Config
import java.util.Collections

/**
 * D3 A, 4.5: the two group rules at the top of the allow list, each with a count, its own switch (off asks first with
 * the chats that move) and a member list where "Remove" is undone from the snackbar.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp")
class AllowGroupRowsTest {

    @get:Rule
    val flag = ProviderTruthFlagRule(enabled = true)

    @get:Rule
    val compose = createComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val res = app.resources
    private val scope = CoroutineScope(Job() + Dispatchers.Main)
    private val events: MutableList<RulesEvent> = Collections.synchronizedList(mutableListOf())
    private lateinit var repo: FakeSpamRepository
    private lateinit var vm: RulesViewModel

    private val codeMember = AllowGroupMember(AllowGroup.CODE, "acmebank", "AcmeBank", addedAt = 2_000)
    private val wroteMember = AllowGroupMember(
        AllowGroup.WROTE, "+15550001234", "+15550001234", addedAt = 1_000, source = MemberSource.HISTORY,
    )

    private fun start(counter: ChatMoveCounter = ChatMoveCounting.UNKNOWN, settings: SpamSettings = SpamSettings()) {
        repo = FakeSpamRepository(initialBlocked = emptyList(), initialRules = emptyList(), initialSettings = settings)
        runBlocking { repo.restoreGroupMembers(listOf(codeMember, wroteMember)) }
        vm = RulesViewModel(app, repo, moveCounter = { counter })
        scope.launch { vm.state.collect { } }
        scope.launch { vm.eventFlow.collect { events += it } }
        awaitUntil { !vm.state.value.loading && vm.state.value.groups.rows.isNotEmpty() }
    }

    @After
    fun tearDown() = scope.cancel()

    private fun stored(): SpamSettings = runBlocking { repo.settings.first() }
    private fun members(): List<AllowGroupMember> = runBlocking { repo.groupMembers.first() }

    @Test
    fun bothGroupsShowWithTheirOwnMembersAndSwitches() {
        val state = AllowGroupsState.of(
            listOf(codeMember, wroteMember, codeMember.copy(senderKey = "shop", address = "Shop", addedAt = 3_000)),
            SpamSettings(codeGroupOn = false, wroteGroupOn = true),
            open = null,
            confirm = null,
            shown = true,
        )
        assertThat(state.rows.map { it.group }).containsExactly(AllowGroup.CODE, AllowGroup.WROTE).inOrder()
        assertThat(state.rows[0].on).isFalse()
        assertThat(state.rows[1].on).isTrue()
        // Newest first.
        assertThat(state.rows[0].members.map { it.address }).containsExactly("Shop", "AcmeBank").inOrder()
        assertThat(state.rows[1].count).isEqualTo(1)
    }

    @Test
    fun withTheFlagOffThereAreNoGroupRows() {
        val state = AllowGroupsState.of(listOf(codeMember), SpamSettings(), null, null, shown = false)
        assertThat(state.shown).isFalse()
        assertThat(state.rows).isEmpty()
    }

    @Test
    fun turningAGroupOffAsksWithTheCountAndOnlyTheConfirmWritesIt() {
        start(counter = { if (it == SortChange.Group(AllowGroup.CODE, false)) 3 else null })
        vm.setGroupOn(AllowGroup.CODE, false)
        awaitUntil { vm.state.value.groups.confirm?.counting == false }
        assertThat(vm.state.value.groups.confirm).isEqualTo(SwitchConfirm(SortChange.Group(AllowGroup.CODE, false), 3))
        assertThat(stored().codeGroupOn).isTrue()

        vm.confirmGroupSwitch()
        awaitUntil { !stored().codeGroupOn }
        // The members stay stored while the group is off.
        assertThat(members()).contains(codeMember)
        awaitUntil { vm.state.value.groups.rows.first { it.group == AllowGroup.CODE }.on.not() }
    }

    @Test
    fun cancellingTheGroupConfirmKeepsItOn() {
        start()
        vm.setGroupOn(AllowGroup.WROTE, false)
        awaitUntil { vm.state.value.groups.confirm != null }
        vm.dismissGroupSwitch()
        idleMain()
        assertThat(vm.state.value.groups.confirm).isNull()
        assertThat(stored().wroteGroupOn).isTrue()
    }

    @Test
    fun turningAGroupOnNeedsNoConfirm() {
        start(settings = SpamSettings(wroteGroupOn = false))
        vm.setGroupOn(AllowGroup.WROTE, true)
        awaitUntil { stored().wroteGroupOn }
        assertThat(vm.state.value.groups.confirm).isNull()
    }

    @Test
    fun removeTakesTheSenderOutAndUndoPutsItBack() {
        start()
        vm.removeMember(wroteMember)
        awaitUntil { events.any { it is RulesEvent.MembersRemoved } }
        assertThat(members()).doesNotContain(wroteMember)
        val removed = events.filterIsInstance<RulesEvent.MembersRemoved>().single().members
        assertThat(removed).containsExactly(wroteMember)

        vm.restoreMembers(removed)
        awaitUntil { members().contains(wroteMember) }
    }

    @Test
    fun theSnackbarNamesTheRemovedSenderAsTheListShowsIt() {
        // G-12: the same label as the member row: grouped, kept left to right.
        val label = memberLabel(app, wroteMember)
        assertThat(label).isEqualTo(isolateNumbers(displayNumber(app, "+15550001234")))
        assertThat(label.filter(Char::isDigit)).isEqualTo("15550001234")
        val text = membersRemovedText(res, listOf(label))
        assertThat(text).isEqualTo(res.getString(R.string.pt_group_member_removed, label))
        assertThat(memberLabel(app, codeMember)).isEqualTo("AcmeBank")
    }

    @Test
    fun openingAGroupShowsItsMemberList() {
        start()
        vm.openGroup(AllowGroup.WROTE)
        awaitUntil { vm.state.value.groups.openRow != null }
        assertThat(vm.state.value.groups.openRow!!.members).containsExactly(wroteMember)
        vm.closeGroup()
        awaitUntil { vm.state.value.groups.openRow == null }
    }

    private fun groupsState(open: AllowGroup? = null) = AllowGroupsState.of(
        listOf(codeMember, wroteMember), SpamSettings(), open, null, shown = true,
    )

    @Test
    fun theRowsSitAtTheTopOfAnEmptyAllowList() {
        var opened: AllowGroup? = null
        var switched: Pair<AllowGroup, Boolean>? = null
        compose.setContent {
            UMessageTheme {
                RulesScreen(
                    RulesUiState(loading = false, tab = ListKind.ALLOW, groups = groupsState()),
                    SnackbarHostState(),
                    RulesActions(onOpenGroup = { opened = it }, onGroupSwitch = { g, on -> switched = g to on }),
                )
            }
        }
        compose.onNodeWithText(res.getString(R.string.pt_group_code_title)).assertExists()
        compose.onNodeWithText(res.getString(R.string.pt_group_wrote_title)).assertExists()
        compose.onAllNodesWithText(res.getQuantityString(R.plurals.pt_group_count, 1, 1), useUnmergedTree = true)
            .assertCountEquals(2)
        compose.onNodeWithTag(groupRowTag(AllowGroup.WROTE)).performClick()
        assertThat(opened).isEqualTo(AllowGroup.WROTE)
        compose.onNodeWithTag(groupSwitchTag(AllowGroup.CODE)).performClick()
        assertThat(switched).isEqualTo(AllowGroup.CODE to false)
    }

    @Test
    fun theBlockTabHasNoGroupRows() {
        compose.setContent {
            UMessageTheme {
                RulesScreen(
                    RulesUiState(loading = false, tab = ListKind.BLOCK, groups = groupsState()),
                    SnackbarHostState(),
                    RulesActions(),
                )
            }
        }
        compose.onNodeWithText(res.getString(R.string.pt_group_code_title)).assertDoesNotExist()
    }

    @Test
    fun theMemberListSaysWhatTheGroupDoesAndEachMemberCanBeRemoved() {
        var removed: AllowGroupMember? = null
        compose.setContent {
            UMessageTheme {
                RulesScreen(
                    RulesUiState(loading = false, tab = ListKind.ALLOW, groups = groupsState(open = AllowGroup.WROTE)),
                    SnackbarHostState(),
                    RulesActions(onRemoveMember = { removed = it }),
                )
            }
        }
        compose.onNodeWithText(res.getString(R.string.pt_group_wrote_body)).assertExists()
        // Found by the history check: the label shows in the list only (answer 4).
        compose.onNodeWithText(res.getString(R.string.pt_group_member_history)).assertExists()
        compose.onNodeWithText(res.getString(R.string.pt_group_member_remove)).performClick()
        assertThat(removed).isEqualTo(wroteMember)
    }

    @Test
    fun anEmptyGroupSaysSo() {
        val empty = AllowGroupsState.of(emptyList(), SpamSettings(), AllowGroup.CODE, null, shown = true)
        compose.setContent {
            UMessageTheme {
                RulesScreen(RulesUiState(loading = false, groups = empty), SnackbarHostState(), RulesActions())
            }
        }
        compose.onNodeWithText(res.getString(R.string.pt_group_empty)).assertExists()
        compose.onNodeWithText(res.getString(R.string.pt_group_code_body)).assertExists()
    }

    private fun idleMain() = shadowOf(Looper.getMainLooper()).idle()

    private fun awaitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (true) {
            idleMain()
            if (condition()) return
            check(System.currentTimeMillis() < deadline) { "condition not met; state=${vm.state.value} events=$events" }
            Thread.sleep(10)
        }
    }
}
