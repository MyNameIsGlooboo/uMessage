package com.glooboo.umessage.ui.spam

import android.app.Application
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.R
import com.glooboo.umessage.spam.BlockedMessage
import com.glooboo.umessage.spam.DecisionReason
import com.glooboo.umessage.ui.theme.UMessageTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Swiping a Blocked tab row shows the action's label next to the icon, as the Messages and Archived rows do. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h2000dp")
class BlockedSenderRowSwipeTest {

    @get:Rule
    val compose = createComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun swipingTowardsTheEndRevealsTheMoveToInboxLabel() {
        val now = System.currentTimeMillis()
        val group = SenderGroup(
            "+447121234567",
            listOf(BlockedMessage(1, "+447121234567", "hello", 1, false, now, DecisionReason.UNKNOWN_SENDER)),
        )
        compose.setContent {
            UMessageTheme {
                BlockedSenderRow(
                    group = group,
                    sims = emptyList(),
                    rules = emptyMap(),
                    selected = false,
                    selecting = false,
                    actions = BlockedActions(),
                    reduceMotion = true,
                )
            }
        }
        compose.onRoot().performTouchInput {
            down(center)
            moveBy(Offset(width / 4f, 0f))
        }
        compose.waitForIdle()
        compose.onNodeWithText(app.getString(R.string.blocked_action_move_inbox), useUnmergedTree = true)
            .assertIsDisplayed()
    }
}
