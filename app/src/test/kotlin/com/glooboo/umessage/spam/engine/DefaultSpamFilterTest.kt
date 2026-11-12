package com.glooboo.umessage.spam.engine

import com.glooboo.umessage.spam.DecisionReason
import com.glooboo.umessage.spam.FilterDecision
import com.glooboo.umessage.spam.IncomingMessage
import com.glooboo.umessage.spam.ListKind
import com.glooboo.umessage.spam.RuleType
import com.glooboo.umessage.spam.SpamRule
import com.glooboo.umessage.spam.SpamSettings
import com.glooboo.umessage.spam.UnknownSenderMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultSpamFilterTest {

    private val normalizer = NumberNormalizer { "IN" }

    private class FakeContacts(
        private val numbers: Set<String>,
        private val normalizer: NumberNormalizer,
        private val readable: Boolean = true,
        private val sent: Set<String> = emptySet(),
        private val sentBroken: Boolean = false,
    ) : ContactChecker {
        override fun isContact(address: String) =
            readable && normalizer.normalize(address) in numbers.map(normalizer::normalize)

        override fun canReadContacts() = readable

        override fun sentTo(address: String): ContactLookup {
            if (sentBroken) error("sms provider busy")
            return if (sent.any { normalizer.sameAddress(it, address) }) ContactLookup.FOUND else ContactLookup.NOT_FOUND
        }
    }

    private class FakeLegacy(
        val blockedNumbers: Set<String> = emptySet(),
        val keywords: List<String> = emptyList(),
        val unknown: Boolean = false,
    ) : LegacyBlocklist {
        override fun isNumberBlocked(address: String) = address in blockedNumbers
        override fun blockedKeywords() = keywords
        override fun blockUnknownNumbers() = unknown
    }

    private fun filter(
        rules: List<SpamRule> = emptyList(),
        settings: SpamSettings = SpamSettings(mode = UnknownSenderMode.OFF),
        contacts: Set<String> = emptySet(),
        legacy: LegacyBlocklist = NoLegacyBlocklist,
        contactsReadable: Boolean = true,
        regions: Map<Int, String> = emptyMap(),
        sent: Set<String> = emptySet(),
        sentBroken: Boolean = false,
    ) = DefaultSpamFilter(
        { rules }, { settings }, FakeContacts(contacts, normalizer, contactsReadable, sent, sentBroken), normalizer, legacy,
        regionForSubscription = { regions[it] },
    )

    private fun msg(address: String = "+919876543210", body: String = "hello", sub: Int = 1) =
        IncomingMessage(address, body, sub)

    private fun rule(
        kind: ListKind,
        type: RuleType,
        pattern: String,
        sub: Int? = null,
        id: Long = 1,
    ) = SpamRule(id = id, kind = kind, type = type, pattern = pattern, subscriptionId = sub)

    private fun assertBlock(reason: DecisionReason, ruleId: Long?, decision: FilterDecision) {
        assertTrue("expected Block but was $decision", decision is FilterDecision.Block)
        decision as FilterDecision.Block
        assertEquals(reason, decision.reason)
        assertEquals(ruleId, decision.ruleId)
    }

    private fun assertAllow(reason: DecisionReason, decision: FilterDecision) {
        assertTrue("expected Allow but was $decision", decision is FilterDecision.Allow)
        assertEquals(reason, decision.reason)
    }

    @Test fun allowRuleDecisionNamesTheRule() {
        val f = filter(rules = listOf(rule(ListKind.ALLOW, RuleType.NUMBER, "09876543210", id = 7)))
        assertEquals(FilterDecision.Allow(DecisionReason.ALLOW_RULE, 7L), f.evaluate(msg()))
    }

    @Test fun allowRuleWinsOverBlockRule() {
        val f = filter(
            rules = listOf(
                rule(ListKind.BLOCK, RuleType.NUMBER, "+919876543210", id = 1),
                rule(ListKind.ALLOW, RuleType.NUMBER, "09876543210", id = 2),
            )
        )
        assertAllow(DecisionReason.ALLOW_RULE, f.evaluate(msg()))
    }

    @Test fun allowKeywordRuleWinsOverBlockKeyword() {
        val f = filter(
            rules = listOf(
                rule(ListKind.BLOCK, RuleType.KEYWORD, "offer", id = 1),
                rule(ListKind.ALLOW, RuleType.KEYWORD, "bank offer", id = 2),
            )
        )
        assertAllow(DecisionReason.ALLOW_RULE, f.evaluate(msg(body = "Your Bank Offer is here")))
    }

    @Test fun allowRuleWinsOverLegacyBlock() {
        val f = filter(
            rules = listOf(rule(ListKind.ALLOW, RuleType.NUMBER, "+919876543210")),
            legacy = FakeLegacy(blockedNumbers = setOf("+919876543210")),
        )
        assertAllow(DecisionReason.ALLOW_RULE, f.evaluate(msg()))
    }

    @Test fun blockNumberRuleMatchesAcrossFormats() {
        val f = filter(rules = listOf(rule(ListKind.BLOCK, RuleType.NUMBER, "0091 98765 43210", id = 7)))
        assertBlock(DecisionReason.BLOCK_RULE, 7, f.evaluate(msg(address = "09876543210")))
        assertBlock(DecisionReason.BLOCK_RULE, 7, f.evaluate(msg(address = "+91-9876543210")))
        assertAllow(DecisionReason.MODE_OFF, f.evaluate(msg(address = "+919876543211")))
    }

    @Test fun blockPrefixRuleWithInternationalPattern() {
        val f = filter(rules = listOf(rule(ListKind.BLOCK, RuleType.PREFIX, "+91 98765", id = 3)))
        assertBlock(DecisionReason.BLOCK_RULE, 3, f.evaluate(msg(address = "09876512345")))
        assertAllow(DecisionReason.MODE_OFF, f.evaluate(msg(address = "+919000000000")))
    }

    @Test fun blockPrefixRuleWithRawTelemarketerPrefix() {
        val f = filter(rules = listOf(rule(ListKind.BLOCK, RuleType.PREFIX, "140", id = 4)))
        assertBlock(DecisionReason.BLOCK_RULE, 4, f.evaluate(msg(address = "1409876543")))
    }

    @Test fun blockSenderIdRuleIsCaseInsensitive() {
        val f = filter(rules = listOf(rule(ListKind.BLOCK, RuleType.SENDER_ID, "vm-hdfcbk", id = 5)))
        assertBlock(DecisionReason.BLOCK_RULE, 5, f.evaluate(msg(address = "VM-HDFCBK")))
        assertBlock(DecisionReason.BLOCK_RULE, 5, f.evaluate(msg(address = "vm-hdfcbk")))
        assertAllow(DecisionReason.MODE_OFF, f.evaluate(msg(address = "VM-ICICIB")))
    }

    @Test fun senderIdRuleWithoutOperatorPrefixMatchesAnyOperator() {
        val f = filter(rules = listOf(rule(ListKind.BLOCK, RuleType.SENDER_ID, "HDFCBK", id = 5)))
        assertBlock(DecisionReason.BLOCK_RULE, 5, f.evaluate(msg(address = "AD-HDFCBK")))
    }

    @Test fun senderIdRuleDoesNotMatchPhoneNumbers() {
        val f = filter(rules = listOf(rule(ListKind.BLOCK, RuleType.SENDER_ID, "56161")))
        assertAllow(DecisionReason.MODE_OFF, f.evaluate(msg(address = "+919876543210")))
    }

    @Test fun blockKeywordRule() {
        val f = filter(rules = listOf(rule(ListKind.BLOCK, RuleType.KEYWORD, "lottery", id = 6)))
        assertBlock(DecisionReason.BLOCK_RULE, 6, f.evaluate(msg(body = "You won the LOTTERY!")))
        assertAllow(DecisionReason.MODE_OFF, f.evaluate(msg(body = "see you soon")))
    }

    @Test fun blockRegexRule() {
        val f = filter(rules = listOf(rule(ListKind.BLOCK, RuleType.REGEX, "win\\s+\\$\\d+", id = 8)))
        assertBlock(DecisionReason.BLOCK_RULE, 8, f.evaluate(msg(body = "Win  $500 now")))
        assertAllow(DecisionReason.MODE_OFF, f.evaluate(msg(body = "no prize")))
    }

    @Test fun invalidRegexIsIgnored() {
        val f = filter(rules = listOf(rule(ListKind.BLOCK, RuleType.REGEX, "([unclosed")))
        assertAllow(DecisionReason.MODE_OFF, f.evaluate(msg()))
    }

    @Test fun perSimScopeOnlyAppliesToThatSim() {
        val f = filter(rules = listOf(rule(ListKind.BLOCK, RuleType.KEYWORD, "promo", sub = 2, id = 9)))
        assertBlock(DecisionReason.BLOCK_RULE, 9, f.evaluate(msg(body = "big promo", sub = 2)))
        assertAllow(DecisionReason.MODE_OFF, f.evaluate(msg(body = "big promo", sub = 1)))
    }

    @Test fun perSimAllowScope() {
        val f = filter(
            rules = listOf(rule(ListKind.ALLOW, RuleType.NUMBER, "+919876543210", sub = 1)),
            settings = SpamSettings(mode = UnknownSenderMode.STRICT),
        )
        assertAllow(DecisionReason.ALLOW_RULE, f.evaluate(msg(sub = 1)))
        assertBlock(DecisionReason.UNKNOWN_SENDER, null, f.evaluate(msg(sub = 2)))
    }

    @Test fun bothSimsRuleAppliesEverywhere() {
        val f = filter(rules = listOf(rule(ListKind.BLOCK, RuleType.KEYWORD, "promo", sub = null, id = 9)))
        assertBlock(DecisionReason.BLOCK_RULE, 9, f.evaluate(msg(body = "promo", sub = 1)))
        assertBlock(DecisionReason.BLOCK_RULE, 9, f.evaluate(msg(body = "promo", sub = 2)))
    }

    @Test fun blockRuleBeatsContact() {
        val f = filter(
            rules = listOf(rule(ListKind.BLOCK, RuleType.NUMBER, "+919876543210", id = 1)),
            contacts = setOf("+919876543210"),
        )
        assertBlock(DecisionReason.BLOCK_RULE, 1, f.evaluate(msg()))
    }

    @Test fun contactIsAllowedInStrictMode() {
        val f = filter(settings = SpamSettings(mode = UnknownSenderMode.STRICT), contacts = setOf("9876543210"))
        assertAllow(DecisionReason.CONTACT, f.evaluate(msg(address = "+91 98765 43210")))
    }

    @Test fun legacyBlockedNumberBlocks() {
        val f = filter(legacy = FakeLegacy(blockedNumbers = setOf("+919876543210")))
        assertBlock(DecisionReason.BLOCK_RULE, null, f.evaluate(msg()))
    }

    @Test fun legacyBlockedKeywordBlocks() {
        val f = filter(legacy = FakeLegacy(keywords = listOf("Casino")))
        assertBlock(DecisionReason.BLOCK_RULE, null, f.evaluate(msg(body = "play at the casino")))
    }

    @Test fun legacyBlockUnknownBlocksNonContactsOnly() {
        val f = filter(legacy = FakeLegacy(unknown = true), contacts = setOf("+919876543210"))
        assertAllow(DecisionReason.CONTACT, f.evaluate(msg()))
        assertBlock(DecisionReason.UNKNOWN_SENDER, null, f.evaluate(msg(address = "+919999999999")))
    }

    // ---- Modes (T1 / SE-06 / SU-29): Strict has no exemptions, Smart lets codes through ----

    @Test fun strictBlocksCodesToo() {
        val f = filter(settings = SpamSettings(mode = UnknownSenderMode.STRICT))
        assertBlock(DecisionReason.UNKNOWN_SENDER, null, f.evaluate(msg(address = "VM-BANK", body = "Your OTP is 482915")))
        assertBlock(DecisionReason.UNKNOWN_SENDER, null, f.evaluate(msg(address = "+447121234567", body = "رمز پویا: ۱۲۳۴۵۶")))
        assertBlock(
            DecisionReason.UNKNOWN_SENDER, null,
            f.evaluate(msg(address = "VM-SHOP", body = "Your code is 123456\n\n@example.com #123456"))
        )
    }

    @Test fun theModeAloneDecidesAboutCodes() {
        val strict = filter(settings = SpamSettings(mode = UnknownSenderMode.STRICT))
        assertBlock(DecisionReason.UNKNOWN_SENDER, null, strict.evaluate(msg(address = "VM-BANK", body = "Your OTP is 482915")))
        val smart = filter(settings = SpamSettings(mode = UnknownSenderMode.SMART))
        assertAllow(DecisionReason.OTP_EXEMPTION, smart.evaluate(msg(address = "VM-BANK", body = "Your OTP is 482915")))
        val off = filter(settings = SpamSettings(mode = UnknownSenderMode.OFF))
        assertAllow(DecisionReason.MODE_OFF, off.evaluate(msg(body = "Your OTP is 482915")))
    }

    @Test fun smartLetsALabelledPasswordThrough() {
        val smart = filter(settings = SpamSettings(mode = UnknownSenderMode.SMART))
        assertAllow(DecisionReason.OTP_EXEMPTION, smart.evaluate(msg(address = "VM-BANK", body = "Password: x7Kq2m")))
        val username = smart.evaluate(msg(address = "VM-BANK", body = "username: sara22"))
        assertBlock(DecisionReason.UNKNOWN_SENDER, null, username)
    }

    @Test fun smartAndStrictDifferOnlyOnCodes() {
        val strict = filter(settings = SpamSettings(mode = UnknownSenderMode.STRICT))
        val smart = filter(settings = SpamSettings(mode = UnknownSenderMode.SMART))
        val code = msg(body = "Your verification code is 482915")
        assertBlock(DecisionReason.UNKNOWN_SENDER, null, strict.evaluate(code))
        assertAllow(DecisionReason.OTP_EXEMPTION, smart.evaluate(code))
        assertBlock(DecisionReason.UNKNOWN_SENDER, null, strict.evaluate(msg(body = "buy now")))
        assertBlock(DecisionReason.UNKNOWN_SENDER, null, smart.evaluate(msg(body = "buy now")))
    }

    @Test fun smartDoesNotAllowNonOtpText() {
        val f = filter(settings = SpamSettings(mode = UnknownSenderMode.SMART))
        assertBlock(DecisionReason.UNKNOWN_SENDER, null, f.evaluate(msg(body = "Call 123456789 now for prizes")))
    }

    @Test fun otpNeedsFourToEightDigits() {
        val f = filter(settings = SpamSettings(mode = UnknownSenderMode.SMART))
        assertAllow(DecisionReason.OTP_EXEMPTION, f.evaluate(msg(body = "Your code is 1234")))
        assertBlock(DecisionReason.UNKNOWN_SENDER, null, f.evaluate(msg(body = "Your code is 123")))
        assertBlock(DecisionReason.UNKNOWN_SENDER, null, f.evaluate(msg(body = "Your code is 123456789")))
        assertAllow(DecisionReason.OTP_EXEMPTION, f.evaluate(msg(body = "Verification code: 1234567")))
        assertAllow(DecisionReason.OTP_EXEMPTION, f.evaluate(msg(body = "password 12345678")))
    }

    @Test fun strictBlocksUnknown() {
        val f = filter(settings = SpamSettings(mode = UnknownSenderMode.STRICT))
        assertBlock(DecisionReason.UNKNOWN_SENDER, null, f.evaluate(msg()))
    }

    @Test fun smartAllowsOtpAndBlocksOthers() {
        val f = filter(settings = SpamSettings(mode = UnknownSenderMode.SMART))
        assertAllow(DecisionReason.OTP_EXEMPTION, f.evaluate(msg(body = "123456 is your login code")))
        assertBlock(DecisionReason.UNKNOWN_SENDER, null, f.evaluate(msg(body = "buy now")))
    }

    @Test fun offAllowsUnknown() {
        val f = filter(settings = SpamSettings(mode = UnknownSenderMode.OFF))
        assertAllow(DecisionReason.MODE_OFF, f.evaluate(msg()))
    }

    @Test fun alphanumericSenderUnknownBlockedInStrict() {
        val f = filter(settings = SpamSettings(mode = UnknownSenderMode.STRICT))
        assertBlock(DecisionReason.UNKNOWN_SENDER, null, f.evaluate(msg(address = "amazon")))
    }

    @Test fun alphanumericSenderAllowRule() {
        val f = filter(
            rules = listOf(rule(ListKind.ALLOW, RuleType.SENDER_ID, "AMAZON")),
            settings = SpamSettings(mode = UnknownSenderMode.STRICT),
        )
        assertAllow(DecisionReason.ALLOW_RULE, f.evaluate(msg(address = "amazon")))
    }

    @Test fun alphanumericSenderIsNeverAContact() {
        val f = filter(settings = SpamSettings(mode = UnknownSenderMode.STRICT), contacts = setOf("AMAZON"))
        assertBlock(DecisionReason.UNKNOWN_SENDER, null, f.evaluate(msg(address = "Amazon")))
    }

    @Test fun persianOtpIsLetThroughInSmart() {
        val f = filter(settings = SpamSettings(mode = UnknownSenderMode.SMART))
        assertAllow(DecisionReason.OTP_EXEMPTION, f.evaluate(msg(address = "BANK", body = "رمز پویا: ۱۲۳۴۵")))
    }

    // Owner decision Q5: a verification code from an unknown sender is delivered even when the message has a link.
    @Test fun smartAllowsOtpWithLink() {
        val f = filter(settings = SpamSettings(mode = UnknownSenderMode.SMART))
        assertAllow(
            DecisionReason.OTP_EXEMPTION,
            f.evaluate(msg(body = "Your verification code 482915, confirm at bit.ly/x1y2"))
        )
        assertAllow(
            DecisionReason.OTP_EXEMPTION,
            f.evaluate(msg(body = "Your verification code 482915, confirm at https://example.test"))
        )
    }

    @Test fun smartAllowsCodeWithBareDomain() {
        val f = filter(settings = SpamSettings(mode = UnknownSenderMode.SMART))
        val bareDomain = msg(body = "Your code 482913. Verify at mybank-secure.com")
        assertAllow(DecisionReason.OTP_EXEMPTION, f.evaluate(bareDomain))
        assertAllow(
            DecisionReason.OTP_EXEMPTION,
            f.evaluate(msg(address = "+447121234567", body = "رمز پویا ۱۲۳۴۵۶ را در shaparak-pay.ir وارد کنید"))
        )
        assertAllow(DecisionReason.OTP_EXEMPTION, f.evaluate(msg(body = "Code 482913, see mellat[.]ir")))
    }

    @Test fun strictStillHoldsCodeWithLink() {
        val f = filter(settings = SpamSettings(mode = UnknownSenderMode.STRICT))
        assertBlock(
            DecisionReason.UNKNOWN_SENDER, null,
            f.evaluate(msg(body = "Your verification code 482915, confirm at https://example.test"))
        )
    }

    @Test fun smartStillHoldsALinkWithAPrizeWordAndNoCode() {
        val f = filter(settings = SpamSettings(mode = UnknownSenderMode.SMART))
        assertBlock(
            DecisionReason.UNKNOWN_SENDER, null,
            f.evaluate(msg(body = "You won a prize! Claim at https://example.test/win"))
        )
        assertBlock(
            DecisionReason.UNKNOWN_SENDER, null,
            f.evaluate(msg(body = "Prize code 482915, claim at https://example.test/win 50% off"))
        )
    }

    @Test fun smartStillHoldsAPhoneNumberWithALinkAndABareNumberWithoutKeyword() {
        val f = filter(settings = SpamSettings(mode = UnknownSenderMode.SMART))
        assertBlock(
            DecisionReason.UNKNOWN_SENDER, null,
            f.evaluate(msg(body = "Call 0800 123 4567 or see https://example.test/help"))
        )
        assertBlock(DecisionReason.UNKNOWN_SENDER, null, f.evaluate(msg(body = "482915 https://example.test/x")))
    }

    @Test fun codeWithLinkFallsThroughToModeOff() {
        val f = filter(settings = SpamSettings(mode = UnknownSenderMode.OFF))
        assertAllow(DecisionReason.MODE_OFF, f.evaluate(msg(body = "Your code 482915, confirm at https://example.test")))
    }

    @Test fun smartAllowsOriginBoundCode() {
        val f = filter(settings = SpamSettings(mode = UnknownSenderMode.SMART))
        assertAllow(
            DecisionReason.OTP_EXEMPTION,
            f.evaluate(msg(address = "VM-SHOP", body = "Your code is 123456\n\n@example.com #123456"))
        )
        assertAllow(
            DecisionReason.OTP_EXEMPTION,
            f.evaluate(msg(address = "VM-SHOP", body = "Your code is 123456\n\n@www.example.com #123456"))
        )
    }

    @Test fun smartAllowsOriginBoundCodeWithoutOtherLinks() {
        val f = filter(settings = SpamSettings(mode = UnknownSenderMode.SMART))
        assertAllow(
            DecisionReason.OTP_EXEMPTION,
            f.evaluate(msg(body = "123456 is your login code\n@www.example.com #123456"))
        )
    }

    @Test fun smartAllowsPersianCodeWithoutLink() {
        val f = filter(settings = SpamSettings(mode = UnknownSenderMode.SMART))
        assertAllow(
            DecisionReason.OTP_EXEMPTION,
            f.evaluate(msg(address = "+447121234567", body = "کد تایید شما: ۴۸۲۹۱۵"))
        )
        // SE-04: Arabic letter forms, as some banks write them.
        assertAllow(
            DecisionReason.OTP_EXEMPTION,
            f.evaluate(msg(address = "+447121234567", body = "كد تاييد شما: ۴۸۲۹۱۵"))
        )
    }

    @Test fun smartBlocksPromoCodes() {
        val f = filter(settings = SpamSettings(mode = UnknownSenderMode.SMART))
        assertBlock(DecisionReason.UNKNOWN_SENDER, null, f.evaluate(msg(body = "Use promo code 2025 for 50% off today!")))
        assertBlock(
            DecisionReason.UNKNOWN_SENDER, null,
            f.evaluate(msg(address = "+447121234567", body = "کد تخفیف ۵۰۲۰ برای خرید اول شما. لغو۱۱"))
        )
        assertAllow(DecisionReason.OTP_EXEMPTION, f.evaluate(msg(body = "Your Bank code: 483920")))
    }

    // ---- SU-28: the old "block unknown numbers" switch ----

    @Test fun legacyBlockUnknownKeepsSmartCodeExemption() {
        val f = filter(settings = SpamSettings(mode = UnknownSenderMode.SMART), legacy = FakeLegacy(unknown = true))
        assertAllow(DecisionReason.OTP_EXEMPTION, f.evaluate(msg(body = "Your code is 482913")))
        assertBlock(DecisionReason.UNKNOWN_SENDER, null, f.evaluate(msg(body = "buy now")))
    }

    @Test fun legacyBlockUnknownTurnsOffModeIntoStrict() {
        val f = filter(settings = SpamSettings(mode = UnknownSenderMode.OFF), legacy = FakeLegacy(unknown = true))
        assertBlock(DecisionReason.UNKNOWN_SENDER, null, f.evaluate(msg(body = "Your code is 482913")))
    }

    // ---- Numbers the user has written to count as known ----

    @Test fun numberTheUserTextedIsKnown() {
        val f = filter(settings = SpamSettings(mode = UnknownSenderMode.STRICT), sent = setOf("09876543210"))
        assertAllow(DecisionReason.KNOWN_CONVERSATION, f.evaluate(msg(address = "+919876543210", body = "buy now")))
        assertBlock(DecisionReason.UNKNOWN_SENDER, null, f.evaluate(msg(address = "+919999999999", body = "buy now")))
    }

    @Test fun contactWinsOverSentHistory() {
        val f = filter(
            settings = SpamSettings(mode = UnknownSenderMode.STRICT),
            contacts = setOf("+919876543210"),
            sent = setOf("+919876543210"),
        )
        assertAllow(DecisionReason.CONTACT, f.evaluate(msg()))
    }

    @Test fun blockRuleBeatsSentHistory() {
        val f = filter(
            rules = listOf(rule(ListKind.BLOCK, RuleType.NUMBER, "+919876543210", id = 21)),
            settings = SpamSettings(mode = UnknownSenderMode.STRICT),
            sent = setOf("+919876543210"),
        )
        assertBlock(DecisionReason.BLOCK_RULE, 21, f.evaluate(msg()))
    }

    @Test fun unreadableSentHistoryDelivers() {
        val f = filter(settings = SpamSettings(mode = UnknownSenderMode.STRICT), sentBroken = true)
        assertAllow(DecisionReason.CONTACTS_UNAVAILABLE, f.evaluate(msg(body = "buy now")))
        // Sender ids are never written to, so they are filtered as usual.
        assertBlock(DecisionReason.UNKNOWN_SENDER, null, f.evaluate(msg(address = "AX-PROMOS")))
    }

    @Test fun sentHistoryIsNotReadWhenModeIsOff() {
        val f = filter(settings = SpamSettings(mode = UnknownSenderMode.OFF), sentBroken = true)
        assertAllow(DecisionReason.MODE_OFF, f.evaluate(msg()))
    }

    // ---- T2 / SE-12 / SE-15: text is normalised before keyword and regex rules ----

    @Test fun keywordRuleSeesThroughArabicLetterForms() {
        val f = filter(rules = listOf(rule(ListKind.BLOCK, RuleType.KEYWORD, "وام فوری", id = 31)))
        assertBlock(DecisionReason.BLOCK_RULE, 31, f.evaluate(msg(body = "وام فوري بدون ضامن")))
        assertBlock(DecisionReason.BLOCK_RULE, 31, f.evaluate(msg(body = "وام فو\u200Cری با سود کم")))
        assertBlock(DecisionReason.BLOCK_RULE, 31, f.evaluate(msg(body = "وام فـــوری")))
    }

    @Test fun arabicFormKeywordMatchesPersianText() {
        val f = filter(rules = listOf(rule(ListKind.BLOCK, RuleType.KEYWORD, "كازينو", id = 32)))
        assertBlock(DecisionReason.BLOCK_RULE, 32, f.evaluate(msg(body = "بهترین کازینو آنلاین")))
    }

    @Test fun blockKeywordSeesThroughSpacingAndLeetspeak() {
        val f = filter(rules = listOf(rule(ListKind.BLOCK, RuleType.KEYWORD, "free", id = 33)))
        assertBlock(DecisionReason.BLOCK_RULE, 33, f.evaluate(msg(body = "F R E E gift inside")))
        assertBlock(DecisionReason.BLOCK_RULE, 33, f.evaluate(msg(body = "fr33 prize inside")))
        assertAllow(DecisionReason.MODE_OFF, f.evaluate(msg(body = "of reeds and rivers")))
    }

    @Test fun allowKeywordDoesNotSeeThroughObfuscation() {
        val f = filter(
            rules = listOf(rule(ListKind.ALLOW, RuleType.KEYWORD, "bank", id = 34)),
            settings = SpamSettings(mode = UnknownSenderMode.STRICT),
        )
        assertAllow(DecisionReason.ALLOW_RULE, f.evaluate(msg(body = "Your BANK statement")))
        assertBlock(DecisionReason.UNKNOWN_SENDER, null, f.evaluate(msg(body = "b a n k prize, claim now")))
    }

    @Test fun legacyKeywordSeesThroughObfuscation() {
        val f = filter(legacy = FakeLegacy(keywords = listOf("casino")))
        assertBlock(DecisionReason.BLOCK_RULE, null, f.evaluate(msg(body = "C-A-S-I-N-O bonus")))
        assertBlock(DecisionReason.BLOCK_RULE, null, f.evaluate(msg(body = "c4sin0 bonus")))
    }

    @Test fun regexRuleIgnoresCaseInEveryScript() {
        val f = filter(rules = listOf(rule(ListKind.BLOCK, RuleType.REGEX, "бесплатно", id = 35)))
        assertBlock(DecisionReason.BLOCK_RULE, 35, f.evaluate(msg(body = "БЕСПЛАТНО приз!")))
        val keyword = filter(rules = listOf(rule(ListKind.BLOCK, RuleType.KEYWORD, "бесплатно", id = 36)))
        assertBlock(DecisionReason.BLOCK_RULE, 36, keyword.evaluate(msg(body = "БЕСПЛАТНО приз!")))
    }

    @Test fun regexRuleSeesFoldedText() {
        val f = filter(rules = listOf(rule(ListKind.BLOCK, RuleType.REGEX, "وام\\s*فوري", id = 37)))
        assertBlock(DecisionReason.BLOCK_RULE, 37, f.evaluate(msg(body = "وام فوری")))
        val digits = filter(rules = listOf(rule(ListKind.BLOCK, RuleType.REGEX, "لغو\\s*11", id = 38)))
        assertBlock(DecisionReason.BLOCK_RULE, 38, digits.evaluate(msg(body = "خرید اقساطی. لغو ۱۱")))
    }

    // ---- QD-05: odd cases ----

    @Test fun allowKeywordMatchesAnywhereInTheBody() {
        val f = filter(
            rules = listOf(rule(ListKind.ALLOW, RuleType.KEYWORD, "bank", id = 41)),
            settings = SpamSettings(mode = UnknownSenderMode.STRICT),
        )
        // An allow keyword is a strong tool: it lets in a stranger's text that mentions the word.
        assertAllow(DecisionReason.ALLOW_RULE, f.evaluate(msg(body = "Spammer: win now! Your bank is waiting")))
    }

    @Test fun blankSenderIsDeliveredInEveryBlockingMode() {
        for (mode in listOf(UnknownSenderMode.STRICT, UnknownSenderMode.SMART)) {
            val f = filter(settings = SpamSettings(mode = mode), sent = setOf("+919876543210"))
            assertAllow(DecisionReason.CONTACTS_UNAVAILABLE, f.evaluate(msg(address = "", body = "buy now")))
            assertAllow(DecisionReason.CONTACTS_UNAVAILABLE, f.evaluate(msg(address = "   ", body = "buy now")))
        }
    }

    @Test fun shortCodeSenderIsFilteredLikeANumber() {
        val f = filter(settings = SpamSettings(mode = UnknownSenderMode.STRICT))
        assertBlock(DecisionReason.UNKNOWN_SENDER, null, f.evaluate(msg(address = "30001234", body = "خرید اقساطی")))
        val smart = filter(settings = SpamSettings(mode = UnknownSenderMode.SMART))
        assertAllow(DecisionReason.OTP_EXEMPTION, smart.evaluate(msg(address = "1000", body = "رمز پویا: ۵۸۲۳۱۹")))
    }

    @Test fun invisibleOnlyKeywordRuleMatchesNothing() {
        val f = filter(rules = listOf(rule(ListKind.BLOCK, RuleType.KEYWORD, "\u200C", id = 42)))
        assertAllow(DecisionReason.MODE_OFF, f.evaluate(msg(body = "سلام\u200Cعلیکم")))
    }

    @Test fun smartDoesNotAllowPromoWithNumber() {
        val f = filter(settings = SpamSettings(mode = UnknownSenderMode.SMART))
        assertBlock(DecisionReason.UNKNOWN_SENDER, null, f.evaluate(msg(body = "Use code SAVE20, call 12345678")))
    }

    @Test fun strictWithoutContactsPermissionDoesNotBlockNumbers() {
        val f = filter(
            settings = SpamSettings(mode = UnknownSenderMode.STRICT),
            contacts = setOf("+919876543210"),
            contactsReadable = false,
        )
        assertAllow(DecisionReason.CONTACTS_UNAVAILABLE, f.evaluate(msg()))
    }

    @Test fun smartWithoutContactsPermissionDoesNotBlockNumbers() {
        val f = filter(settings = SpamSettings(mode = UnknownSenderMode.SMART), contactsReadable = false)
        assertAllow(DecisionReason.CONTACTS_UNAVAILABLE, f.evaluate(msg(body = "buy now")))
    }

    @Test fun withoutContactsPermissionSenderIdsAreStillFiltered() {
        val f = filter(settings = SpamSettings(mode = UnknownSenderMode.STRICT), contactsReadable = false)
        assertBlock(DecisionReason.UNKNOWN_SENDER, null, f.evaluate(msg(address = "AX-PROMOS")))
    }

    @Test fun withoutContactsPermissionBlockRulesStillApply() {
        val f = filter(
            rules = listOf(rule(ListKind.BLOCK, RuleType.KEYWORD, "lottery", id = 4)),
            settings = SpamSettings(mode = UnknownSenderMode.STRICT),
            contactsReadable = false,
        )
        assertBlock(DecisionReason.BLOCK_RULE, 4, f.evaluate(msg(body = "lottery win")))
    }

    @Test fun legacyBlockUnknownWithoutContactsPermissionDoesNotBlockNumbers() {
        val f = filter(legacy = FakeLegacy(unknown = true), contactsReadable = false)
        assertAllow(DecisionReason.CONTACTS_UNAVAILABLE, f.evaluate(msg()))
    }

    @Test fun numberRuleUsesRegionOfReceivingSim() {
        // Default region is IN; SIM 2 is a British SIM, so "0712..." on SIM 2 is +44712...
        val f = filter(
            rules = listOf(rule(ListKind.BLOCK, RuleType.NUMBER, "+447121234567", id = 11)),
            regions = mapOf(2 to "GB"),
        )
        assertBlock(DecisionReason.BLOCK_RULE, 11, f.evaluate(msg(address = "07121234567", sub = 2)))
        assertAllow(DecisionReason.MODE_OFF, f.evaluate(msg(address = "07121234567", sub = 1)))
    }

    @Test fun numberRuleStillMatchesInDefaultRegion() {
        val f = filter(
            rules = listOf(rule(ListKind.BLOCK, RuleType.NUMBER, "+919876543210", id = 12)),
            regions = mapOf(2 to "GB"),
        )
        assertBlock(DecisionReason.BLOCK_RULE, 12, f.evaluate(msg(address = "09876543210", sub = 2)))
    }

    @Test fun catastrophicRegexRuleIsSkipped() {
        val f = filter(rules = listOf(rule(ListKind.BLOCK, RuleType.REGEX, "(a+)+$", id = 13)))
        assertAllow(DecisionReason.MODE_OFF, f.evaluate(msg(body = "a".repeat(40) + "!")))
    }

    @Test fun regexOnlySeesCappedBody() {
        val f = filter(rules = listOf(rule(ListKind.BLOCK, RuleType.REGEX, "tail-marker", id = 14)))
        val body = "x".repeat(com.glooboo.umessage.spam.RegexGuard.MAX_BODY_CHARS) + "tail-marker"
        assertAllow(DecisionReason.MODE_OFF, f.evaluate(msg(body = body)))
        assertBlock(DecisionReason.BLOCK_RULE, 14, f.evaluate(msg(body = "tail-marker")))
    }

    // spam-6: a saved contact is not held by keyword, regex, prefix or domain block rules; exact blocks still apply.

    private val contactNumber = "+919876543210"

    @Test fun aContactIsNotBlockedByAKeywordRule() {
        val f = filter(
            rules = listOf(rule(ListKind.BLOCK, RuleType.KEYWORD, "hello", id = 3)),
            contacts = setOf(contactNumber),
        )
        assertAllow(DecisionReason.CONTACT, f.evaluate(msg()))
    }

    @Test fun aContactIsNotBlockedByRegexPrefixOrDomainRules() {
        val rules = listOf(
            rule(ListKind.BLOCK, RuleType.REGEX, "hel+o", id = 1),
            rule(ListKind.BLOCK, RuleType.PREFIX, "+9198", id = 2),
            rule(ListKind.BLOCK, RuleType.DOMAIN, "example.com", id = 3),
        )
        val f = filter(rules = rules, contacts = setOf(contactNumber))
        assertAllow(DecisionReason.CONTACT, f.evaluate(msg(body = "hello see www.example.com")))
    }

    @Test fun aContactIsStillBlockedByItsExactNumber() {
        val f = filter(
            rules = listOf(rule(ListKind.BLOCK, RuleType.NUMBER, contactNumber, id = 4)),
            contacts = setOf(contactNumber),
        )
        assertBlock(DecisionReason.BLOCK_RULE, 4, f.evaluate(msg()))
    }

    @Test fun aLaterRuleStillBlocksAContactByNumberWhenAKeywordRuleWasSkipped() {
        val rules = listOf(
            rule(ListKind.BLOCK, RuleType.KEYWORD, "hello", id = 1),
            rule(ListKind.BLOCK, RuleType.NUMBER, contactNumber, id = 2),
        )
        val f = filter(rules = rules, contacts = setOf(contactNumber))
        assertBlock(DecisionReason.BLOCK_RULE, 2, f.evaluate(msg()))
    }

    @Test fun aNonContactIsStillBlockedByPatternRules() {
        val rules = listOf(
            rule(ListKind.BLOCK, RuleType.PREFIX, "+9198", id = 2),
            rule(ListKind.BLOCK, RuleType.KEYWORD, "hello", id = 3),
        )
        assertBlock(DecisionReason.BLOCK_RULE, 2, filter(rules = rules).evaluate(msg()))
        assertBlock(DecisionReason.BLOCK_RULE, 3, filter(rules = rules.drop(1)).evaluate(msg()))
    }

    @Test fun aSenderIdIsBlockedByKeywordRulesAsBefore() {
        val f = filter(
            rules = listOf(rule(ListKind.BLOCK, RuleType.KEYWORD, "hello", id = 3)),
            contacts = setOf(contactNumber),
        )
        assertBlock(DecisionReason.BLOCK_RULE, 3, f.evaluate(msg(address = "VM-SHOP")))
    }

    @Test fun aContactIsNotBlockedByALegacyKeywordButAStrangerIs() {
        val legacy = FakeLegacy(keywords = listOf("hello"))
        assertAllow(DecisionReason.CONTACT, filter(contacts = setOf(contactNumber), legacy = legacy).evaluate(msg()))
        assertBlock(DecisionReason.BLOCK_RULE, null, filter(legacy = legacy).evaluate(msg()))
    }

    @Test fun aContactIsStillBlockedByALegacyBlockedNumber() {
        val legacy = FakeLegacy(blockedNumbers = setOf(contactNumber))
        val f = filter(contacts = setOf(contactNumber), legacy = legacy)
        assertBlock(DecisionReason.BLOCK_RULE, null, f.evaluate(msg()))
    }

    @Test fun aDomainBlockRuleBlocksAMessageWithThatLinkAndNamesTheRule() {
        val f = filter(rules = listOf(rule(ListKind.BLOCK, RuleType.DOMAIN, "example.com", id = 9)))
        assertBlock(DecisionReason.BLOCK_RULE, 9, f.evaluate(msg(body = "win at http://promo.example.com/x")))
        assertAllow(DecisionReason.MODE_OFF, f.evaluate(msg(body = "win at example.org")))
    }
}
