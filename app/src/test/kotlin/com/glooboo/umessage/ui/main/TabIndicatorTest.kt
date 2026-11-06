package com.glooboo.umessage.ui.main

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TabIndicatorTest {

    private fun indicator(
        tab: MainTab,
        selected: MainTab = MainTab.Messages,
        newBlocked: Int = 0,
        failed: Boolean = false,
        paused: Boolean = false,
        archivedNew: Boolean = false,
    ) = tabIndicator(tab, selected, newBlocked, failed, paused, archivedNew)

    @Test
    fun blockedShowsItsCountFromAnotherTab() {
        assertThat(indicator(MainTab.Blocked, newBlocked = 3)).isEqualTo(TabIndicator(count = 3))
    }

    @Test
    fun noBadgeOnTheTabBeingViewed() {
        assertThat(indicator(MainTab.Blocked, selected = MainTab.Blocked, newBlocked = 1).none).isTrue()
        assertThat(indicator(MainTab.Forwarded, selected = MainTab.Forwarded, failed = true).none).isTrue()
        assertThat(indicator(MainTab.Archived, selected = MainTab.Archived, archivedNew = true).none).isTrue()
    }

    /** P2-08: a failed forward not yet seen puts a red dot on Forwarded, before the paused dot. */
    @Test
    fun failedForwardIsAnErrorDot() {
        assertThat(indicator(MainTab.Forwarded, failed = true, paused = true))
            .isEqualTo(TabIndicator(dot = true, error = true))
        assertThat(indicator(MainTab.Forwarded, paused = true)).isEqualTo(TabIndicator(dot = true))
    }

    @Test
    fun archivedDotAndQuietTabs() {
        assertThat(indicator(MainTab.Archived, archivedNew = true)).isEqualTo(TabIndicator(dot = true))
        assertThat(indicator(MainTab.Messages, newBlocked = 5, failed = true).none).isTrue()
    }
}
