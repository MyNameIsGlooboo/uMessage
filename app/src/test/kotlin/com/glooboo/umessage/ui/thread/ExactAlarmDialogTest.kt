package com.glooboo.umessage.ui.thread

import androidx.activity.ComponentActivity
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.ui.lock.LockedLeave
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.shadows.ShadowLooper

/** K2-6: the exact-alarm offer closed by the chat (not answered by the user) says so, so it is made again next time. */
@RunWith(AndroidJUnit4::class)
class ExactAlarmDialogTest {

    @Test
    fun closedByTheChatItCountsAsNotAnswered() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        var unanswered = 0
        var answered = 0
        activity.showExactAlarmDialog(
            onOpenSettings = { answered++ },
            onScheduleAnyway = { answered++ },
            onClosedUnanswered = { unanswered++ },
        )
        ShadowLooper.idleMainLooper()

        LockedLeave.closeHostedDialogs(activity) // the chat is left under the app lock

        assertEquals(1, unanswered)
        assertEquals(0, answered)
    }
}
