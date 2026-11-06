package com.glooboo.umessage.ui.onboarding

import org.junit.Assert.assertEquals
import org.junit.Test

class OnboardingPagesTest {
    @Test
    fun `a phone that still needs something gets four pages`() {
        assertEquals(
            listOf(OnboardingPage.WELCOME, OnboardingPage.ALLOW, OnboardingPage.MODE, OnboardingPage.KEEP_RUNNING),
            OnboardingState.pagesFor(allowNeeded = true),
        )
    }

    @Test
    fun `the Allow page is skipped when everything is already granted`() {
        assertEquals(
            listOf(OnboardingPage.WELCOME, OnboardingPage.MODE, OnboardingPage.KEEP_RUNNING),
            OnboardingState.pagesFor(allowNeeded = false),
        )
    }

    @Test
    fun `the flow starts with Welcome and ends with Keep running`() {
        listOf(true, false).forEach {
            val pages = OnboardingState.pagesFor(it)
            assertEquals(OnboardingPage.WELCOME, pages.first())
            assertEquals(OnboardingPage.KEEP_RUNNING, pages.last())
        }
    }
}
