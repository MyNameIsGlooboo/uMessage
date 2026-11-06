package com.glooboo.umessage.ui.settings.search

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SearchTextTest {
    @Test
    fun lowerCasesAndDropsAccents() {
        assertThat(SearchText.normalize("Ünï Cödé")).isEqualTo("uni code")
    }

    @Test
    fun unifiesArabicAndPersianLetters() {
        assertThat(SearchText.normalize("كيك")).isEqualTo("کیک")
        assertThat(SearchText.normalize("مدرسة")).isEqualTo("مدرسه")
        assertThat(SearchText.normalize("آب")).isEqualTo("اب")
    }

    @Test
    fun dropsZeroWidthJoinerAndTatweel() {
        assertThat(SearchText.normalize("می‌خواهم")).isEqualTo("میخواهم")
        assertThat(SearchText.normalize("سلـــام")).isEqualTo("سلام")
    }

    @Test
    fun turnsEveryDigitStyleIntoAscii() {
        assertThat(SearchText.normalize("۰۱۲۳۴۵۶۷۸۹")).isEqualTo("0123456789")
        assertThat(SearchText.normalize("٠١٢٣٤٥٦٧٨٩")).isEqualTo("0123456789")
    }

    @Test
    fun splitsWordsOnPunctuationIncludingTheArabicComma() {
        assertThat(SearchText.tokens("Theme, colours (dark)")).containsExactly("theme", "colours", "dark").inOrder()
        assertThat(SearchText.tokens("پیام،اعلان؛ قفل")).containsExactly("پیام", "اعلان", "قفل").inOrder()
        assertThat(SearchText.tokens("  ... ")).isEmpty()
    }

    @Test
    fun editDistanceCountsASwapAsOne() {
        assertThat(SearchText.editDistance("backup", "backup")).isEqualTo(0)
        assertThat(SearchText.editDistance("backup", "backpu")).isEqualTo(1)
        assertThat(SearchText.editDistance("lock", "lok")).isEqualTo(1)
        assertThat(SearchText.editDistance("lock", "luck")).isEqualTo(1)
        assertThat(SearchText.editDistance("lock", "locker")).isEqualTo(2)
        assertThat(SearchText.editDistance("", "ab")).isEqualTo(2)
    }
}
