package com.glooboo.umessage.ui.onboarding

import android.app.Application
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.R
import com.glooboo.umessage.ui.theme.UMessageTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * R2-2, R2-6: the choice card's name and tag. Side by side when both fit, the tag under the name when not, placed
 * from the start edge in both directions, and no crash on an unbounded width or an intrinsic-width parent.
 */
@RunWith(AndroidJUnit4::class)
// Native graphics, as in WelcomeChoiceLargeFontTest: real text widths decide the branch.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w800dp-h800dp")
class ChoiceTitleTest {

    @get:Rule
    val compose = createComposeRule()

    private val res = ApplicationProvider.getApplicationContext<Application>().resources
    private val nameRes = R.string.pt_onb_filter_off

    private fun show(
        direction: LayoutDirection = LayoutDirection.Ltr,
        frame: @Composable (title: @Composable () -> Unit) -> Unit,
    ) {
        compose.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides direction) {
                UMessageTheme(darkTheme = false, dynamicColor = false, syncWindow = false) {
                    frame { ChoiceTitle(nameRes, recommended = true, modifier = Modifier.testTag(TITLE)) }
                }
            }
        }
    }

    private fun fixedWidth(width: Dp, direction: LayoutDirection = LayoutDirection.Ltr) =
        show(direction) { title -> Box(Modifier.width(width)) { title() } }

    private fun nameBounds(): DpRect = compose.onNodeWithText(res.getString(nameRes)).getUnclippedBoundsInRoot()
    private fun tagBounds(): DpRect =
        compose.onNodeWithText(res.getString(R.string.settings_nav_recommended)).getUnclippedBoundsInRoot()
    private fun titleBounds(): DpRect = compose.onNodeWithTag(TITLE).getUnclippedBoundsInRoot()

    @Test
    fun aWideCardPutsTheTagAfterTheName() {
        fixedWidth(600.dp)
        val name = nameBounds()
        val tag = tagBounds()
        assertThat(name.left).isEqualTo(titleBounds().left)
        assertThat(tag.left).isAtLeast(name.right)
        // One line: the tag sits within the name's line box.
        assertThat(tag.top).isAtLeast(name.top)
        assertThat(tag.bottom).isAtMost(name.bottom)
        assertThat(titleBounds().width).isEqualTo(600.dp)
    }

    @Test
    fun rightToLeftTheNameStartsAtTheRightEdgeAndTheTagFollowsItsLeft() {
        fixedWidth(600.dp, LayoutDirection.Rtl)
        val name = nameBounds()
        val tag = tagBounds()
        assertThat(name.right).isEqualTo(titleBounds().right)
        assertThat(tag.right).isAtMost(name.left)
        assertThat(tag.top).isAtLeast(name.top)
    }

    @Test
    fun aNarrowCardPutsTheTagUnderTheName() {
        fixedWidth(140.dp)
        val name = nameBounds()
        val tag = tagBounds()
        assertThat(tag.top).isAtLeast(name.bottom)
        assertThat(name.width).isEqualTo(140.dp)
        assertThat(tag.width).isEqualTo(140.dp)
    }

    @Test
    fun anUnboundedWidthLaysBothOutOnOneLine() {
        // A horizontal scroll gives an unbounded width, which fillMaxWidth passes through.
        show { title -> Row(Modifier.horizontalScroll(rememberScrollState())) { title() } }
        val name = nameBounds()
        val tag = tagBounds()
        assertThat(tag.left).isAtLeast(name.right)
        assertThat(titleBounds().right).isAtLeast(tag.right)
        assertThat(titleBounds().width.value).isLessThan(800f)
    }

    @Test
    fun anIntrinsicMaxWidthParentGetsTheOneLineWidth() {
        show { title -> Column(Modifier.width(IntrinsicSize.Max)) { title() } }
        val name = nameBounds()
        val tag = tagBounds()
        assertThat(tag.left).isAtLeast(name.right)
        assertThat(titleBounds().width.value).isLessThan(800f)
    }

    @Test
    fun anIntrinsicMinWidthParentStacksWithoutCrashing() {
        show { title -> Column(Modifier.width(IntrinsicSize.Min)) { title() } }
        assertThat(tagBounds().top).isAtLeast(nameBounds().bottom)
    }

    private companion object {
        const val TITLE = "choice_title"
    }
}
