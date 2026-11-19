package com.glooboo.umessage.ui.components

import android.content.Context
import android.provider.ContactsContract.CommonDataKinds.Phone
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.fossify.commons.models.PhoneNumber
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** M-UI10: a row of the "choose a number" dialog keeps its digit groups in order in a right-to-left dialog. */
@RunWith(AndroidJUnit4::class)
class NumberChoiceLabelTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun theNumberIsIsolatedAsOneLeftToRightRun() {
        val label = context.numberChoiceLabel(PhoneNumber("+15550001234", Phone.TYPE_MOBILE, "", "+15550001234"))

        // One isolate around the whole number, then the type: "<LRI>+1 555-000-1234<PDI> (Mobile)".
        assertThat(label).startsWith("⁦+1")
        assertThat(label.count { it == '⁦' }).isEqualTo(1)
        assertThat(label.substringBefore('⁩').filter { it.isDigit() }).isEqualTo("15550001234")
        assertThat(label.substringAfter('⁩')).startsWith(" (")
    }

    @Test
    @Config(qualifiers = "fa")
    fun inPersianTheNumberHasPersianDigitsInOrderInsideOneIsolate() {
        val label = context.numberChoiceLabel(PhoneNumber("+15550001234", Phone.TYPE_MOBILE, "", "+15550001234"))

        assertThat(label).startsWith("⁦+۱")
        assertThat(label.count { it == '⁦' }).isEqualTo(1)
        assertThat(label.substringBefore('⁩').filter { it.isDigit() }).isEqualTo("۱۵۵۵۰۰۰۱۲۳۴")
        assertThat(label.substringAfter('⁩')).startsWith(" (")
    }
}
