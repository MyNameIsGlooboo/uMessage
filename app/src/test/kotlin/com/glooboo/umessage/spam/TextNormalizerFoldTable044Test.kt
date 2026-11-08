package com.glooboo.umessage.spam

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** PF2: [TextNormalizer.fold] is what is saved in `messages.body_folded`; this pins its output for a table of cases. */
class TextNormalizerFoldTable044Test {

    @Test
    fun foldMatchesTheTable() {
        val cases = mapOf(
            "" to "",
            "plain ASCII 123" to "plain ASCII 123",
            "Hello" to "Hello", // case is left alone; LIKE ignores ASCII case, Kotlin matching ignores case
            "علي" to "علی",
            "كتاب" to "کتاب",
            "مصطفى" to "مصطفی",
            "إعلان أول آخر ٱ" to "اعلان اول اخر ا",
            "مؤسسة" to "موسسه",
            "۰۱۲۳۴۵۶۷۸۹" to "0123456789",
            "٠١٢٣٤٥٦٧٨٩" to "0123456789",
            "می‌خواهم" to "میخواهم",
            "ســلام" to "سلام",
            "مُحَمَّد" to "محمد",
            "a­b﻿c" to "abc",
            "تخفیف ۵۰% و ٥٠٪" to "تخفیف 50% و 50٪",
        )
        for ((input, expected) in cases) assertThat(TextNormalizer.fold(input)).isEqualTo(expected)
    }

    @Test
    fun foldIsIdempotent() {
        val text = "علي ۱۲۳ ك‌تاب ٱ"
        assertThat(TextNormalizer.fold(TextNormalizer.fold(text))).isEqualTo(TextNormalizer.fold(text))
    }
}
