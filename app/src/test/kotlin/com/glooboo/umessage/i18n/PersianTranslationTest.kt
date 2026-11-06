package com.glooboo.umessage.i18n

import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Checks the Persian strings against the English ones, for the app and the vendored commons: same
 * placeholders (count and type), plurals with quantities one and other, no Arabic ي/ك, no names English does
 * not have, and, for the app's own strings, nothing left untranslated. See docs/translation-fa.md.
 */
class PersianTranslationTest {

    /** Shown in English in every language on purpose (the owner's choice for the privacy policy link). */
    private val englishOnly = setOf("privacy_policy", "privacy_policy_body", "sks_privacy_policy")

    private val appRes = File("src/main/res")
    private val commonsRes = File("../third_party/commons/commons/src/main/res")

    /** Keys are a string's name, name[quantity] for plurals and name#index for arrays. Untranslatable ones are left out. */
    private fun load(file: File): Map<String, String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val out = LinkedHashMap<String, String>()
        val nodes = doc.documentElement.childNodes
        for (i in 0 until nodes.length) {
            val e = nodes.item(i) as? Element ?: continue
            if (e.getAttribute("translatable") == "false") continue
            val name = e.getAttribute("name")
            val items = e.getElementsByTagName("item")
            when (e.tagName) {
                "string" -> out[name] = e.textContent
                "plurals" -> for (j in 0 until items.length) {
                    val item = items.item(j) as Element
                    out["$name[${item.getAttribute("quantity")}]"] = item.textContent
                }
                "string-array" -> for (j in 0 until items.length) out["$name#$j"] = items.item(j).textContent
            }
        }
        return out
    }

    private fun strings(dir: File): Map<String, String> {
        val files = dir.listFiles { f -> f.name.startsWith("strings") && f.name.endsWith(".xml") }.orEmpty()
        assertTrue("no string files in $dir", files.isNotEmpty())
        return files.sortedBy { it.name }.fold(LinkedHashMap()) { all, f -> all.apply { putAll(load(f)) } }
    }

    private val placeholder = Regex("""%(\d+\$)?[-#+ 0,(]*\d*(\.\d+)?[a-zA-Z%]""")

    /** Sorted, so Persian may put them in another order but not change their number or types. */
    private fun placeholders(s: String) = placeholder.findAll(s).map { it.value }.sorted().toList()

    private fun pluralName(key: String) = key.takeIf { it.endsWith("]") }?.substringBefore('[')

    private fun problems(resDir: File, requireComplete: Boolean): List<String> {
        val english = strings(File(resDir, "values"))
        val persian = strings(File(resDir, "values-fa"))
        val problems = mutableListOf<String>()
        for ((key, fa) in persian) {
            if (fa.any { it == 'ي' || it == 'ك' }) problems += "$key: Arabic ي or ك, use ی and ک"
            val en = english[key] ?: pluralName(key)?.let { english["$it[other]"] }
            when {
                en == null -> problems += "$key: not in English"
                placeholders(en) != placeholders(fa) ->
                    problems += "$key: placeholders ${placeholders(fa)}, English has ${placeholders(en)}"
            }
        }
        persian.keys.mapNotNull { pluralName(it) }.toSet().forEach { name ->
            if ("$name[one]" !in persian || "$name[other]" !in persian) problems += "$name: needs quantities one and other"
        }
        if (requireComplete) {
            english.keys.filter { it !in englishOnly }.forEach { key ->
                val wanted = pluralName(key)?.let { "$it[other]" } ?: key
                if (wanted !in persian) problems += "$key: no Persian translation"
            }
        }
        return problems.distinct()
    }

    @Test
    fun `app Persian strings match English and are complete`() {
        val problems = problems(appRes, requireComplete = true)
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun `commons Persian strings match English`() {
        val problems = problems(commonsRes, requireComplete = false)
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }
}
