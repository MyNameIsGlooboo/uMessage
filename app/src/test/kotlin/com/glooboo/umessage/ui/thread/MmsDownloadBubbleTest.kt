package com.glooboo.umessage.ui.thread

import android.app.Application
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.R
import com.glooboo.umessage.messaging.MmsDownloadState
import com.glooboo.umessage.messaging.MmsDownloadTracker
import com.glooboo.umessage.ui.theme.UMessageTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Q40: the "Picture message not downloaded" bubble. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h800dp")
class MmsDownloadBubbleTest {
    @get:Rule
    val compose = createComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private fun str(id: Int) = app.getString(id)

    private fun show(state: MmsDownloadState, onDownload: () -> Unit = {}) = compose.setContent {
        UMessageTheme { MmsDownloadBubble(state, onDownload) }
    }

    @Test
    fun idleBubbleOffersTheDownloadAndTapStartsIt() {
        var taps = 0
        show(MmsDownloadState.NotDownloaded) { taps++ }
        compose.onNodeWithText(str(R.string.mms_download_failed_title)).assertExists()
        compose.onNodeWithText(str(R.string.mms_tap_to_download)).assertIsEnabled().performClick()
        assertThat(taps).isEqualTo(1)
    }

    @Test
    fun whileDownloadingTheButtonIsDisabled() {
        show(MmsDownloadState.Downloading)
        compose.onNodeWithText(str(R.string.mms_downloading)).assertIsNotEnabled()
    }

    @Test
    fun aFailedDownloadCanBeTriedAgain() {
        var taps = 0
        show(MmsDownloadState.Failed) { taps++ }
        compose.onNodeWithText(str(R.string.mms_download_again)).assertIsEnabled().performClick()
        assertThat(taps).isEqualTo(1)
    }

    @Test
    fun anExpiredMessageHasNoButton() {
        show(MmsDownloadState.Expired)
        compose.onNodeWithText(str(R.string.mms_expired)).assertExists()
        compose.onAllNodesWithText(str(R.string.mms_tap_to_download)).assertCountEquals(0)
    }

    @Test
    fun theRowFollowsTheTrackerAndReportsTheMessageId() {
        val tracker = MmsDownloadTracker()
        var tapped = -1L
        compose.setContent {
            UMessageTheme { MmsDownloadRow(ThreadRow.MmsDownload(9, 0, "k"), { tapped = it }, tracker) }
        }
        compose.onNodeWithText(str(R.string.mms_tap_to_download)).performClick()
        assertThat(tapped).isEqualTo(9L)
        tracker.begin(9, System.currentTimeMillis())
        compose.waitForIdle()
        compose.onNodeWithText(str(R.string.mms_downloading)).assertIsNotEnabled()
        tracker.failAllRunning()
        compose.waitForIdle()
        compose.onNodeWithText(str(R.string.mms_download_again)).assertIsEnabled()
    }
}
