package com.glooboo.umessage.ui.log

import android.content.Context
import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.spam.ForwardRule
import com.glooboo.umessage.spam.RuleType
import com.glooboo.umessage.spam.SpamLog
import com.glooboo.umessage.spam.SpamLogEntry
import com.glooboo.umessage.ui.components.displayNumber
import com.glooboo.umessage.ui.components.isolateNumbers
import com.glooboo.umessage.ui.log.ForwardLogText.Outcome
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The decision log's forward codes, as ForwardDelivery stores them, shown as sentences. */
@RunWith(AndroidJUnit4::class)
class ForwardLogTextTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val res: Resources get() = context.resources

    private val rules = listOf(
        ForwardRule(id = 1, senderType = RuleType.NUMBER, senderPattern = "07121234567", targets = listOf("5554999"), note = "Bank alerts"),
        ForwardRule(id = 2, senderType = null, bodyType = RuleType.KEYWORD, bodyPattern = "deposit", targets = listOf("5554999")),
        ForwardRule(id = 3, senderType = RuleType.SENDER_ID, senderPattern = "MYBANK", targets = listOf("5554999")),
        ForwardRule(id = 4, senderType = RuleType.PREFIX, senderPattern = "+1", targets = listOf("5554999")),
        ForwardRule(id = 5, senderType = RuleType.NUMBER, senderPattern = "+15550001234", targets = listOf("5554999")),
    )

    @Test
    fun parsesEveryStoredShape() {
        assertThat(ForwardLogText.parse("#1 CODE_EXCLUDED")).isEqualTo(ForwardLogText.Parsed(Outcome.CODE_EXCLUDED, 1))
        assertThat(ForwardLogText.parse("#2 LOOP_FORWARD").outcome).isEqualTo(Outcome.LOOP_FORWARD)
        assertThat(ForwardLogText.parse("#1 → 5554999 SENT")).isEqualTo(ForwardLogText.Parsed(Outcome.SENT, 1, "5554999"))
        assertThat(ForwardLogText.parse("#1 → +15550001111 FAILED_3").outcome).isEqualTo(Outcome.FAILED)
        assertThat(ForwardLogText.parse("#4 → 07121234567 LOOP_SENDER").target).isEqualTo("07121234567")
        assertThat(ForwardLogText.parse("#4 SIM_FALLBACK").outcome).isEqualTo(Outcome.SIM_FALLBACK)
        assertThat(ForwardLogText.parse("→ 5554999 RADIO_FAILED")).isEqualTo(ForwardLogText.Parsed(Outcome.RADIO_FAILED, null, "5554999"))
        assertThat(ForwardLogText.parse("ERROR_IllegalStateException"))
            .isEqualTo(ForwardLogText.Parsed(Outcome.ERROR, detail = "IllegalStateException"))
        assertThat(ForwardLogText.parse("something new").outcome).isEqualTo(Outcome.UNKNOWN)
    }

    @Test
    fun englishSentences() {
        assertThat(ForwardLogText.sentence(res, "#1 CODE_EXCLUDED")).isEqualTo("Not forwarded: verification codes are off for this rule")
        assertThat(ForwardLogText.sentence(res, "#2 LOOP_FORWARD")).isEqualTo("Not forwarded: it was already a forwarded message")
        assertThat(ForwardLogText.sentence(res, "#1 → 5554999 SENT")).isEqualTo("Forwarded to 5554999 (sent)")
        assertThat(ForwardLogText.sentence(res, "#1 → 5554999 FAILED_3")).isEqualTo("Forwarded to 5554999 (failed)")
        assertThat(ForwardLogText.sentence(res, "#1 → 07121234567 LOOP_SENDER"))
            .isEqualTo("Not forwarded to 07121234567: it is the number the message came from")
        assertThat(ForwardLogText.sentence(res, "#1 SIM_FALLBACK")).startsWith("Sent from the receiving SIM")
        assertThat(ForwardLogText.sentence(res, "→ 5554999 RADIO_FAILED")).contains("5554999")
        assertThat(ForwardLogText.sentence(res, "ERROR_IllegalStateException")).contains("IllegalStateException")
        // Something this build does not know is shown as it was stored rather than hidden.
        assertThat(ForwardLogText.sentence(res, "something new")).isEqualTo("something new")
    }

    @Test
    fun ruleIsNamedByItsNoteOrCondition() {
        assertThat(ForwardLogText.ruleLabel(context, "#1 → 5554999 SENT", rules)).isEqualTo("Rule: Bank alerts")
        assertThat(ForwardLogText.ruleLabel(context, "#2 CODE_EXCLUDED", rules)).isEqualTo("Rule: Text: deposit")
        assertThat(ForwardLogText.ruleLabel(context, "#3 LOOP_FORWARD", rules)).isEqualTo("Rule: From: MYBANK")
        assertThat(ForwardLogText.ruleLabel(context, "#9 SENT", rules)).isEqualTo("Rule: a deleted rule")
        // P-4: a prefix sender is isolated whole, however short.
        assertThat(ForwardLogText.ruleLabel(context, "#4 SENT", rules)).isEqualTo("Rule: From: \u2066+1\u2069")
        // A number sender without a note: grouped and in local digits, as the rule row shows it, and isolated.
        assertThat(ForwardLogText.ruleLabel(context, "#5 SENT", rules))
            .isEqualTo("Rule: From: ${isolateNumbers(displayNumber(context, "+15550001234"))}")
        assertThat(ForwardLogText.ruleLabel(context, "→ 5554999 RADIO_FAILED", rules)).isNull()
    }

    @Test
    @Config(qualifiers = "fa")
    fun persianSentencesKeepPhoneNumbersLatin() {
        val sent = ForwardLogText.sentence(res, "#1 → 5554999 SENT")
        assertThat(sent).contains("5554999")
        assertThat(sent).contains("فوروارد")
        assertThat(ForwardLogText.sentence(res, "#1 CODE_EXCLUDED")).startsWith("فوروارد نشد")
        assertThat(ForwardLogText.ruleLabel(context, "#1 SENT", rules)).isEqualTo("قانون: Bank alerts")
        // T-4: literal texts, not built with the code under test. A prefix in Persian digits, isolated whole.
        assertThat(ForwardLogText.ruleLabel(context, "#4 SENT", rules)).isEqualTo("قانون: از: \u2066+\u06F1\u2069")
        // A number sender in Persian digits whatever the grouping, with no Latin digit left.
        val number = ForwardLogText.ruleLabel(context, "#5 SENT", rules)!!
        assertThat(number).contains("\u06F1")
        assertThat(number.none { it in '0'..'9' }).isTrue()
    }

    @Test
    fun storedEntriesAreUnchanged() {
        // Display-time mapping only: the log file still holds the codes.
        val entry = SpamLogEntry(1, "+15551234567", 1, "FORWARD_SKIPPED", "#1 CODE_EXCLUDED", "FORWARD")
        assertThat(SpamLog.decode(SpamLog.encode(entry))).isEqualTo(entry)
        assertThat(ForwardLogText.isForward("FORWARD_SKIPPED")).isTrue()
        assertThat(ForwardLogText.isForward("BLOCK")).isFalse()
    }
}
