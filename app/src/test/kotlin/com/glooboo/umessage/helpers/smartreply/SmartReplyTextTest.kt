package com.glooboo.umessage.helpers.smartreply

import com.glooboo.umessage.helpers.smartreply.SmartReplyText.Script
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** I59: how texts are read before they are compared with the tables. */
class SmartReplyTextTest {

    private fun normalize(text: String) = SmartReplyText.normalize(text)

    @Test
    fun englishIsLowerCaseWithoutPunctuationOrApostrophes() {
        assertThat(normalize("Can't")).isEqualTo("cant")
        assertThat(normalize("I’m  HERE!!")).isEqualTo("im here")
        assertThat(normalize("Thanks!!! 🙏")).isEqualTo("thanks")
    }

    @Test
    fun accentsAndTurkishLettersBecomePlainLetters() {
        assertThat(normalize("Café")).isEqualTo("cafe")
        assertThat(normalize("İyi Geceler")).isEqualTo("iyi geceler")
        assertThat(normalize("IYI GECELER")).isEqualTo("iyi geceler")
        assertThat(normalize("Günaydın")).isEqualTo("gunaydin")
    }

    @Test
    fun repeatedLettersAreWrittenOnce() {
        assertThat(normalize("Heyyyy")).isEqualTo("hey")
        assertThat(normalize("hello")).isEqualTo("hello")
        assertThat(normalize("سلاااام")).isEqualTo("سلام")
    }

    @Test
    fun persianDigitsAndZeroWidthJoinersAreFolded() {
        assertThat(normalize("۱۲۳ abc")).isEqualTo("123 abc")
        assertThat(normalize("می‌آیی")).isEqualTo(normalize("می آیی"))
    }

    @Test
    fun persianVerbPrefixJoinsTheNextWord() {
        assertThat(normalize("می آیی")).isEqualTo("میآیی")
        assertThat(normalize("نمی تونم")).isEqualTo("نمیتونم")
    }

    @Test
    fun arabicLetterVariantsAreJoined() {
        assertThat(normalize("أهلاً")).isEqualTo(normalize("اهلا"))
        assertThat(normalize("السلامة")).isEqualTo(normalize("السلامه"))
    }

    @Test
    fun scriptIsOneOrNothing() {
        assertThat(SmartReplyText.script(normalize("hello"))).isEqualTo(Script.Latin)
        assertThat(SmartReplyText.script(normalize("سلام"))).isEqualTo(Script.Arabic)
        assertThat(SmartReplyText.script(normalize("hello سلام"))).isNull()
        assertThat(SmartReplyText.script(normalize("你好"))).isEqualTo(Script.Han)
        assertThat(SmartReplyText.script(normalize("hello 你好"))).isNull()
        assertThat(SmartReplyText.script(normalize("你好 안녕"))).isNull()
        assertThat(SmartReplyText.script(normalize("ありがとう"))).isEqualTo(Script.Japanese)
        assertThat(SmartReplyText.script(normalize("コーヒー"))).isEqualTo(Script.Japanese)
        assertThat(SmartReplyText.script(normalize("了解しました"))).isEqualTo(Script.Japanese)
        assertThat(SmartReplyText.script(normalize("안녕하세요"))).isEqualTo(Script.Hangul)
        assertThat(SmartReplyText.script(normalize("ㅋㅋㅋ"))).isEqualTo(Script.Hangul)
        assertThat(SmartReplyText.script(normalize("สวัสดีครับ"))).isEqualTo(Script.Thai)
        assertThat(SmartReplyText.script(normalize("नमस्ते"))).isEqualTo(Script.Devanagari)
        assertThat(SmartReplyText.script(normalize("ধন্যবাদ"))).isEqualTo(Script.Bengali)
        assertThat(SmartReplyText.script(normalize("Привіт"))).isEqualTo(Script.Cyrillic)
        assertThat(SmartReplyText.script(normalize("Привет"))).isEqualTo(Script.Cyrillic)
        assertThat(SmartReplyText.script(normalize("привет hello"))).isNull()
        assertThat(SmartReplyText.script(normalize("γεια"))).isNull()
        assertThat(SmartReplyText.script(normalize("123"))).isNull()
        assertThat(SmartReplyText.script("")).isNull()
    }

    @Test
    fun onlyScriptsWithoutSpacesAreCompact() {
        val compact = Script.entries.filter { it.compact }
        assertThat(compact).containsExactly(Script.Han, Script.Japanese, Script.Hangul, Script.Thai)
        assertThat(Script.named("japanese")).isEqualTo(Script.Japanese)
        assertThat(Script.named("unknown")).isEqualTo(Script.Latin)
        assertThat(Script.Japanese.reads(Script.Han)).isTrue()
        assertThat(Script.Han.reads(Script.Japanese)).isFalse()
    }

    @Test
    fun compactTextsDropSpacesAndMatchAnywhere() {
        assertThat(normalize("你好！")).isEqualTo("你好")
        assertThat(SmartReplyText.compact(normalize("집에 가는 중"))).isEqualTo("집에가는중")
        assertThat(SmartReplyText.containsPhrase("你明天来吗", "来吗", compact = true)).isTrue()
        assertThat(SmartReplyText.containsPhrase("你明天来吗", "来吗", compact = false)).isFalse()
        assertThat(SmartReplyText.containsPhrase("abc", "", compact = true)).isFalse()
        assertThat(normalize("谢谢谢谢谢谢")).isEqualTo("谢")
        assertThat(normalize("ＯＫ！")).isEqualTo("ok")
    }

    @Test
    fun thaiLengthIgnoresVowelAndToneMarks() {
        val thai = SmartReplyText.compact(normalize("ขอบคุณค่ะ"))
        assertThat(thai).contains("\u0E48")
        assertThat(SmartReplyText.length(thai)).isEqualTo(thai.length - 2)
    }

    @Test
    fun indicVowelSignsAndMarksStayInsideWords() {
        assertThat(normalize("चलता हूँ")).isEqualTo("चलता हूँ")
        assertThat(normalize("चलता हूँ")).isNotEqualTo(normalize("चलता है"))
        assertThat(normalize("नमस्ते")).isEqualTo("नमस्ते")
        assertThat(normalize("धन्यवाद!")).isEqualTo("धन्यवाद")
        assertThat(normalize("আমি ভালো আছি")).isEqualTo("আমি ভালো আছি")
        assertThat(normalize("কি")).isNotEqualTo(normalize("কী"))
    }

    @Test
    fun russianYoReadsAsYe() {
        assertThat(normalize("всё")).isEqualTo(normalize("все"))
    }

    @Test
    fun questionMarksAreCountedInGroups() {
        assertThat(SmartReplyText.questionGroups("ok")).isEqualTo(0)
        assertThat(SmartReplyText.questionGroups("ok??")).isEqualTo(1)
        assertThat(SmartReplyText.questionGroups("a? b?")).isEqualTo(2)
        assertThat(SmartReplyText.questionGroups("کجایی؟")).isEqualTo(1)
        assertThat(SmartReplyText.hasQuestion("ok?")).isTrue()
        assertThat(SmartReplyText.hasQuestion("ok")).isFalse()
        assertThat(SmartReplyText.questionGroups("你好吗？")).isEqualTo(1)
        assertThat(SmartReplyText.hasQuestion("元気？")).isTrue()
    }

    @Test
    fun phrasesMatchOnWholeWordsOnly() {
        assertThat(SmartReplyText.containsWords("are you coming back", "you coming")).isTrue()
        assertThat(SmartReplyText.containsWords("are you comingback", "you coming")).isFalse()
        assertThat(SmartReplyText.containsWords("hello", "")).isFalse()
    }
}
