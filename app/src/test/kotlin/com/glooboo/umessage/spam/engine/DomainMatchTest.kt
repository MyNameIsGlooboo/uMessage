package com.glooboo.umessage.spam.engine

import com.glooboo.umessage.spam.IncomingMessage
import com.glooboo.umessage.spam.ListKind
import com.glooboo.umessage.spam.RuleType
import com.glooboo.umessage.spam.SpamRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** spam-22: the DOMAIN rule type matches the hosts of links in a message body, subdomains included. */
class DomainMatchTest {

    private fun hit(body: String, domain: String, disguised: Boolean = true) =
        DomainMatch.matches(body, domain, disguised)

    @Test fun matchesTheDomainInALink() {
        assertTrue(hit("Claim now https://example.com/win?id=1", "example.com"))
        assertTrue(hit("go to example.com", "example.com"))
        assertTrue(hit("EXAMPLE.COM/Prize", "example.com"))
    }

    @Test fun matchesSubdomains() {
        assertTrue(hit("http://www.example.com/a", "example.com"))
        assertTrue(hit("see login.secure.example.com now", "example.com"))
        assertTrue(hit("see login.secure.example.com now", "secure.example.com"))
    }

    @Test fun doesNotMatchOtherDomains() {
        assertFalse(hit("visit notexample.com", "example.com"))
        assertFalse(hit("visit example.com.evil.net/x", "example.com"))
        assertFalse(hit("visit example.org", "example.com"))
        assertFalse(hit("visit www.example.com", "login.example.com"))
    }

    @Test fun anEmailAddressIsNotALink() {
        assertFalse(hit("write to bob@example.com", "example.com"))
    }

    @Test fun readsDisguisedDotsOnlyWhenAsked() {
        assertTrue(hit("pay at example[.]com", "example.com"))
        assertTrue(hit("pay at example(dot)com", "example.com"))
        assertFalse(hit("pay at example[.]com", "example.com", disguised = false))
    }

    @Test fun readsPersianDigitsAndTextAroundTheLink() {
        assertTrue(hit("لینک: shop123.example.com/۱۲۳ را بزنید", "example.com"))
    }

    @Test fun aPatternIsCleanedLikeItIsStored() {
        assertEquals("example.com", DomainMatch.clean("  https://Example.com/path?q=1 "))
        assertEquals("example.com", DomainMatch.clean("*.example.com"))
        assertEquals("example.com", DomainMatch.clean(".example.com."))
        assertEquals("example.com", DomainMatch.clean("example.com:8080"))
        assertTrue(hit("example.com/x", "HTTPS://Example.com/"))
    }

    @Test fun aPatternWithoutADotNeverMatches() {
        assertFalse(hit("visit example.com", "com"))
        assertFalse(hit("visit example.com", ""))
    }

    @Test fun hostsListsEveryLinkLowerCased() {
        assertEquals(
            listOf("a.example.com", "bit.ly"),
            DomainMatch.hosts("Open A.Example.com or https://bit.ly/x"),
        )
    }

    @Test fun theRuleMatcherUsesItForBlockAndAllowRules() {
        val matcher = RuleMatcher(NumberNormalizer { "IN" })
        val message = IncomingMessage("VM-SHOP", "Offer at deals.example.com and pay at shop[.]test", 1)
        fun rule(kind: ListKind, domain: String) = SpamRule(1, kind, RuleType.DOMAIN, domain)
        assertTrue(matcher.matches(rule(ListKind.BLOCK, "example.com"), message))
        assertTrue(matcher.matches(rule(ListKind.ALLOW, "example.com"), message))
        // A disguised link counts for a block rule but cannot borrow an allowed domain.
        assertTrue(matcher.matches(rule(ListKind.BLOCK, "shop.test"), message))
        assertFalse(matcher.matches(rule(ListKind.ALLOW, "shop.test"), message))
        assertFalse(matcher.matches(rule(ListKind.BLOCK, " "), message))
    }
}
