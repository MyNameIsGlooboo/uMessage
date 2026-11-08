package com.glooboo.umessage.messaging

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.backup.BackupMoreSettings
import com.glooboo.umessage.backup.BackupSettings
import com.glooboo.umessage.extensions.config
import com.glooboo.umessage.helpers.SEND_LONG_AS_MMS_AFTER
import com.glooboo.umessage.helpers.SEND_LONG_MESSAGE_MMS
import com.glooboo.umessage.spam.SpamSettings
import com.glooboo.umessage.ui.thread.SmsSegments
import com.google.common.truth.Truth.assertThat
import org.fossify.commons.helpers.PREFS_KEY
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Q9: a long text becomes a picture message only when the user chose a number of SMS parts. */
@RunWith(AndroidJUnit4::class)
class LongTextMmsTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private fun prefs() = context.getSharedPreferences(PREFS_KEY, Context.MODE_PRIVATE)

    @Before fun cleanPrefs() {
        prefs().edit().remove(SEND_LONG_AS_MMS_AFTER).remove(SEND_LONG_MESSAGE_MMS).commit()
    }

    @Test fun neverSendsNothingAsMmsHoweverLong() {
        listOf(1, 2, 3, 10, 50, 1_000).forEach {
            assertThat(LongTextMms.shouldSendAsMms(it, LongTextMms.NEVER)).isFalse()
        }
    }

    @Test fun aThresholdSendsAsMmsFromThatManyPartsOn() {
        LongTextMms.THRESHOLDS.forEach { after ->
            assertThat(LongTextMms.shouldSendAsMms(after - 1, after)).isFalse()
            assertThat(LongTextMms.shouldSendAsMms(after, after)).isTrue()
            assertThat(LongTextMms.shouldSendAsMms(after + 1, after)).isTrue()
        }
        assertThat(LongTextMms.THRESHOLDS).containsExactly(2, 3, 4, 5, 6, 8, 10).inOrder()
        assertThat(LongTextMms.CHOICES.first()).isEqualTo(LongTextMms.NEVER)
    }

    @Test fun englishPartsAreCountedByThePlatform() {
        assertThat(LongTextMms.smsParts("a".repeat(160))).isEqualTo(1)
        assertThat(LongTextMms.smsParts("a".repeat(161))).isEqualTo(2)
        assertThat(LongTextMms.smsParts("a".repeat(306))).isEqualTo(2)
        assertThat(LongTextMms.smsParts("a".repeat(307))).isEqualTo(3)
    }

    @Test fun persianTextCountsSeventyThenSixtySevenCharactersPerPart() {
        assertThat(LongTextMms.smsParts("س".repeat(70))).isEqualTo(1)
        assertThat(LongTextMms.smsParts("س".repeat(71))).isEqualTo(2)
        assertThat(LongTextMms.smsParts("س".repeat(134))).isEqualTo(2)
        assertThat(LongTextMms.smsParts("س".repeat(135))).isEqualTo(3)
        // The composer's own counter agrees with the count the decision uses.
        listOf(1, 70, 71, 134, 135, 400).forEach {
            val text = "س".repeat(it)
            assertThat(LongTextMms.smsParts(text)).isEqualTo(SmsSegments.of(text).parts)
        }
    }

    @Test fun aPersianTextReachesTheThresholdSoonerThanAnEnglishOne() {
        val persian = "س".repeat(135)
        val english = "a".repeat(135)
        assertThat(LongTextMms.shouldSendAsMms(LongTextMms.smsParts(persian), 3)).isTrue()
        assertThat(LongTextMms.shouldSendAsMms(LongTextMms.smsParts(english), 3)).isFalse()
    }

    @Test fun anInstallThatNeverChoseSendsLongTextsAsSms() {
        assertThat(context.config.sendLongAsMmsAfterParts).isEqualTo(LongTextMms.NEVER)
        assertThat(context.isLongMmsMessage("a".repeat(2_000))).isFalse()
    }

    @Test fun theOldSwitchOffMeansNever() {
        prefs().edit().putBoolean(SEND_LONG_MESSAGE_MMS, false).commit()
        assertThat(context.config.sendLongAsMmsAfterParts).isEqualTo(LongTextMms.NEVER)
    }

    @Test fun anOldExplicitOnKeepsMmsFromTwoParts() {
        prefs().edit().putBoolean(SEND_LONG_MESSAGE_MMS, true).commit()
        assertThat(context.config.sendLongAsMmsAfterParts).isEqualTo(2)
        assertThat(context.isLongMmsMessage("a".repeat(161))).isTrue()
        assertThat(context.isLongMmsMessage("a".repeat(160))).isFalse()
    }

    @Test fun theNewChoiceWinsOverTheOldSwitchAndIsKept() {
        prefs().edit().putBoolean(SEND_LONG_MESSAGE_MMS, true).commit()
        context.config.sendLongAsMmsAfterParts = LongTextMms.NEVER
        assertThat(context.config.sendLongAsMmsAfterParts).isEqualTo(LongTextMms.NEVER)
        context.config.sendLongAsMmsAfterParts = 6
        assertThat(context.config.sendLongAsMmsAfterParts).isEqualTo(6)
        assertThat(context.isLongMmsMessage("a".repeat(5 * 153))).isFalse()
        assertThat(context.isLongMmsMessage("a".repeat(5 * 153 + 1))).isTrue()
    }

    @Test fun aDamagedStoredValueFallsBackToNever() {
        prefs().edit().putInt(SEND_LONG_AS_MMS_AFTER, 7).commit()
        assertThat(context.config.sendLongAsMmsAfterParts).isEqualTo(LongTextMms.NEVER)
        context.config.sendLongAsMmsAfterParts = -3
        assertThat(context.config.sendLongAsMmsAfterParts).isEqualTo(LongTextMms.NEVER)
    }

    @Test fun theChoiceSurvivesABackupAndARestore() {
        context.config.sendLongAsMmsAfterParts = 8
        val saved = BackupMoreSettings.collect(context, BackupSettings(), SpamSettings())
        assertThat(saved.sendLongAsMmsAfter).isEqualTo(8)
        context.config.sendLongAsMmsAfterParts = LongTextMms.NEVER
        BackupMoreSettings.apply(context, saved)
        assertThat(context.config.sendLongAsMmsAfterParts).isEqualTo(8)
    }

    @Test fun anOldBackupWithoutTheChoiceKeepsTheDefault() {
        BackupMoreSettings.apply(context, BackupSettings())
        assertThat(context.config.sendLongAsMmsAfterParts).isEqualTo(LongTextMms.NEVER)
        BackupMoreSettings.apply(context, BackupSettings(sendLongAsMms = false))
        assertThat(context.config.sendLongAsMmsAfterParts).isEqualTo(LongTextMms.NEVER)
        BackupMoreSettings.apply(context, BackupSettings(sendLongAsMms = true))
        assertThat(context.config.sendLongAsMmsAfterParts).isEqualTo(2)
    }
}
