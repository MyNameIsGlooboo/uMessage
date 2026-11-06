package com.glooboo.umessage.ui.components

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Smoke test for Compose UI tests under Robolectric: render a composable and query its semantics. */
@RunWith(AndroidJUnit4::class)
class SectionHeaderTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun showsTextAsAHeading() {
        compose.setContent { SectionHeader("Blocked senders") }

        compose.onNodeWithText("Blocked senders")
            .assertIsDisplayed()
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
    }
}
