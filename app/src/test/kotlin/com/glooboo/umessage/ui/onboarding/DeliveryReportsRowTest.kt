package com.glooboo.umessage.ui.onboarding

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.helpers.Config
import com.glooboo.umessage.ui.theme.UMessageTheme
import org.fossify.commons.helpers.PREFS_KEY
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config as RoboConfig

/** The delivery reports switch on the welcome flow writes the same preference the Settings row reads. */
@RunWith(AndroidJUnit4::class)
@RoboConfig(qualifiers = "w411dp-h891dp")
class DeliveryReportsRowTest {
    @get:Rule
    val compose = createComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun `the switch starts on for a new install and toggling it writes the preference`() {
        // A real fresh install: wipe the app's preferences, then let Config.init run its first-run step.
        app.getSharedPreferences(PREFS_KEY, Context.MODE_PRIVATE).edit().clear().commit()
        Config.newInstance(app)
        val vm = OnboardingViewModel(app)
        compose.setContent {
            UMessageTheme(darkTheme = false, dynamicColor = false, syncWindow = false) {
                var on by remember { mutableStateOf(vm.deliveryReports.value) }
                DeliveryReportsRow(checked = on, onCheckedChange = { on = it; vm.setDeliveryReports(it) })
            }
        }
        compose.onNodeWithText("Get delivery reports").assertIsDisplayed()
        assertTrue(vm.deliveryReports.value)
        // A later launch is not a first run and must not flip the stored value.
        assertTrue(Config.newInstance(app).enableDeliveryReports)

        compose.onNodeWithText("Get delivery reports").performClick()

        assertFalse(vm.deliveryReports.value)
        assertFalse(Config.newInstance(app).enableDeliveryReports)
    }
}
