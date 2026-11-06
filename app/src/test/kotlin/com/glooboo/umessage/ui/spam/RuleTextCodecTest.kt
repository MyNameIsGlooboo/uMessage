package com.glooboo.umessage.ui.spam

import com.glooboo.umessage.spam.ListKind
import com.glooboo.umessage.spam.RuleType
import com.glooboo.umessage.spam.SpamRule
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RuleTextCodecTest {

    private fun comparable(rules: List<SpamRule>) = rules.map { listOf(it.kind, it.type, it.pattern, it.subscriptionId, it.note) }

    @Test
    fun exportKeepsSimScopeAndNote() {
        // SU-11: a SIM 2 block used to come back as a block on both SIMs, and notes were lost.
        val rules = listOf(
            SpamRule(kind = ListKind.BLOCK, type = RuleType.NUMBER, pattern = "+44712", subscriptionId = 2, note = "Telemarketer, again"),
            SpamRule(kind = ListKind.ALLOW, type = RuleType.SENDER_ID, pattern = "VM-HDFCBK", note = "My bank"),
            SpamRule(kind = ListKind.BLOCK, type = RuleType.REGEX, pattern = "win,\\s+\\d+\tnow"),
        )
        val decoded = RuleTextCodec.decode(RuleTextCodec.encode(rules))
        assertThat(decoded.invalidLines).isEqualTo(0)
        assertThat(comparable(decoded.rules)).isEqualTo(comparable(rules))
    }

    @Test
    fun noteWithTabsOrLineBreaksIsFlattened() {
        val rules = listOf(SpamRule(kind = ListKind.BLOCK, type = RuleType.KEYWORD, pattern = "free gift", note = "a\tb\nc"))
        val decoded = RuleTextCodec.decode(RuleTextCodec.encode(rules))
        assertThat(decoded.rules.single().note).isEqualTo("a b c")
    }

    @Test
    fun oldCommaFilesAreStillRead() {
        val decoded = RuleTextCodec.decode("BLOCK,NUMBER,+44712\nallow,keyword,delivery, code\n# comment\n\nBLOCK,REGEX,(a+)+\nnonsense\n")
        assertThat(comparable(decoded.rules)).isEqualTo(
            listOf(
                listOf(ListKind.BLOCK, RuleType.NUMBER, "+44712", null, ""),
                listOf(ListKind.ALLOW, RuleType.KEYWORD, "delivery, code", null, ""),
            ),
        )
        // The unsafe regex and the junk line.
        assertThat(decoded.invalidLines).isEqualTo(2)
    }

    @Test
    fun badSimFieldMakesTheLineInvalid() {
        val decoded = RuleTextCodec.decode("${RuleTextCodec.HEADER}\nBLOCK\tNUMBER\tsim2\t\t+44712\nBLOCK\tNUMBER\t\t\t+44713\n")
        assertThat(decoded.invalidLines).isEqualTo(1)
        assertThat(decoded.rules.single().pattern).isEqualTo("+44713")
    }

    @Test
    fun shortAllowKeywordInAFileIsRejected() {
        val decoded = RuleTextCodec.decode("ALLOW,KEYWORD,ok\n")
        assertThat(decoded.rules).isEmpty()
        assertThat(decoded.invalidLines).isEqualTo(1)
    }
}
