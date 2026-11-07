package com.glooboo.umessage.ui.main

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MainTabPolicyTest {

    private fun inputs(archived: Int = 0, rules: Int = 0, show: Boolean? = null, available: Boolean = true) =
        MainTabInputs(
            archiveAvailable = available, archivedCount = archived, forwardRuleCount = rules, showForwarded = show,
        )

    private fun tabs(i: MainTabInputs, selected: MainTab = MainTab.Messages, opened: Boolean = false) =
        MainTabPolicy.visibleTabs(i, selected, opened)

    @Test
    fun freshConfigWithAnEmptyArchive_showsArchivedFromTheFirstLaunch() {
        assertThat(tabs(inputs())).containsExactly(MainTab.Messages, MainTab.Blocked, MainTab.Archived).inOrder()
        assertThat(tabs(inputs(), selected = MainTab.Messages)).contains(MainTab.Archived)
    }

    @Test
    fun archivedStaysWhenSomethingIsArchived() {
        assertThat(tabs(inputs(archived = 1)))
            .containsExactly(MainTab.Messages, MainTab.Blocked, MainTab.Archived).inOrder()
    }

    @Test
    fun archivedStaysAfterTheLastChatIsUnarchived() {
        assertThat(tabs(inputs(archived = 0), selected = MainTab.Archived)).contains(MainTab.Archived)
        assertThat(tabs(inputs(archived = 0), selected = MainTab.Messages)).contains(MainTab.Archived)
    }

    @Test
    fun archivedNeverShowsWhereArchivingIsNotSupported() {
        assertThat(tabs(inputs(archived = 3, available = false), selected = MainTab.Archived))
            .doesNotContain(MainTab.Archived)
    }

    @Test
    fun forwardedNeedsARuleAndTheUsersYes() {
        assertThat(tabs(inputs(rules = 1, show = null))).doesNotContain(MainTab.Forwarded)
        assertThat(tabs(inputs(rules = 1, show = false))).doesNotContain(MainTab.Forwarded)
        assertThat(tabs(inputs(rules = 0, show = true))).doesNotContain(MainTab.Forwarded)
        assertThat(tabs(inputs(rules = 2, show = true))).contains(MainTab.Forwarded)
    }

    @Test
    fun forwardedStaysAfterTheLastRuleWhileFailedForwardsAreListed() {
        val failedLeft = inputs(rules = 0, show = true).copy(failedForwards = 1)
        assertThat(tabs(failedLeft)).contains(MainTab.Forwarded)
        assertThat(tabs(failedLeft.copy(failedForwards = 0))).doesNotContain(MainTab.Forwarded)
        assertThat(tabs(failedLeft.copy(showForwarded = false))).doesNotContain(MainTab.Forwarded)
    }

    @Test
    fun orderIsMessagesBlockedForwardedArchived() {
        assertThat(tabs(inputs(archived = 2, rules = 1, show = true)))
            .containsExactly(MainTab.Messages, MainTab.Blocked, MainTab.Forwarded, MainTab.Archived).inOrder()
    }

    @Test
    fun forwardedOpenedOnPurposeStaysOnlyWhileSelected() {
        val i = inputs(rules = 0, show = false)
        assertThat(tabs(i, MainTab.Forwarded, opened = true)).contains(MainTab.Forwarded)
        assertThat(tabs(i, MainTab.Messages, opened = true)).doesNotContain(MainTab.Forwarded)
        assertThat(tabs(i, MainTab.Forwarded, opened = false)).doesNotContain(MainTab.Forwarded)
    }

    @Test
    fun aSelectedTabThatIsGoneFallsBackToMessages() {
        val list = tabs(inputs())
        assertThat(MainTabPolicy.effectiveTab(list, MainTab.Forwarded)).isEqualTo(MainTab.Messages)
        assertThat(MainTabPolicy.effectiveTab(list, MainTab.Archived)).isEqualTo(MainTab.Archived)
        assertThat(MainTabPolicy.effectiveTab(list, MainTab.Blocked)).isEqualTo(MainTab.Blocked)
    }
}
