package com.glooboo.umessage.spam.engine

import com.glooboo.umessage.spam.TextNormalizer

/**
 * The DOMAIN rule type: whether a message body links to a domain. A link's host matches a rule domain when it is that
 * domain or one of its subdomains ("www.example.com" and "a.b.example.com" match "example.com"; "notexample.com" and
 * "example.com.evil.net" do not).
 */
object DomainMatch {

    /** A dot, also written to dodge link detection: "[.]", "(.)", "{.}", "[dot]", "(dot)". Every bracket is escaped. */
    private const val DISGUISED_DOT = "(?:\\.|\\[\\.\\]|\\(\\.\\)|\\{\\.\\}|\\[dot\\]|\\(dot\\))"
    private const val LABEL = "[a-z0-9](?:[a-z0-9\\-]*[a-z0-9])?"

    private fun hostRegex(dot: String) = Regex(
        "(?<![a-z0-9@.\\-])" + LABEL + "(?:" + dot + LABEL + ")*" + dot + "[a-z]{2,24}(?![a-z0-9\\-])",
        RegexOption.IGNORE_CASE,
    )

    private val plainHost = hostRegex("\\.")
    private val anyHost = hostRegex(DISGUISED_DOT)
    private val disguisedDot = Regex(DISGUISED_DOT, RegexOption.IGNORE_CASE)

    /**
     * The hosts written in [body], lower case. With [disguised] a host written with a disguised dot ("example[.]com")
     * counts too. An address after an "@" is an e-mail, not a link, and is skipped.
     */
    fun hosts(body: String, disguised: Boolean = true): List<String> {
        val text = TextNormalizer.fold(body)
        val regex = if (disguised) anyHost else plainHost
        return regex.findAll(text).map { it.value.lowercase().replace(disguisedDot, ".") }.toList()
    }

    /** Whether [body] links to [pattern] or a subdomain of it. A rule domain without a dot never matches. */
    fun matches(body: String, pattern: String, disguised: Boolean = true): Boolean {
        val domain = clean(pattern)
        if (!domain.contains('.')) return false
        return hosts(body, disguised).any { it == domain || it.endsWith(".$domain") }
    }

    /**
     * A rule's domain as stored: lower case, without scheme, "www." is kept (it is a subdomain like any other), path,
     * port, a leading "*." or ".", or a trailing dot. "https://Example.com/a?b" becomes "example.com".
     */
    fun clean(pattern: String): String {
        var text = TextNormalizer.fold(pattern).trim().lowercase()
        text = text.replace(disguisedDot, ".")
        text = text.substringAfter("://")
        text = text.substringBefore('/').substringBefore('?').substringBefore('#')
        text = text.substringAfterLast('@').substringBefore(':')
        return text.trimStart('*', '.').trimEnd('.')
    }
}
