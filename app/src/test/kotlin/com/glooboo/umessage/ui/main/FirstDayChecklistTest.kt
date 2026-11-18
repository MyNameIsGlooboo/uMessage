package com.glooboo.umessage.ui.main

import android.app.Application
import android.app.NotificationManager
import android.app.role.RoleManager
import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.helpers.W3cPrefs
import com.glooboo.umessage.ui.theme.UMessageTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowRoleManager

/** The first-day card has a close button only once no required step is left that can still be done on this phone. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp")
class FirstDayChecklistTest {

    @get:Rule val compose = createComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val roles get() = shadowOf(app.getSystemService(RoleManager::class.java)) as ShadowRoleManager

    private fun show() = compose.setContent {
        UMessageTheme(darkTheme = false, dynamicColor = false, syncWindow = false) {
            FirstDayChecklist(forwardRuleCount = 0, onOpenBlockedTab = {}, onOpenForwarding = {})
        }
    }

    private fun holdTheRole() {
        roles.addAvailableRole(RoleManager.ROLE_SMS)
        roles.addHeldRole(RoleManager.ROLE_SMS)
    }

    @Test fun noCloseButtonWhileARequiredStepIsOpen() {
        holdTheRole()
        // Notifications are on and the Blocked tab was never opened: that required step is still open.
        show()

        compose.onNodeWithContentDescription("Close checklist").assertDoesNotExist()
    }

    @Test fun noCloseButtonWhenNotificationsAreOff() {
        holdTheRole()
        W3cPrefs.get(app).blockedTabVisited = true
        shadowOf(app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .setNotificationsEnabled(false)
        show()

        compose.onNodeWithContentDescription("Close checklist").assertDoesNotExist()
    }

    @Test fun closeButtonWhenEveryRequiredStepIsDone() {
        holdTheRole()
        W3cPrefs.get(app).blockedTabVisited = true
        show()

        compose.onNodeWithContentDescription("Close checklist").assertIsDisplayed()
    }

    @Test fun closeButtonWhenThePhoneHasNoSmsRoleToGive() {
        // No role is offered here (the default in Robolectric), so that step can never be done; the rest is done.
        W3cPrefs.get(app).blockedTabVisited = true
        show()

        compose.onNodeWithContentDescription("Close checklist").assertIsDisplayed()
    }

    @Test fun noCloseButtonWhenTheRoleCanBeAskedForButIsNotHeld() {
        W3cPrefs.get(app).blockedTabVisited = true
        roles.addAvailableRole(RoleManager.ROLE_SMS)
        show()

        compose.onNodeWithContentDescription("Close checklist").assertDoesNotExist()
    }

    @Test fun closeButtonAfterSevenDaysEvenWithNotificationsOff() {
        holdTheRole()
        W3cPrefs.get(app).blockedTabVisited = true
        W3cPrefs.get(app).checklistFirstShown = System.currentTimeMillis() - 8L * 24 * 60 * 60 * 1000
        shadowOf(app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .setNotificationsEnabled(false)
        show()

        compose.onNodeWithContentDescription("Close checklist").assertIsDisplayed()
    }
}
