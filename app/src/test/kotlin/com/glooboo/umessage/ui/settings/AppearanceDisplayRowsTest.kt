package com.glooboo.umessage.ui.settings

import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.R
import com.glooboo.umessage.helpers.DateStyle
import com.glooboo.umessage.helpers.threadPrefs
import com.glooboo.umessage.helpers.w3cPrefs
import com.glooboo.umessage.ui.theme.UMessageTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** U46, U05: the calendar is one row that opens a dialog; unread first is a switch on the Appearance page. */
@RunWith(AndroidJUnit4::class)
class AppearanceDisplayRowsTest {
    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun show() = compose.setContent {
        UMessageTheme(darkTheme = false, dynamicColor = false, syncWindow = false) { AppearanceDisplayRows() }
    }

    @Test
    fun theCalendarRowOpensADialogAndKeepsBothSettingsInStep() {
        show()
        compose.onNodeWithText(context.getString(R.string.ux8e_calendar)).performClick()
        compose.onNodeWithText(context.getString(R.string.w3c_date_jalali)).performClick()

        assertThat(context.w3cPrefs.dateStyle).isEqualTo(DateStyle.JALALI)
        assertThat(context.threadPrefs.useJalaliDates).isTrue()
    }

    @Test
    fun unreadFirstSwitchesFromThePage() {
        show()
        compose.onNodeWithText(context.getString(R.string.i36_unread_first)).performClick()

        assertThat(context.w3cPrefs.unreadFirst).isTrue()
    }
}
