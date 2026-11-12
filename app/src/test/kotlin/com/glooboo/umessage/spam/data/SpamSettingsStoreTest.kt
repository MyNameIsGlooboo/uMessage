package com.glooboo.umessage.spam.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.spam.SpamSettings
import com.glooboo.umessage.spam.UnknownSenderMode
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

/** Smoke test for Telephony-free logic over real SharedPreferences. Robolectric gives each test fresh prefs. */
@RunWith(AndroidJUnit4::class)
class SpamSettingsStoreTest {

    private val store = SpamSettingsStore(ApplicationProvider.getApplicationContext<Context>())

    @Test
    fun defaultsThenUpdate() = runTest {
        assertThat(store.get()).isEqualTo(SpamSettings())

        store.update { it.copy(mode = UnknownSenderMode.STRICT, purgeAfterDays = 7) }

        assertThat(store.observe().first())
            .isEqualTo(SpamSettings(mode = UnknownSenderMode.STRICT, purgeAfterDays = 7))
    }

    @Test
    fun purgeIsClaimedOncePerInterval() {
        assertThat(store.claimPurge(now = 1_000_000)).isTrue()
        assertThat(store.claimPurge(now = 1_000_001)).isFalse()
        assertThat(store.claimPurge(now = 1_000_000 + PurgePolicy.INTERVAL_MS)).isTrue()
    }

    @Test
    fun releasedPurgeCanBeClaimedAgain() {
        assertThat(store.claimPurge(now = 1_000_000)).isTrue()
        store.releasePurge()
        assertThat(store.claimPurge(now = 1_000_001)).isTrue()
    }
}
