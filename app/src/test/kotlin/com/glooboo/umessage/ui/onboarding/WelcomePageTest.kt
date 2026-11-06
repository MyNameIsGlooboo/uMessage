package com.glooboo.umessage.ui.onboarding

import android.content.Context
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.R
import com.glooboo.umessage.ui.theme.UMessageTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The Welcome step lists the app's features under the owner-approved intro line. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp")
class WelcomePageTest {
    @get:Rule
    val compose = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun setUp() = compose.setContent {
        UMessageTheme(darkTheme = false, dynamicColor = false, syncWindow = false) {
            WelcomePage(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), reduceMotion = true)
        }
    }

    @Test
    fun `the welcome step shows the title, the intro line and all eight features in order`() {
        setUp()
        compose.onNodeWithText("More features").performScrollTo().performClick()
        compose.onNodeWithText("Welcome to uMessage").assertIsDisplayed()
        compose.onNodeWithText("Better messaging, less spam.").assertIsDisplayed()
        val titles = listOf(
            "Spam stays out",
            "One-tap codes",
            "Copy just what you need",
            "Your own rules",
            "Auto-forwarding",
            "Schedule and dual SIM",
            "App lock and backup",
            "Private by design",
        )
        assertEquals(titles, welcomeFeatures().map { context.getString(it.titleRes) })
        titles.forEach { compose.onNodeWithText(it).performScrollTo().assertIsDisplayed() }
    }

    @Test
    fun `at first only three features show, and More features reveals the rest`() {
        setUp()
        listOf("Spam stays out", "One-tap codes", "Your own rules").forEach {
            compose.onNodeWithText(it).assertIsDisplayed()
        }
        listOf("Auto-forwarding", "Schedule and dual SIM", "Private by design").forEach {
            compose.onNodeWithText(it).assertDoesNotExist()
        }
        compose.onNodeWithText("More features").performScrollTo().performClick()
        compose.onNodeWithText("Auto-forwarding").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Fewer features").performScrollTo().performClick()
        compose.onNodeWithText("Auto-forwarding").assertDoesNotExist()
    }

    @Test
    fun `the title is a heading`() {
        setUp()
        compose.onNodeWithText("Welcome to uMessage")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
    }

    @Test
    fun `each feature row is read as one item, title and detail together`() {
        setUp()
        compose.onNodeWithText("More features").performScrollTo().performClick()
        welcomeFeatures().forEach { feature ->
            val title = context.getString(feature.titleRes)
            val detail = context.getString(feature.detailRes)
            compose.onNodeWithText(detail).performScrollTo().assert(hasText(title))
        }
    }

    @Test
    fun `every feature has its own icon and text`() {
        val features = welcomeFeatures()
        assertEquals(8, features.size)
        assertEquals(8, features.map { it.icon }.distinct().size)
        assertEquals(16, features.flatMap { listOf(it.titleRes, it.detailRes) }.distinct().size)
    }

    @Test
    fun `the old welcome body and points are gone`() {
        val old = listOf("onb_welcome_body", "onb_welcome_point_1", "onb_welcome_point_2", "onb_welcome_point_3")
        old.forEach { name ->
            assertTrue(name, runCatching { R.string::class.java.getField(name) }.isFailure)
        }
    }

    @Test
    @Config(qualifiers = "fa-w411dp-h891dp")
    fun `in Persian the intro is translated and the app name stays Latin`() {
        setUp()
        compose.onNodeWithText("به uMessage خوش آمدید").assertIsDisplayed()
        compose.onNodeWithText("پیام‌رسانی بهتر، هرزنامهٔ کمتر.").assertIsDisplayed()
        welcomeFeatures().forEach { feature ->
            val title = context.getString(feature.titleRes)
            assertTrue(title, title.any { it in '؀'..'ۿ' })
        }
    }
}
