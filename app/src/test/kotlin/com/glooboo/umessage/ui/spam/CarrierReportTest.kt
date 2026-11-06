package com.glooboo.umessage.ui.spam

import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.R
import com.glooboo.umessage.spam.BlockedMessage
import com.glooboo.umessage.spam.DecisionReason
import com.glooboo.umessage.spam.MMS_PLACEHOLDER_BODY
import com.glooboo.umessage.testing.ShadowRecordingSmsManager
import com.glooboo.umessage.ui.theme.UMessageTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * I32: the carrier report. Every sender here is a fake that records the call: no test sends an SMS, and the recording
 * SmsManager of the test setup is checked to have seen nothing either.
 */
@RunWith(AndroidJUnit4::class)
class CarrierReportTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val sent = mutableListOf<Triple<String, String, Int>>()
    private val report = CarrierReport(context) { _, text, number, subId -> sent += Triple(text, number, subId) }

    @Before
    fun setUp() {
        ShadowRecordingSmsManager.reset()
        report.number = ""
    }

    private fun spam(id: Long, body: String, address: String = "AD-SHOP", at: Long = id, subId: Int = 2) =
        BlockedMessage(id, address, body, subId, false, at, DecisionReason.UNKNOWN_SENDER)

    @Test
    fun nothingIsSentWhileNoNumberIsSet() {
        assertThat(report.send(spam(1, "Win a prize"))).isFalse()
        assertThat(sent).isEmpty()
    }

    @Test
    fun onlyAShortNumberOfDigitsIsAccepted() {
        assertThat(CarrierReport.isValidNumber("7726")).isTrue()
        assertThat(CarrierReport.isValidNumber("12")).isFalse()
        assertThat(CarrierReport.isValidNumber("123456789")).isFalse()
        assertThat(CarrierReport.isValidNumber("77a6")).isFalse()
        assertThat(CarrierReport.isValidNumber("+7726")).isFalse()
        report.number = "77a6"
        assertThat(report.send(spam(1, "Win a prize"))).isFalse()
        assertThat(sent).isEmpty()
    }

    @Test
    fun withANumberTheMessageTextGoesOutOnceFromTheSimItCameOn() {
        report.number = " 7726 "

        assertThat(report.send(spam(1, "Win a prize", subId = 2))).isTrue()

        assertThat(sent).containsExactly(Triple("Win a prize", "7726", 2))
    }

    @Test
    fun aPictureMessageWithoutTextIsNotReported() {
        report.number = "7726"

        assertThat(report.send(spam(1, MMS_PLACEHOLDER_BODY))).isFalse()
        assertThat(report.send(spam(2, "  "))).isFalse()
        assertThat(sent).isEmpty()
    }

    @Test
    fun theReportIsTheNewestMessageOfExactlyOneSelectedSender() {
        val groups = groupBySender(
            listOf(spam(1, "old ad", at = 100), spam(2, "new ad", at = 200), spam(3, "other", address = "X", at = 50)),
        )

        assertThat(CarrierReport.pick(groups, setOf("AD-SHOP"))?.body).isEqualTo("new ad")
        assertThat(CarrierReport.pick(groups, setOf("AD-SHOP", "X"))).isNull()
        assertThat(CarrierReport.pick(groups, emptySet())).isNull()
        assertThat(CarrierReport.pick(groups, setOf("nobody"))).isNull()
    }

    @Test
    fun theDialogSendsNothingUntilConfirmedAndNamesTheCost() {
        var confirmed = 0
        compose.setContent {
            UMessageTheme { CarrierReportDialog(number = "7726", onConfirm = { confirmed++ }, onDismiss = {}) }
        }

        compose.onNodeWithText(context.getString(R.string.carrier_report_body, "7726")).assertExists()
        assertThat(context.getString(R.string.carrier_report_body, "7726")).contains("charge")
        assertThat(confirmed).isEqualTo(0)

        compose.onNodeWithText(context.getString(R.string.carrier_report_send)).performClick()

        assertThat(confirmed).isEqualTo(1)
        assertThat(ShadowRecordingSmsManager.textSends).isEmpty()
    }
}
