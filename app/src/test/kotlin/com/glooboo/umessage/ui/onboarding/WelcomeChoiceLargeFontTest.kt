package com.glooboo.umessage.ui.onboarding

import android.app.Application
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.R
import com.glooboo.umessage.testing.ProviderTruthFlagRule
import com.glooboo.umessage.ui.theme.UMessageTheme
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** U12, G-8: the Welcome filter choice in Persian at twice the font size: every key text is whole, the choice works. */
@RunWith(AndroidJUnit4::class)
// Native graphics, as in OnboardingBottomBarTest: the default legacy mode measures text with made-up widths, so its
// layouts report overflow that a phone never shows and say nothing about a text that is really cut.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "fa-w360dp-h800dp")
class WelcomeChoiceLargeFontTest {

    @get:Rule
    val flag = ProviderTruthFlagRule(enabled = true)

    @get:Rule
    val compose = createComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val res = app.resources

    @Test
    fun inPersianAtTwiceTheFontSizeTheChoiceFitsAndWorks() {
        var chosen: Boolean? = null
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                UMessageTheme(darkTheme = false, dynamicColor = false, syncWindow = false) {
                    var on by remember { mutableStateOf(true) }
                    WelcomePage(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                        reduceMotion = true,
                        filterOn = on,
                        onFilter = {
                            on = it
                            chosen = it
                        },
                    )
                }
            }
        }
        val keys = listOf(
            R.string.spam_settings_unknown_title, R.string.pt_onb_filter_on, R.string.pt_onb_filter_on_desc,
            R.string.pt_onb_filter_off, R.string.pt_onb_filter_off_desc, R.string.pt_onb_filter_hint,
            R.string.settings_nav_recommended,
        )
        keys.forEach { key ->
            val text = res.getString(key)
            assertThat(text).isNotEqualTo(englishOf(key))
            val node = compose.onNodeWithText(text, useUnmergedTree = true).performScrollTo()
            node.assertIsDisplayed()
            assertWithMessage(res.getResourceEntryName(key)).that(overflows(node)).isFalse()
        }
        compose.onNodeWithTag(FILTER_OFF_TAG).performScrollTo().performClick()
        assertThat(chosen).isFalse()
        compose.onNodeWithTag(FILTER_OFF_TAG).assertIsSelected()
    }

    /** A text cut off at the end, or lines lost, shows as a visual overflow of its layout. */
    private fun overflows(node: SemanticsNodeInteraction): Boolean {
        val results = mutableListOf<TextLayoutResult>()
        node.fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        return results.any { it.hasVisualOverflow }
    }

    private fun englishOf(key: Int): String {
        val config = android.content.res.Configuration(res.configuration).apply { setLocale(java.util.Locale.ENGLISH) }
        return app.createConfigurationContext(config).resources.getString(key)
    }
}
