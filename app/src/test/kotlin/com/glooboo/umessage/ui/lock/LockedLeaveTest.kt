package com.glooboo.umessage.ui.lock

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.ui.platform.ComposeView
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.fossify.commons.extensions.baseConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric

/** K2-6: when a screen closes its own windows on leaving, and which views of its content count as dialog hosts. */
@RunWith(AndroidJUnit4::class)
class LockedLeaveTest {

    private val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()

    @Test
    fun onlyWithTheAppLockOnAndNotForAPickerOfItsOwn() {
        activity.baseConfig.isAppPasswordProtectionOn = false
        assertFalse(LockedLeave.closesWindows(activity, ownTrip = false))

        activity.baseConfig.isAppPasswordProtectionOn = true
        assertTrue(LockedLeave.closesWindows(activity, ownTrip = false))
        assertFalse(LockedLeave.closesWindows(activity, ownTrip = true))
    }

    @Test
    fun theHostedDialogsGoAndTheScreenStays() {
        val content = activity.findViewById<ViewGroup>(android.R.id.content)
        val screen = ComposeView(activity)
        content.addView(screen, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0))
        content.addView(ComposeView(activity), FrameLayout.LayoutParams(0, 0))
        content.addView(ComposeView(activity), FrameLayout.LayoutParams(0, 0))

        LockedLeave.closeHostedDialogs(activity)

        assertEquals(1, content.childCount)
        assertSame(screen, content.getChildAt(0))
    }
}
