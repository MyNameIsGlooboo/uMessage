package com.glooboo.umessage.ui.thread

import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Landscape with the keyboard up: the "Not in your contacts" card steps aside so the composer never covers it. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w800dp-h360dp-land")
class UnknownCardLandscapeTest {

    @Test
    fun theWindowIsLandscapeAndTheCardCollapsesOnlyWithTheKeyboardUp() {
        val config = ApplicationProvider.getApplicationContext<android.content.Context>().resources.configuration
        val landscape = config.orientation == Configuration.ORIENTATION_LANDSCAPE
        assertThat(landscape).isTrue()
        assertThat(unknownCardCollapsed(landscape, keyboardUp = true)).isTrue()
        assertThat(unknownCardCollapsed(landscape, keyboardUp = false)).isFalse()
    }

    @Test
    fun portraitNeverCollapsesIt() {
        assertThat(unknownCardCollapsed(landscape = false, keyboardUp = true)).isFalse()
    }
}
