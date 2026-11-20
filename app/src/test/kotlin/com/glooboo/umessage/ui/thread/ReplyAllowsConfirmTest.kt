package com.glooboo.umessage.ui.thread

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.R
import com.glooboo.umessage.ui.theme.UMessageTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Provider truth, step C (answer 20, D4 B2): a suggested reply in a Spam chat asks first; Cancel sends nothing. */
@RunWith(AndroidJUnit4::class)
class ReplyAllowsConfirmTest {

    @get:Rule
    val compose = createComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private var sent = 0
    private var cancelled = 0

    private fun show() = compose.setContent {
        UMessageTheme(darkTheme = false, dynamicColor = false, syncWindow = false) {
            ReplyAllowsConfirm(onSend = { sent++ }, onDismiss = { cancelled++ })
        }
    }

    @Test
    fun sendSends() {
        show()
        compose.onNodeWithText(app.getString(R.string.pt_reply_allows_confirm)).assertIsDisplayed()
        compose.onNodeWithText(app.getString(R.string.thread_send)).performClick()
        assertThat(sent).isEqualTo(1)
        assertThat(cancelled).isEqualTo(0)
    }

    @Test
    fun cancelSendsNothing() {
        show()
        compose.onNodeWithText(app.getString(R.string.spam_cancel)).performClick()
        assertThat(sent).isEqualTo(0)
        assertThat(cancelled).isEqualTo(1)
    }

    @Test
    fun aSpamChatOfAnUnknownSenderAsksFirst() {
        val state = ChatLineState()
        state.update(ChatLine.UNKNOWN, filterOn = true)
        assertThat(state.unknownInSpam).isTrue()
        state.update(ChatLine.BLOCKED, filterOn = true)
        assertThat(state.unknownInSpam).isFalse()
    }
}
