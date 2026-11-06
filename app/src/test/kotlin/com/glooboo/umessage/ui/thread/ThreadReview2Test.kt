package com.glooboo.umessage.ui.thread

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import com.glooboo.umessage.spam.ListKind
import com.glooboo.umessage.spam.RuleType
import com.glooboo.umessage.spam.SpamLogEntry
import com.glooboo.umessage.spam.SpamRule
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.util.Locale

/** Design review 2, group C: links, search, message details, bubble colours and the schedule calendar. */
class ThreadReview2Test {

    // region TH-05 link sheet
    @Test
    fun everyLinkTapGoesToTheSheet() {
        val tapped = mutableListOf<String>()
        linkSheetUriHandler { tapped += it }.openUri("https://bit.ly/x9Kq2")
        assertThat(tapped).containsExactly("https://bit.ly/x9Kq2")
    }

    @Test
    fun theSheetNamesTheDomain() {
        assertThat(linkHost("https://www.example.com/a?b=1")).isEqualTo("example.com")
        assertThat(linkHost("http://bit.ly/x9Kq2")).isEqualTo("bit.ly")
        assertThat(linkHost("https://bad host.example/x")).isEqualTo("bad")
        assertThat(linkHost("mailto:sara@example.com")).isEqualTo("sara@example.com")
        assertThat(linkCopyText("mailto:sara@example.com")).isEqualTo("sara@example.com")
        assertThat(linkCopyText("https://example.com")).isEqualTo("https://example.com")
    }
    // endregion

    // region TH-04, TH-31 bubbles
    @Test
    fun aLinkNeverBreaksRightAfterItsScheme() {
        assertThat(keepSchemeTogether("https://example.com/a")).isEqualTo("https://⁠example.com/a")
        assertThat(keepSchemeTogether("example.com")).isEqualTo("example.com")
    }

    @Test
    fun darkSentBubblesAreDarkWithLightText() {
        val surface = Color(0xFF121318)
        val pastel = Color(0xFFB0BFF5)
        val (container, content) = darkSentBubbleColors(
            primaryContainer = pastel, onPrimaryContainer = Color(0xFF1A2A5C),
            primary = pastel, surface = surface, onSurface = Color(0xFFE3E2E9), received = Color(0xFF1E1F25),
        )
        assertThat(container.luminance()).isLessThan(0.15f)
        assertThat(content).isEqualTo(Color(0xFFE3E2E9))

        val darkContainer = Color(0xFF2F3E6E)
        val kept = darkSentBubbleColors(darkContainer, Color.White, pastel, surface, Color.White, Color(0xFF1E1F25))
        assertThat(kept.first).isEqualTo(darkContainer)
    }

    @Test
    fun darkSentAndReceivedBubblesAreClearlyDifferentTones() {
        // N6: a dark primaryContainer that is nearly the received bubble's tone is not used as it is.
        val surface = Color(0xFF141218)
        val received = Color(0xFF36343B)
        val onSurface = Color(0xFFE6E0E9)
        val (container, content) = darkSentBubbleColors(
            primaryContainer = Color(0xFF4F378B), onPrimaryContainer = Color(0xFFEADDFF),
            primary = Color(0xFFD0BCFF), surface = surface, onSurface = onSurface, received = received,
        )
        assertThat(container).isNotEqualTo(Color(0xFF4F378B))
        assertThat(contrastRatio(container, received)).isAtLeast(1.5f)
        assertThat(contrastRatio(content, container)).isAtLeast(4.5f)

        // A grey-blue accent close to the page: still a distinct tone, and the text stays readable.
        val (greyBlue, greyBlueText) = darkSentBubbleColors(
            primaryContainer = Color(0xFF33384A), onPrimaryContainer = Color.White,
            primary = Color(0xFFB4C4E8), surface = Color(0xFF111318), onSurface = onSurface,
            received = Color(0xFF33353A),
        )
        assertThat(contrastRatio(greyBlue, Color(0xFF33353A))).isAtLeast(1.5f)
        assertThat(contrastRatio(greyBlueText, greyBlue)).isAtLeast(4.5f)
    }

    @Test
    fun searchMatchesAreHighlighted() {
        val style = SpanStyle(background = Color.Yellow)
        val text = highlightMatches(AnnotatedString("Code ۴۸۲۹۱۳ or code 482913"), "482", style)
        assertThat(text.spanStyles.map { it.start to it.end }).containsExactly(5 to 8, 20 to 23)
        assertThat(highlightMatches(AnnotatedString("abc"), "", style).spanStyles).isEmpty()
    }
    // endregion

    // region TH-20 search
    @Test
    fun searchFoldsCaseDigitsAndArabicLetters() {
        assertThat(matchesSearch("Your CODE is 482913", "code")).isTrue()
        assertThat(matchesSearch("رمز ۴۸۲۹۱۳", "4829")).isTrue()
        assertThat(matchesSearch("كد", "کد")).isTrue()
        assertThat(matchesSearch("hello", "  ")).isFalse()
        assertThat(searchRanges("aaaa", "aa")).containsExactly(0..1, 2..3).inOrder()
    }

    @Test
    fun matchButtonsWrapAround() {
        assertThat(olderMatch(0, 3)).isEqualTo(1)
        assertThat(olderMatch(2, 3)).isEqualTo(0)
        assertThat(newerMatch(0, 3)).isEqualTo(2)
        assertThat(newerMatch(0, 0)).isEqualTo(0)
    }
    // endregion

    // region TH-13 message details
    private fun entry(
        time: Long,
        decision: String,
        reason: String,
        sender: String = "+447121234567",
        ruleId: Long? = null,
    ) =
        SpamLogEntry(time, sender, 1, decision, reason, "SMS", ruleId, if (ruleId != null) "label" else "")

    @Test
    fun theDecisionClosestInTimeFromTheSameSenderIsFound() {
        val entries = listOf(
            entry(1_000_000, "ALLOW", "CONTACT"),
            entry(1_010_000, "ALLOW", "OTP_EXEMPTION"),
            entry(1_005_000, "ALLOW", "UNKNOWN_SENDER", sender = "+14155550100"),
            entry(1_020_000, "FORWARD", "#3 → 5554999 SENT"),
            entry(9_000_000, "ALLOW", "NO_MATCH"),
        )
        val found = MessageDecisions.find(entries, "07121234567", 1_011_000)
        assertThat(found.decision?.reason).isEqualTo("OTP_EXEMPTION")
        assertThat(found.forwards.map { it.reason }).containsExactly("#3 → 5554999 SENT")
        assertThat(MessageDecisions.find(entries, "07121234567", 50_000_000).decision).isNull()
    }

    @Test
    fun senderNamesMatchIgnoringCase() {
        assertThat(MessageDecisions.sameSender("VM-HDFCBK", "vm-hdfcbk")).isTrue()
        assertThat(MessageDecisions.sameSender("VM-HDFCBK", "VM-OTHER")).isFalse()
    }

    @Test
    fun theRuleBehindADecisionIsNamed() {
        val allow = SpamRule(id = 4, kind = ListKind.ALLOW, type = RuleType.NUMBER, pattern = "07121234567")
        val block = SpamRule(id = 7, kind = ListKind.BLOCK, type = RuleType.KEYWORD, pattern = "win a prize")
        val rules = listOf(allow, block)
        val allowed = entry(0, "ALLOW", "ALLOW_RULE")
        assertThat(MessageDecisions.ruleLabel(allowed, "+447121234567", rules)).isEqualTo("07121234567")
        val blocked = entry(0, "BLOCK", "BLOCK_RULE", ruleId = 7)
        assertThat(MessageDecisions.ruleLabel(blocked, "+447121234567", rules)).isEqualTo("win a prize")
        assertThat(MessageDecisions.ruleLabel(entry(0, "ALLOW", "CONTACT"), "x", rules)).isNull()
        assertThat(MessageDecisions.reasonLabel("CONTACT")).isNotNull()
        assertThat(MessageDecisions.reasonLabel("IllegalStateException")).isNull()
    }
    // endregion

    // region TH-24, TH-26 schedule calendar
    @Test
    fun theJalaliWeekStartsOnSaturday() {
        assertThat(firstDayOfWeek(jalali = true, locale = Locale.US)).isEqualTo(DayOfWeek.SATURDAY)
        assertThat(firstDayOfWeek(jalali = false, locale = Locale.forLanguageTag("fa"))).isEqualTo(DayOfWeek.SATURDAY)
        assertThat(firstDayOfWeek(jalali = false, locale = Locale.US)).isEqualTo(DayOfWeek.SUNDAY)
    }

    @Test
    fun aJalaliMonthHasItsOwnDaysAndOnlyTheWeeksItNeeds() {
        // Aban 1405: 1 Aban is Friday 23 October 2026; 30 days.
        val aban = CalendarMonth.of(1405, 8, jalali = true)
        assertThat(aban.first).isEqualTo(LocalDate.of(2026, 10, 23))
        assertThat(aban.length).isEqualTo(30)
        assertThat(aban.dayNumber(LocalDate.of(2026, 11, 2))).isEqualTo(11)
        val weeks = monthWeeks(aban, DayOfWeek.SATURDAY)
        // Saturday-first: Friday is the last cell of the first week.
        assertThat(weeks.first().indexOf(aban.first)).isEqualTo(6)
        assertThat(weeks.size).isEqualTo(6)
        assertThat(weeks.flatten().filterNotNull()).hasSize(30)
        assertThat(weeks.all { it.size == 7 }).isTrue()
    }

    @Test
    fun monthsStepAcrossTheYearInBothCalendars() {
        val esfand = CalendarMonth.of(1404, 12, jalali = true)
        assertThat(esfand.plus(1).let { it.year to it.month }).isEqualTo(1405 to 1)
        assertThat(esfand.plus(1).first).isEqualTo(esfand.last.plusDays(1))
        val december = CalendarMonth.of(2026, 12, jalali = false)
        assertThat(december.plus(1).first).isEqualTo(LocalDate.of(2027, 1, 1))
        // February 2026 starts on a Sunday and fits four Sunday-first weeks: no empty band.
        assertThat(monthWeeks(CalendarMonth.of(2026, 2, jalali = false), DayOfWeek.SUNDAY)).hasSize(4)
        assertThat(CalendarMonth.containing(LocalDate.of(2026, 11, 2), jalali = true).month).isEqualTo(8)
    }

    @Test
    fun monthTitlesFollowTheCalendarAndLanguage() {
        assertThat(monthTitle(CalendarMonth.of(1405, 8, jalali = true), Locale.ENGLISH)).isEqualTo("Aban 1405")
        val persian = Locale.forLanguageTag("fa")
        assertThat(monthTitle(CalendarMonth.of(1405, 8, jalali = true), persian)).isEqualTo("آبان ۱۴۰۵")
        assertThat(monthTitle(CalendarMonth.of(2026, 11, jalali = false), Locale.ENGLISH)).isEqualTo("November 2026")
    }
    // endregion
}
