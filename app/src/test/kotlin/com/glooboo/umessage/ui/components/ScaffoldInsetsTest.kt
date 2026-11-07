package com.glooboo.umessage.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** UI-landscape: the scaffold applies the side insets itself and hands the screen only top and bottom. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w800dp-h360dp-land")
class ScaffoldInsetsTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `content is moved in from a cutout on the left and a bar on the right`() {
        var received: PaddingValues? = null
        var density = 1f
        compose.setContent {
            density = LocalDensity.current.density
            HorizontalInsetContent(PaddingValues(start = 48.dp, top = 10.dp, end = 24.dp, bottom = 5.dp)) {
                received = it
                // An empty box has empty bounds, which the test reports as zero; give it some height.
                Box(Modifier.fillMaxWidth().height(10.dp).testTag("page"))
            }
        }
        val bounds = compose.onNodeWithTag("page").fetchSemanticsNode().boundsInRoot
        assertEquals(48f * density, bounds.left, 1f)
        assertEquals(0.dp, received!!.calculateLeftPadding(LayoutDirection.Ltr))
        assertEquals(0.dp, received!!.calculateRightPadding(LayoutDirection.Ltr))
        assertEquals(10.dp, received!!.calculateTopPadding())
        assertEquals(5.dp, received!!.calculateBottomPadding())
    }
}
