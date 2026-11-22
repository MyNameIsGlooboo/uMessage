package com.glooboo.umessage.ui.spam

import android.app.Application
import android.content.Intent
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.spam.ListKind
import com.glooboo.umessage.testing.resetSharedMessagesDatabase
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * N-4: the real path from the intent to the tab. [RulesActivity] builds [RulesViewModel] through its factory, which
 * reads [RulesActivity.EXTRA_TAB] with `createSavedStateHandle()`; "View rule" (D-6) must land on the block list.
 */
@RunWith(AndroidJUnit4::class)
class RulesActivityTabTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private var scenario: ActivityScenario<RulesActivity>? = null

    @After
    fun tearDown() {
        scenario?.close()
        resetSharedMessagesDatabase()
    }

    /** The tab the screen shows once it has settled, read from the view model the screen itself made. */
    private fun shownTab(intent: Intent, wanted: ListKind): ListKind? {
        scenario = ActivityScenario.launch<RulesActivity>(intent)
        var tab: ListKind? = null
        val read = {
            scenario!!.onActivity { activity ->
                tab = ViewModelProvider(activity, RulesViewModel.Factory)[RulesViewModel::class.java].state.value.tab
            }
            tab
        }
        runCatching { compose.waitUntil(TIMEOUT_MS) { read() == wanted } }
        return read()
    }

    @Test
    fun theBlockIntentOpensTheBlockTab() {
        assertThat(shownTab(RulesActivity.intent(app, ListKind.BLOCK), ListKind.BLOCK)).isEqualTo(ListKind.BLOCK)
    }

    @Test
    fun aPlainIntentOpensTheAllowTab() {
        assertThat(shownTab(RulesActivity.intent(app), ListKind.ALLOW)).isEqualTo(ListKind.ALLOW)
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
