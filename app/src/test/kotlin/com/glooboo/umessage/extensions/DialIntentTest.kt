package com.glooboo.umessage.extensions

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class DialIntentTest {
    @Test
    fun withTheCallPermissionATapPlacesTheCall() {
        val intent = callIntent("+15550100", canCall = true)!!

        assertThat(intent.action).isEqualTo(Intent.ACTION_CALL)
        assertThat(intent.data?.scheme).isEqualTo("tel")
        // Uri.fromParts writes the plus sign as %2B; the phone app reads the decoded number.
        assertThat(intent.data?.schemeSpecificPart).isEqualTo("+15550100")
    }

    @Test
    fun withoutThePermissionATapOpensTheDialer() {
        val intent = callIntent("+15550100", canCall = false)!!

        assertThat(intent.action).isEqualTo(Intent.ACTION_DIAL)
        assertThat(intent.data?.schemeSpecificPart).isEqualTo("+15550100")
    }

    @Test
    fun onlyDialableNumbersBecomeAnIntent() {
        assertThat(isDialableNumber("(555) 010-0100")).isTrue()
        assertThat(isDialableNumber("*123#")).isTrue()
        assertThat(isDialableNumber("")).isFalse()
        assertThat(isDialableNumber("555;ATD")).isFalse()
        assertThat(isDialableNumber("call me")).isFalse()
        assertThat(callIntent("not a number", canCall = true)).isNull()
    }

    @Test
    fun theAppDeclaresCallPhoneForTheTapToCall() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertThat(manifest).contains("android.permission.CALL_PHONE")
    }
}
