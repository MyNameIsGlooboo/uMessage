package com.glooboo.umessage.ui.onboarding

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** F8: the page list must not change under a saved page position after a rotation or a process restart. */
@RunWith(AndroidJUnit4::class)
class OnboardingPagesRestoreTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `the list stays the same after state restoration even when everything is granted by then`() {
        val allowNeededNow = mutableStateOf(true)
        var pages: List<OnboardingPage> = emptyList()
        val tester = StateRestorationTester(compose)
        tester.setContent { pages = rememberOnboardingPages(allowNeededNow.value) }
        compose.waitForIdle()
        assertEquals(OnboardingState.pagesFor(true), pages)

        // The user granted everything on the Allow page, then the phone was rotated.
        allowNeededNow.value = false
        tester.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        assertEquals(OnboardingState.pagesFor(true), pages)
    }

    @Test
    fun `a flow that opened with nothing left to allow has no Allow page after restoration either`() {
        val allowNeededNow = mutableStateOf(false)
        var pages: List<OnboardingPage> = emptyList()
        val tester = StateRestorationTester(compose)
        tester.setContent { pages = rememberOnboardingPages(allowNeededNow.value) }
        compose.waitForIdle()
        allowNeededNow.value = true
        tester.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        assertEquals(OnboardingState.pagesFor(false), pages)
    }
}
