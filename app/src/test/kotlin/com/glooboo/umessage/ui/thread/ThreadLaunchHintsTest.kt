package com.glooboo.umessage.ui.thread

import android.content.Intent
import com.glooboo.umessage.helpers.THREAD_FOCUS_COMPOSER
import com.glooboo.umessage.helpers.THREAD_NOTICE
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ThreadLaunchHintsTest {

    @Test
    fun theHintsAreReadOnceAndTakenOffTheIntent() {
        val intent = Intent().putExtra(THREAD_FOCUS_COMPOSER, true).putExtra(THREAD_NOTICE, "Moved")
        assertThat(ThreadLaunchHints.take(intent)).isEqualTo(ThreadLaunchHints(true, "Moved"))
        assertThat(intent.hasExtra(THREAD_FOCUS_COMPOSER)).isFalse()
        assertThat(intent.hasExtra(THREAD_NOTICE)).isFalse()
        assertThat(ThreadLaunchHints.take(intent)).isEqualTo(ThreadLaunchHints(false, null))
    }

    @Test
    fun aBlankNoticeIsNoNotice() {
        val intent = Intent().putExtra(THREAD_NOTICE, " ")
        assertThat(ThreadLaunchHints.take(intent).notice).isNull()
    }
}
