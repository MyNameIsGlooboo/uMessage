package com.glooboo.umessage.i18n

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Q20: the folder that holds filtered messages is called Spam, not Blocked. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "en")
class SpamTabLabelEnTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun `the tab and the screen title say Spam`() {
        assertEquals("Spam", context.getString(R.string.main_tab_blocked))
        assertEquals("Spam", context.getString(R.string.blocked_title))
        assertEquals("Spam, 3 new", context.resources.getQuantityString(R.plurals.main_tab_blocked_new, 3, 3))
    }
}

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "fa")
class SpamTabLabelFaTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun `the tab and the screen title say the everyday Persian word`() {
        assertEquals("اسپم", context.getString(R.string.main_tab_blocked))
        assertEquals("اسپم", context.getString(R.string.blocked_title))
    }
}
