package com.glooboo.umessage.ui.settings.search

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The settings search matcher on invented settings: normalising, prefixes, synonyms, typos and ranking. */
class SettingsSearchMatcherTest {
    private val docs = listOf(
        SettingsSearchDoc(0, "Appearance", "Theme, colours, text size", "theme dark light night", "Settings"),
        SettingsSearchDoc(1, "Backup", "Save messages to a file", "export pdf", "Settings"),
        SettingsSearchDoc(2, "Spam protection", "Blocks unwanted messages", "block junk", "Settings"),
        SettingsSearchDoc(3, "App lock", "Ask for a PIN", "pin password fingerprint", "Settings"),
        SettingsSearchDoc(4, "Notifications", "Alerts and sounds", "sound ringtone", "Settings"),
        SettingsSearchDoc(5, "Language", "", "persian farsi english", "Settings"),
        SettingsSearchDoc(6, "Text size", "", "font bigger", "Appearance"),
        SettingsSearchDoc(7, "پیام‌های مسدود", "", "هرزنامه اسپم", "تنظیمات"),
        SettingsSearchDoc(8, "کیبورد", "", "تایپ", "تنظیمات"),
        SettingsSearchDoc(9, "بخش ۲", "", "", "تنظیمات"),
        SettingsSearchDoc(10, "سقف ۷۷۲۶", "", "", "تنظیمات"),
    )
    private val matcher = SettingsSearchMatcher(docs)

    private fun ids(query: String, matcher: SettingsSearchMatcher = this.matcher) =
        matcher.search(query).map { it.id }

    @Test
    fun synonymFindsTheSetting() {
        assertThat(ids("dark")).containsExactly(0)
        assertThat(ids("junk")).containsExactly(2)
        assertThat(ids("export")).containsExactly(1)
    }

    @Test
    fun ignoresCaseAndAccents() {
        assertThat(ids("THEME")).containsExactly(0)
        assertThat(ids("thème")).containsExactly(0)
        assertThat(ids("NöTiFiCàTiOnS").first()).isEqualTo(4)
    }

    @Test
    fun matchesTheStartOfAWord() {
        assertThat(ids("notif").first()).isEqualTo(4)
        assertThat(ids("fing")).containsExactly(3)
    }

    @Test
    fun doesNotMatchTheMiddleOfAWord() {
        assertThat(ids("tion")).isEmpty()
    }

    @Test
    fun everyWordMustMatchInAnyOrder() {
        assertThat(ids("text size").first()).isEqualTo(6)
        assertThat(ids("size text").first()).isEqualTo(6)
        assertThat(ids("dark backup")).isEmpty()
    }

    @Test
    fun wordsMayMatchInDifferentFields() {
        assertThat(ids("dark size")).containsExactly(0)
    }

    @Test
    fun typoOfOneLetterIsForgivenForLongWords() {
        assertThat(ids("notificaiton").first()).isEqualTo(4)
        assertThat(ids("backpu").first()).isEqualTo(1)
        assertThat(ids("pasword").first()).isEqualTo(3)
        assertThat(ids("fingerpint").first()).isEqualTo(3)
    }

    @Test
    fun noTypoToleranceForShortWords() {
        assertThat(ids("lok")).isEmpty()
        assertThat(ids("pdg")).isEmpty()
    }

    @Test
    fun twoTyposAreTooMany() {
        assertThat(ids("bakcpu")).isEmpty()
    }

    @Test
    fun blankAndOneLetterQueriesFindNothing() {
        assertThat(ids("")).isEmpty()
        assertThat(ids("   ")).isEmpty()
        assertThat(ids("t")).isEmpty()
        assertThat(ids("!!")).isEmpty()
    }

    @Test
    fun pageNameIsSearchedToo() {
        val hits = ids("appearance")
        assertThat(hits.first()).isEqualTo(0)
        assertThat(hits).contains(6)
    }

    @Test
    fun titleBeatsKeywordBeatsSummaryBeatsPage() {
        val ranked = SettingsSearchMatcher(
            listOf(
                SettingsSearchDoc(0, "Zzz", "", "", "Sound"),
                SettingsSearchDoc(1, "Yyy", "Plays a sound", "", ""),
                SettingsSearchDoc(2, "Xxx", "", "sound", ""),
                SettingsSearchDoc(3, "Sound", "", "", ""),
            ),
        )
        assertThat(ids("sound", ranked)).containsExactly(3, 2, 1, 0).inOrder()
    }

    @Test
    fun titlePrefixBeatsKeywordExact() {
        val ranked = SettingsSearchMatcher(
            listOf(
                SettingsSearchDoc(0, "Other", "", "sound", ""),
                SettingsSearchDoc(1, "Sounds", "", "", ""),
            ),
        )
        assertThat(ids("sound", ranked)).containsExactly(1, 0).inOrder()
    }

    @Test
    fun exactWordBeatsPrefixInTheSameField() {
        val ranked = SettingsSearchMatcher(
            listOf(
                SettingsSearchDoc(0, "Locked chats", "", "", ""),
                SettingsSearchDoc(1, "Lock", "", "", ""),
            ),
        )
        assertThat(ids("lock", ranked)).containsExactly(1, 0).inOrder()
    }

    @Test
    fun titleMatchOfAllWordsBeatsSplitMatch() {
        val ranked = SettingsSearchMatcher(
            listOf(
                SettingsSearchDoc(0, "Delete", "old things", "", ""),
                SettingsSearchDoc(1, "Delete old messages", "", "", ""),
            ),
        )
        assertThat(ids("delete old", ranked).first()).isEqualTo(1)
    }

    @Test
    fun equalScoresKeepTheIndexOrder() {
        val same = SettingsSearchMatcher(List(5) { SettingsSearchDoc(it, "Alert $it", "", "", "") })
        assertThat(ids("alert", same)).containsExactly(0, 1, 2, 3, 4).inOrder()
    }

    @Test
    fun showsAtMostEightResults() {
        val many = SettingsSearchMatcher(List(20) { SettingsSearchDoc(it, "Alert $it", "", "", "") })
        assertThat(many.search("alert")).hasSize(SettingsSearchMatcher.MAX_RESULTS)
        assertThat(many.search("alert", limit = 3)).hasSize(3)
    }

    @Test
    fun persianSynonymAndPrefix() {
        assertThat(ids("هرزنامه")).containsExactly(7)
        assertThat(ids("اسپ")).containsExactly(7)
        assertThat(ids("تایپ")).containsExactly(8)
    }

    @Test
    fun persianTypoOfOneLetter() {
        assertThat(ids("هرزنمه")).containsExactly(7)
    }

    @Test
    fun arabicLetterFormsMatchPersianOnes() {
        assertThat(ids("كيبورد")).containsExactly(8)
        assertThat(ids("تايپ")).containsExactly(8)
    }

    @Test
    fun arabicVowelMarksAreIgnored() {
        assertThat(ids("هَرزنامه")).containsExactly(7)
    }

    @Test
    fun halfSpaceAndNoSpaceAreTheSame() {
        assertThat(ids("پیامهای")).containsExactly(7)
        assertThat(ids("پیام‌های")).containsExactly(7)
        assertThat(ids("پیام‌های مسدود")).containsExactly(7)
    }

    @Test
    fun persianAndArabicDigitsMatchAsciiDigits() {
        assertThat(ids("بخش 2")).containsExactly(9)
        assertThat(ids("بخش ٢")).containsExactly(9)
        assertThat(ids("بخش ۲")).containsExactly(9)
        assertThat(ids("7726")).containsExactly(10)
        assertThat(ids("۷۷۲۶")).containsExactly(10)
    }

    @Test
    fun latinAndPersianQueriesFindTheirOwnSettings() {
        assertThat(ids("spam")).containsExactly(2)
        assertThat(ids("اسپم")).containsExactly(7)
    }
}
