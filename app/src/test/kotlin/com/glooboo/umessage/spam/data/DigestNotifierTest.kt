package com.glooboo.umessage.spam.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.helpers.DailyChecks
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/** The daily alarm that runs the weekly summary. The summary itself (counted in Spam) is in DigestNotifierSpamTest. */
@RunWith(AndroidJUnit4::class)
class DigestNotifierTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun theDailyAlarmIsBookedOnceUnlessForced() {
        DailyChecks.arm(context)
        DailyChecks.arm(context)

        val alarms = shadowOf(context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager)
        assertThat(alarms.scheduledAlarms).hasSize(1)
        assertThat(alarms.scheduledAlarms.single().interval).isEqualTo(android.app.AlarmManager.INTERVAL_DAY)

        DailyChecks.arm(context, force = true)

        assertThat(alarms.scheduledAlarms).hasSize(1)
    }
}
