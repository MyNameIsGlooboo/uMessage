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
    fun tappingANumberOpensTheDialerAndNeverPlacesTheCall() {
        val intent = dialIntent("+15550100")

        assertThat(intent.action).isEqualTo(Intent.ACTION_DIAL)
        assertThat(intent.data?.scheme).isEqualTo("tel")
        // Uri.fromParts writes the plus sign as %2B; the dialer reads the decoded number.
        assertThat(intent.data?.schemeSpecificPart).isEqualTo("+15550100")
    }

    @Test
    fun theAppNoLongerDeclaresCallPhone() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertThat(manifest).doesNotContain("android.permission.CALL_PHONE")
    }
}
