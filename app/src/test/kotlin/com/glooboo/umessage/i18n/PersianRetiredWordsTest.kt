package com.glooboo.umessage.i18n

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * M-UI11: the app's Persian strings use the everyday words of docs/translation-fa.md, never the retired formal
 * ones listed there as "Formerly ...". The settings-search keywords (`sks_*`) keep the old words on purpose, so a
 * setting is found with either word.
 */
class PersianRetiredWordsTest {

    private val appRes = File("src/main/res")

    /** Retired word -> the word to use. Patterns, so a retired word inside a kept one is not flagged. */
    private val retired = linkedMapOf(
        "هرزنامه" to "اسپم",
        "مسدود" to "بلاک",
        "رفع بلاک" to "آنبلاک",
        "بایگانی" to "آرشیو",
        "پشتیبان(?!ی)" to "بک‌آپ (پشتیبانی = support stays)",
        "صادر کردن" to "خروجی گرفتن",
        "بازارسال" to "فوروارد",
        "هم‌رسانی" to "اشتراک‌گذاری",
        "گذرواژه" to "رمز",
        "مجوز" to "دسترسی",
        "موقعیت" to "لوکیشن",
        "پروانه" to "لایسنس",
        "حق نشر" to "کپی‌رایت",
        "پیوند" to "لینک",
        "سطل بازیافت" to "سطل زباله",
        "فهرست" to "لیست",
        "نویسه(?!ٔ عام)" to "کاراکتر (نویسهٔ عام = wildcard stays)",
        "زیست‌سنجی" to "اثر انگشت یا چهره",
        "رمزگذاری" to "رمزنگاری",
        "قلم" to "فونت",
        "بارگیری" to "دانلود",
        "واژه" to "کلمه",
        "مطابقت" to "جور بودن",
        "تطبیق" to "جور شدن",
        "واگرد" to "برگردان",
        "مخاطبین" to "مخاطب‌ها",
        "مخاطبان" to "مخاطب‌ها",
        "اکنون" to "الان",
        "سپس" to "بعد",
        "همچنان" to "هنوز",
        "پیش از" to "قبل از",
        "پس از" to "بعد از",
        "بسیار" to "خیلی",
        "بنابراین" to "پس",
        "هنگام" to "وقتی",
    ).mapKeys { Regex(it.key) }

    /** Keys that keep the old words on purpose: the settings-search keywords. */
    private fun allowed(name: String) = name.startsWith("sks_")

    /** Every translatable string, plural item and array item of values-fa, keyed by name. */
    private fun persianTexts(): List<Pair<String, String>> {
        val files = File(appRes, "values-fa")
            .listFiles { f -> f.name.startsWith("strings") && f.name.endsWith(".xml") }.orEmpty()
        assertTrue("no Persian string files", files.isNotEmpty())
        val out = mutableListOf<Pair<String, String>>()
        for (file in files.sortedBy { it.name }) {
            val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement.childNodes
            for (i in 0 until nodes.length) {
                val e = nodes.item(i) as? Element ?: continue
                if (e.getAttribute("translatable") == "false") continue
                val name = e.getAttribute("name")
                when (e.tagName) {
                    "string" -> out += name to e.textContent
                    "plurals", "string-array" -> {
                        val items = e.getElementsByTagName("item")
                        for (j in 0 until items.length) out += name to items.item(j).textContent
                    }
                }
            }
        }
        return out
    }

    @Test
    fun `Persian strings use no retired word`() {
        val problems = persianTexts().filterNot { allowed(it.first) }.flatMap { (name, text) ->
            retired.filter { (word, _) -> word.containsMatchIn(text) }
                .map { (word, use) -> "$name: retired word «${word.pattern}», use «$use»" }
        }.distinct()
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun `a retired word is caught and the kept forms are not`() {
        val caught = { text: String -> retired.keys.any { it.containsMatchIn(text) } }
        assertTrue(caught("پیام‌های مسدود"))
        assertTrue(caught("از پشتیبان بازیابی شد"))
        assertFalse(caught("روش ارسال پشتیبانی نمی‌شود"))
        assertFalse(caught("نویسهٔ عام"))
    }
}
