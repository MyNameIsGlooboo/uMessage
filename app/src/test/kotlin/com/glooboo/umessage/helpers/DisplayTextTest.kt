package com.glooboo.umessage.helpers

import com.glooboo.umessage.extensions.getThreadTitle
import com.google.common.truth.Truth.assertThat
import org.fossify.commons.models.SimpleContact
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith

/** getThreadTitle uses android.text.TextUtils, hence Robolectric. */
@RunWith(AndroidJUnit4::class)
class DisplayTextTest {

    @Test
    fun nulFromAnOldBrokenLinkThreadIsNotShown() {
        assertThat("\u0000z".stripControlCharacters()).isEqualTo("z")
    }

    @Test
    fun namesKeepZeroWidthNonJoinerAndEmojiByDefault() {
        val persian = "می‌خواهم"
        assertThat(persian.stripControlCharacters()).isEqualTo(persian)
        assertThat("Bob 😀".stripControlCharacters()).isEqualTo("Bob 😀")
    }

    @Test
    fun addressesDropFormatCharactersToo() {
        assertThat("‎5550100‏".stripControlCharacters(keepFormat = false)).isEqualTo("5550100")
    }

    @Test
    fun threadTitleSkipsControlCharacters() {
        val contact = SimpleContact(1, 1, "\u0000z", "", ArrayList(), ArrayList(), ArrayList())
        assertThat(arrayListOf(contact).getThreadTitle()).isEqualTo("z")
    }
}
