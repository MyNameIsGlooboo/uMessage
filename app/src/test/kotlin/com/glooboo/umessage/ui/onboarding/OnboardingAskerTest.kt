package com.glooboo.umessage.ui.onboarding

import android.Manifest
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.ui.setup.PermissionAsker
import com.glooboo.umessage.ui.theme.UMessageTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * M-UI8: the Allow step asks through the Setup page's [PermissionAsker]. A dialog that was only dismissed is not taken
 * for "don't ask again", so the next tap asks again instead of opening app info.
 */
@RunWith(AndroidJUnit4::class)
class OnboardingAskerTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var asker: PermissionAsker
    private var answers = 0

    private fun show() = compose.setContent {
        UMessageTheme(darkTheme = false, dynamicColor = false, syncWindow = false) {
            asker = rememberOnboardingAsker { answers++ }
        }
    }

    @Test
    fun aDismissedDialogIsNotBlocked() {
        show()
        compose.runOnIdle {
            // Back or a tap outside: refused, no rationale before or after, a person took time to answer.
            asker.onResult(mapOf(Manifest.permission.READ_CONTACTS to false))
        }
        compose.runOnIdle { assertThat(asker.denied).isEmpty() }
    }

    @Test
    fun aRequestGoesToTheSystemDialog() {
        show()
        var launched: List<String>? = null
        compose.runOnIdle {
            asker.launch = { launched = it.toList() }
            asker.request(listOf(Manifest.permission.READ_CONTACTS))
        }
        assertThat(launched).containsExactly(Manifest.permission.READ_CONTACTS)
    }
}
