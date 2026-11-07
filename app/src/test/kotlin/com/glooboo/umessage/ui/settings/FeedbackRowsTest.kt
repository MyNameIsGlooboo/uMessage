package com.glooboo.umessage.ui.settings

import android.content.Intent
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.ui.theme.UMessageTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Settings > About: the feedback row in every build, the rate row only in the Google Play build. */
@RunWith(AndroidJUnit4::class)
class FeedbackRowsTest {
    @get:Rule
    val compose = createComposeRule()

    private var rated = 0
    private var feedback = 0

    private fun showAbout(rate: Boolean) {
        compose.setContent {
            UMessageTheme(darkTheme = false, dynamicColor = false, syncWindow = false) {
                SettingsScreen(
                    SettingsUiState(showRateRow = rate),
                    SettingsActions(onRate = { rated++ }, onSendFeedback = { feedback++ }),
                    SettingsPage.About,
                )
            }
        }
    }

    @Test
    fun `the rate row is absent without the Google Play flag`() {
        showAbout(rate = false)
        compose.onNodeWithText("Rate uMessage").assertDoesNotExist()
        compose.onNodeWithText("Send feedback").assertExists()
    }

    @Test
    fun `the rate row is present with the Google Play flag and one tap runs it`() {
        showAbout(rate = true)
        compose.onNodeWithText("Rate uMessage").performScrollTo().performClick()
        assertThat(rated).isEqualTo(1)
    }

    @Test
    fun `one tap on the feedback row runs it`() {
        showAbout(rate = false)
        compose.onNodeWithText("Send feedback").performScrollTo().performClick()
        assertThat(feedback).isEqualTo(1)
    }

    @Test
    fun `the feedback email has the exact subject, the contact address and no body`() {
        val intent = FeedbackLinks.feedbackIntent("0.4.2")
        assertThat(intent.action).isEqualTo(Intent.ACTION_SENDTO)
        assertThat(intent.data.toString()).isEqualTo("mailto:Glooboo@proton.me")
        assertThat(intent.getStringExtra(Intent.EXTRA_SUBJECT)).isEqualTo("uMessage feedback (0.4.2)")
        assertThat(intent.hasExtra(Intent.EXTRA_TEXT)).isFalse()
    }

    @Test
    fun `the store links use the package name`() {
        assertThat(FeedbackLinks.marketIntent("a.b").data.toString()).isEqualTo("market://details?id=a.b")
        assertThat(FeedbackLinks.storeWebIntent("a.b").data.toString())
            .isEqualTo("https://play.google.com/store/apps/details?id=a.b")
        assertThat(FeedbackLinks.issuesIntent().data.toString())
            .isEqualTo("https://github.com/MyNameIsGlooboo/uMessage/issues")
    }

    @Test
    fun `the fallback stops at the first attempt that works`() {
        val calls = mutableListOf<Int>()
        val result = FeedbackLinks.firstThatWorks(
            listOf({ calls += 1; false }, { calls += 2; true }, { calls += 3; true }),
        )
        assertThat(result).isTrue()
        assertThat(calls).containsExactly(1, 2).inOrder()
        assertThat(FeedbackLinks.firstThatWorks(listOf({ false }, { false }))).isFalse()
    }
}
