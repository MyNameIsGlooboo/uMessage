package com.glooboo.umessage.ui.spam

import com.glooboo.umessage.R
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** L-4: the line under "Auto-delete old spam" says "Off until you choose a number of days" only while it is off. */
class PurgeSummaryTest {

    @Test
    fun theOffLineShowsOnlyWhileNoDaysAreChosen() {
        assertThat(purgeSummary(0)).isEqualTo(R.string.spam_purge_body)
        assertThat(purgeSummary(30)).isNull()
        assertThat(purgeSummary(7)).isNull()
    }
}
