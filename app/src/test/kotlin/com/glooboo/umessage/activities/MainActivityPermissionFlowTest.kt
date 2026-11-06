package com.glooboo.umessage.activities

import android.Manifest
import android.app.Application
import android.app.role.RoleManager
import android.content.pm.PackageManager
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.R
import com.glooboo.umessage.extensions.config
import com.glooboo.umessage.testing.resetSharedMessagesDatabase
import com.glooboo.umessage.ui.onboarding.OnboardingState
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowRoleManager

/** The default-SMS role and SMS permission steps of the conversation list (MC-04, MC-05, CM-05). */
@RunWith(AndroidJUnit4::class)
class MainActivityPermissionFlowTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val roles get() = shadowOf(app.getSystemService(RoleManager::class.java)) as ShadowRoleManager
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun setUp() {
        OnboardingState.setCompleted(app)
        app.config.wasDbCleared = true
        roles.addAvailableRole(RoleManager.ROLE_SMS)
    }

    @After
    fun tearDown() {
        scenario?.close()
        if (app.getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_SMS)) {
            roles.removeHeldRole(RoleManager.ROLE_SMS)
        }
        resetSharedMessagesDatabase()
    }

    private fun grantSmsPermissions() = shadowOf(app).grantPermissions(
        Manifest.permission.READ_SMS,
        Manifest.permission.SEND_SMS,
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.POST_NOTIFICATIONS,
    )

    private fun roleRequests(): Int {
        var count = 0
        while (true) {
            val next = shadowOf(app).nextStartedActivity ?: return count
            if (next.action == "android.app.role.action.REQUEST_ROLE") count++
        }
    }

    @Test
    fun `rotating while the default-SMS dialog is open does not open a second one`() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        assertThat(roleRequests()).isEqualTo(1)

        scenario!!.recreate()
        assertThat(roleRequests()).isEqualTo(0)
    }

    @Test
    fun `denying SMS permission explains it instead of closing the app`() {
        roles.addHeldRole(RoleManager.ROLE_SMS)
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario!!.onActivity { activity ->
            assertThat(shadowOf(activity).lastRequestedPermission.requestedPermissions)
                .asList().contains(Manifest.permission.READ_SMS)
            activity.onRequestPermissionsResult(
                GENERIC_PERM_HANDLER,
                arrayOf(Manifest.permission.READ_SMS),
                intArrayOf(PackageManager.PERMISSION_DENIED),
            )
        }
        compose.onNodeWithText(app.getString(R.string.main_sms_permission_title)).assertExists()
        compose.onNodeWithText(app.getString(R.string.main_sms_permission_settings)).assertExists()
        scenario!!.onActivity { assertThat(it.isFinishing).isFalse() }

        // Granted in Android settings: coming back opens the list.
        grantSmsPermissions()
        scenario!!.moveToState(Lifecycle.State.STARTED)
        scenario!!.moveToState(Lifecycle.State.RESUMED)
        compose.waitForIdle()
        assertThat(compose.onAllNodesWithText(app.getString(R.string.main_sms_permission_title)).fetchSemanticsNodes())
            .isEmpty()
    }

    @Test
    fun `losing the default-SMS role while the list is open shows a warning with a fix`() {
        roles.addHeldRole(RoleManager.ROLE_SMS)
        grantSmsPermissions()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        val banner = app.getString(R.string.main_not_default_banner)
        compose.waitForIdle()
        assertThat(compose.onAllNodesWithText(banner).fetchSemanticsNodes()).isEmpty()

        roles.removeHeldRole(RoleManager.ROLE_SMS)
        scenario!!.moveToState(Lifecycle.State.STARTED)
        scenario!!.moveToState(Lifecycle.State.RESUMED)
        compose.onNodeWithText(banner).assertExists()
        compose.onNodeWithText(app.getString(R.string.main_not_default_fix)).assertExists()
    }

    private companion object {
        /** BaseSimpleActivity's request code for handlePermission. */
        const val GENERIC_PERM_HANDLER = 100
    }
}
