package com.glooboo.umessage.ui.thread

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.R
import com.glooboo.umessage.ui.theme.UMessageTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** I07: warnings in the link sheet and the setting that stops risky links from opening. */
@RunWith(AndroidJUnit4::class)
class LinkSheetTest {

    @get:Rule
    val compose = createComposeRule()

    private fun str(id: Int) = ApplicationProvider.getApplicationContext<android.content.Context>().getString(id)

    private fun show(uri: String, lockRisky: Boolean = false) = compose.setContent {
        UMessageTheme {
            LinkSheet(
                uri, trusted = false, onOpen = {}, onCopy = {}, onShare = {}, onDismiss = {},
                lockRisky = lockRisky,
            )
        }
    }

    @Test
    fun shortenedLinkShowsItsWarningAndStillOpens() {
        show("https://bit.ly/abc")
        compose.onNodeWithText(str(R.string.i07_shortener)).assertExists()
        compose.onNodeWithText(str(R.string.thread_link_confirm_open)).assertExists()
    }

    @Test
    fun ordinaryLinkShowsNoWarning() {
        show("https://example.com/page")
        compose.onNodeWithText(str(R.string.i07_shortener)).assertDoesNotExist()
        compose.onNodeWithText(str(R.string.i07_locked)).assertDoesNotExist()
    }

    @Test
    fun lockedRiskyLinkCannotBeOpenedButExplainsWhy() {
        show("https://bit.ly/abc", lockRisky = true)
        compose.onNodeWithText(str(R.string.i07_locked)).assertExists()
        compose.onNodeWithText(str(R.string.thread_link_confirm_open)).assertDoesNotExist()
        compose.onNodeWithText(str(R.string.thread_r2_link_copy)).assertExists()
    }

    @Test
    fun theLockLeavesPlainHttpAndOrdinaryLinksOpenable() {
        show("http://example.com/x", lockRisky = true)
        compose.onNodeWithText(str(R.string.i07_http_only)).assertExists()
        compose.onNodeWithText(str(R.string.thread_link_confirm_open)).assertExists()
    }
}
