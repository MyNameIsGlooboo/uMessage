package com.glooboo.umessage.helpers

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/** I02, I45, I08: defaults and the "is it due" claims. */
@RunWith(AndroidJUnit4::class)
class CleanupPrefsTest {

    private val prefs = CleanupPrefs.get(ApplicationProvider.getApplicationContext<Context>())
    private val week = CleanupPrefs.DIGEST_INTERVAL_MS

    @Test
    fun cleanupAndTheDigestAreOffByDefault() {
        assertThat(prefs.codeDays).isEqualTo(0)
        assertThat(prefs.oldMessageDays).isEqualTo(0)
        assertThat(prefs.codeCardDismissed).isFalse()
        assertThat(prefs.digestOn).isFalse()
        assertThat(prefs.digestChosen).isFalse()
    }

    @Test
    fun theFirstDigestClaimOnlyStartsTheWeek() {
        assertThat(prefs.claimDigest(1_000)).isFalse()
        assertThat(prefs.claimDigest(1_000 + week - 1)).isFalse()
        assertThat(prefs.claimDigest(1_000 + week)).isTrue()
        assertThat(prefs.claimDigest(1_000 + week + 1)).isFalse()
    }

    @Test
    fun aClockThatWentBackwardsStartsTheWeekAgain() {
        prefs.claimDigest(10 * week)

        assertThat(prefs.claimDigest(5 * week)).isFalse()
        assertThat(prefs.claimDigest(5 * week + week)).isTrue()
    }

    @Test
    fun cleanupIsClaimedOncePerInterval() {
        assertThat(prefs.claimCleanup(1_000)).isTrue()
        assertThat(prefs.claimCleanup(2_000)).isFalse()
        assertThat(prefs.claimCleanup(1_000 + CleanupPrefs.CLEANUP_INTERVAL_MS)).isTrue()
    }

    @Test
    fun dueWhenNeverRunOrTheClockWentBackwards() {
        assertThat(DuePolicy.isDue(0, 5, 10)).isTrue()
        assertThat(DuePolicy.isDue(100, 50, 10)).isTrue()
        assertThat(DuePolicy.isDue(100, 105, 10)).isFalse()
    }
}
