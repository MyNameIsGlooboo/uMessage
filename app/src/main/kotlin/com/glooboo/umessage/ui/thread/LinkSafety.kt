package com.glooboo.umessage.ui.thread

import java.net.IDN

enum class LinkFlag {
    /** bit.ly and the like: the address does not say where the link goes. */
    SHORTENER,

    /** A name written as xn--...: it may show other letters than the ones it is made of. */
    PUNYCODE,

    /** Letters of two alphabets in one name ("paypal" with a Cyrillic "a"). */
    MIXED_SCRIPT,

    /** A number instead of a name (192.168.1.1). */
    RAW_IP,

    /** http:// and not https://: nothing is encrypted. */
    HTTP_ONLY,

    /** Spelled like a bank or brand but not its address (melIat, paypa1, mellat-secure). */
    LOOKALIKE,

    /** "https://paypal.com@other.test": the part before the @ is not the site. */
    USER_INFO,
}

/** The result of [LinkSafety.check]. [lookalikeOf] names the brand when [flags] holds [LinkFlag.LOOKALIKE]. */
data class LinkReport(val flags: List<LinkFlag>, val lookalikeOf: String? = null) {
    val isClean: Boolean get() = flags.isEmpty()

    /** Anything but a plain http:// address makes a link risky enough to lock (I07 setting). */
    val isRisky: Boolean get() = flags.any { it != LinkFlag.HTTP_ONLY }
}

/**
 * I07: offline checks of a link's address. Advice only: every flag is a reason to look twice, never a verdict, and
 * nothing here leaves the phone.
 */
@Suppress("TooManyFunctions")
object LinkSafety {

    private const val MIN_DISTANCE_BRAND = 6
    private const val MIN_PART_BRAND = 4
    private const val MIN_NUMBER_IP = 8
    private const val LABELS_WITH_SECOND_LEVEL = 3
    private const val LABELS_PLAIN = 2
    private val authority = Regex("^(?:([a-zA-Z][a-zA-Z0-9+.-]*)://)?([^/?#]*)")
    private val dottedIp = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")
    private val hexIp = Regex("^0x[0-9a-f]+(\\.0x[0-9a-f]+)*$")
    private val secondLevel = setOf("co", "com", "org", "net", "gov", "edu", "ac", "sch")
    private val confusables = mapOf('0' to 'o', '1' to 'l', '3' to 'e', '4' to 'a', '5' to 's', '$' to 's',
        // Cyrillic and Greek letters that look like Latin ones.
        'а' to 'a', 'е' to 'e', 'о' to 'o', 'р' to 'p', 'с' to 'c', 'у' to 'y', 'х' to 'x', 'і' to 'i', 'ј' to 'j',
        'ѕ' to 's', 'һ' to 'h', 'ԁ' to 'd', 'ɡ' to 'g', 'ο' to 'o', 'α' to 'a', 'ν' to 'v', 'ρ' to 'p', 'τ' to 't',
        'ι' to 'i', 'κ' to 'k', 'ε' to 'e',
    )
    private const val MAX_ASCII = 127
    private val arabicScripts = setOf(Character.UnicodeScript.ARABIC, Character.UnicodeScript.COMMON)
    private val ignorable = setOf('-', '_', '\u200c', '\u200d')

    /** Flags of [uri], in a fixed order. A mail address or an empty value gets none. */
    fun check(uri: String): LinkReport {
        val value = uri.trim()
        if (value.isEmpty() || value.startsWith("mailto:", ignoreCase = true)) return LinkReport(emptyList())
        val parts = authority.find(value) ?: return LinkReport(emptyList())
        val scheme = parts.groupValues[1].lowercase()
        val host = hostOf(parts.groupValues[2])
        val flags = mutableListOf<LinkFlag>()
        if (parts.groupValues[2].contains('@')) flags += LinkFlag.USER_INFO
        if (host.isNotEmpty()) flags += hostFlags(host)
        if (scheme == "http") flags += LinkFlag.HTTP_ONLY
        val brand = if (LinkFlag.RAW_IP in flags) null else lookalike(host)
        if (brand != null) flags += LinkFlag.LOOKALIKE
        return LinkReport(flags.distinct().sortedBy { it.ordinal }, brand)
    }

    /** The host of an authority part ("user@Host.test:8080" -> "host.test"), lower case and without a final dot. */
    private fun hostOf(authorityPart: String): String {
        val afterUser = authorityPart.substringAfterLast('@')
        val host = if (afterUser.startsWith("[")) {
            afterUser.substringAfter('[').substringBefore(']').let { "[$it]" }
        } else {
            afterUser.substringBefore(':')
        }
        return host.trim().trimEnd('.').lowercase()
    }

    private fun hostFlags(host: String): List<LinkFlag> {
        if (isRawIp(host)) return listOf(LinkFlag.RAW_IP)
        val flags = mutableListOf<LinkFlag>()
        val labels = host.split('.')
        if (labels.any { it.startsWith("xn--") }) flags += LinkFlag.PUNYCODE
        if (domain(host) in linkShorteners || linkShorteners.any { host.endsWith(".$it") }) flags += LinkFlag.SHORTENER
        val decoded = labels.map(::decode)
        if (decoded.any { mixesScripts(it) || isForeignScript(it) }) flags += LinkFlag.MIXED_SCRIPT
        return flags
    }

    private fun isRawIp(host: String): Boolean =
        host.startsWith("[") || dottedIp.matches(host) || hexIp.matches(host) ||
            (host.length >= MIN_NUMBER_IP && host.all { it in '0'..'9' })

    private fun decode(label: String): String = if (label.startsWith("xn--")) IDN.toUnicode(label) else label

    /** True when one name holds letters of different alphabets, Persian and Arabic counting as one. */
    private fun mixesScripts(label: String): Boolean =
        label.filter { it.isLetter() }.map { Character.UnicodeScript.of(it.code) }.toSet().size > 1

    /**
     * A whole label in another alphabet can still imitate a Latin name (all-Cyrillic "аpple"-style hosts). Persian and
     * Arabic names are common here and are not flagged; any other non-ASCII letter is.
     */
    private fun isForeignScript(label: String): Boolean = label.any {
        it.isLetter() && it.code > MAX_ASCII && Character.UnicodeScript.of(it.code) !in arabicScripts
    }

    /** The registrable domain: "pay.example.co.uk" -> "example.co.uk", "a.b.example.com" -> "example.com". */
    private fun domain(host: String): String {
        val labels = host.split('.')
        val withSecondLevel = labels.size >= LABELS_WITH_SECOND_LEVEL && labels[labels.size - 2] in secondLevel
        val keep = if (withSecondLevel) LABELS_WITH_SECOND_LEVEL else LABELS_PLAIN
        return labels.takeLast(keep).joinToString(".")
    }

    /** The brand [host] imitates, or null. A real address of the brand, or its name exactly, is never reported. */
    private fun lookalike(host: String): String? {
        if (host.isEmpty() || !host.contains('.')) return null
        val registrable = domain(host)
        val label = registrable.substringBefore('.')
        return linkBrands.firstOrNull { brand ->
            registrable !in brand.official && brand.names.any { name -> imitates(label, name, brand.global) }
        }?.label
    }

    /**
     * The brand's own name on an address that is not its own: fine for a brand that owns its name in every country
     * ([global]), a copy for a bank, whose real address is the only one.
     */
    private fun imitates(label: String, name: String, global: Boolean): Boolean {
        if (label == name) return !global
        val plain = label.filterNot { it in ignorable }
        if (plain == name) return true
        val folded = fold(plain)
        if (folded == name) return true
        val close = name.length >= MIN_DISTANCE_BRAND && EditDistance.between(folded, name) == 1
        val parts = label.split('-', '\u200c').filter { it.isNotEmpty() }
        val named = name.length >= MIN_PART_BRAND && parts.size > 1 && name in parts.map(::fold)
        return close || named
    }

    /** Look-alike digits as letters, and "rn" or "vv" as the letter they imitate. */
    private fun fold(text: String): String =
        text.map { confusables[it] ?: it }.joinToString("").replace("rn", "m").replace("vv", "w")
}
