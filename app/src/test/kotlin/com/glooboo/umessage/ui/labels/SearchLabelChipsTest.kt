package com.glooboo.umessage.ui.labels

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.ui.main.InboxFilter
import com.glooboo.umessage.ui.main.MainSearchState
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/** The search page's label chips: which label counts as selected and what a tap or a delete does to the filter. */
@RunWith(AndroidJUnit4::class)
class SearchLabelChipsTest {

    @Test
    fun noLabelIsSelectedWhileAnotherFilterIsOn() {
        val search = MainSearchState()
        assertThat(search.selectedLabelId).isNull()
        search.filter = InboxFilter.Sim
        search.labelId = 5
        assertThat(search.selectedLabelId).isNull()
    }

    @Test
    fun aTapSelectsTheLabelAndASecondTapClearsIt() {
        val search = MainSearchState()
        search.toggleLabel(5)
        assertThat(search.selectedLabelId).isEqualTo(5L)
        search.toggleLabel(5)
        assertThat(search.filter).isEqualTo(InboxFilter.All)
        assertThat(search.selectedLabelId).isNull()
    }

    @Test
    fun aTapOnAnotherLabelMovesTheSelection() {
        val search = MainSearchState()
        search.toggleLabel(5)
        search.toggleLabel(6)
        assertThat(search.selectedLabelId).isEqualTo(6L)
    }

    @Test
    fun deletingTheSelectedLabelClearsTheFilter() {
        val search = MainSearchState()
        search.toggleLabel(5)
        search.forgetLabel(5)
        assertThat(search.filter).isEqualTo(InboxFilter.All)
    }

    @Test
    fun deletingAnotherLabelKeepsTheFilter() {
        val search = MainSearchState()
        search.toggleLabel(5)
        search.forgetLabel(6)
        assertThat(search.selectedLabelId).isEqualTo(5L)
    }
}
