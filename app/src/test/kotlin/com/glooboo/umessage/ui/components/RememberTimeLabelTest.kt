package com.glooboo.umessage.ui.components

import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.helpers.DateStyle
import com.glooboo.umessage.helpers.w3cPrefs
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** B13: the Blocked list and the restored sheet show dates in the style chosen in Settings, like the inbox. */
@RunWith(AndroidJUnit4::class)
class RememberTimeLabelTest {
    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `a Jalali user gets the Jalali label for an old message`() {
        context.w3cPrefs.dateStyle = DateStyle.JALALI
        val old = System.currentTimeMillis() - 400L * 24 * 60 * 60 * 1000
        var label = ""
        compose.setContent { label = rememberTimeLabel(old) }
        compose.waitForIdle()
        assertThat(label).isEqualTo(formatListTimeStyled(context, old))
        assertThat(label).isNotEqualTo(formatListTime(context, old))
    }
}
