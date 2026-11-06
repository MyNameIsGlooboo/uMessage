package com.glooboo.umessage.helpers.smartreply

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** I59: behaviour of the matcher on the shipped tables. Invented texts only. */
class SmartReplyMatcherTest {

    private val matcher = SmartReplyTestData.matcher()

    private fun intent(body: String, language: String? = null) = matcher.match(body, language)?.intentId

    @Test
    fun anExactPhraseGivesThreeAtMostReplies() {
        val reply = matcher.match("Are you coming tonight?", "en")!!
        assertThat(reply.intentId).isEqualTo("are_you_coming")
        assertThat(reply.language).isEqualTo("en")
        assertThat(reply.replies.size).isIn(2..3)
        assertThat(reply.rightToLeft).isFalse()
    }

    @Test
    fun fillerWordsAroundAPhraseAreIgnored() {
        assertThat(intent("hey bro, how are you")).isEqualTo("how_are_you")
        assertThat(intent("Hello hello")).isEqualTo("greeting")
        assertThat(intent("سلام خوبی؟")).isEqualTo("how_are_you")
    }

    @Test
    fun spellingVariantsMatchTheSameIntent() {
        assertThat(intent("Heyyyy")).isEqualTo("greeting")
        assertThat(intent("Thaaanks")).isEqualTo("thanks")
        assertThat(intent("İYI GECELER")).isEqualTo("good_night")
        assertThat(intent("فردا می‌آیی؟")).isEqualTo("are_you_coming")
        assertThat(intent("فردا میای؟")).isEqualTo("are_you_coming")
        assertThat(intent("السَّلَامُ عَلَيْكُمْ"))
            .isEqualTo("salam")
    }

    @Test
    fun anyDoubtGivesNothing() {
        assertThat(intent("ok?")).isNull()
        assertThat(intent("Are you coming? Are you sure?")).isNull()
        assertThat(intent("Can't wait! How are you?")).isNull()
        assertThat(intent("thanks سلام")).isNull()
        assertThat(intent("habari")).isNull()
        assertThat(intent("")).isNull()
        assertThat(intent("😂😂")).isNull()
    }

    @Test
    fun linksAmountsAndLongNumbersGiveNothing() {
        assertThat(intent("Thanks and see you at www.shop.com")).isNull()
        assertThat(intent("Thanks $20")).isNull()
        assertThat(intent("thanks 12345")).isNull()
        assertThat(intent("thanks")).isEqualTo("thanks")
    }

    @Test
    fun aLongTextGivesNothing() {
        val text = "good morning everyone I hope you all slept well and are ready for the long day ahead"
        assertThat(intent(text)).isNull()
    }

    @Test
    fun persianRepliesAreRightToLeftAndKeepZeroWidthJoiners() {
        val reply = matcher.match("خوبی؟", "fa")!!
        assertThat(reply.language).isEqualTo("fa")
        assertThat(reply.rightToLeft).isTrue()
        assertThat(reply.replies.any { '‌' in it }).isTrue()
    }

    @Test
    fun aPhraseTwoArabicScriptTablesShareGoesToTheAppLanguageThenToPersian() {
        val salam = "سلام"
        assertThat(matcher.match(salam, "ar")?.language).isEqualTo("ar")
        assertThat(matcher.match(salam, "fa")?.language).isEqualTo("fa")
        assertThat(matcher.match(salam, "en")?.language).isEqualTo("fa")
        assertThat(matcher.match(salam)?.language).isEqualTo("fa")
    }

    @Test
    fun theLanguageOfTheTextDecidesNotTheAppLanguage() {
        assertThat(matcher.match("Günaydın", "en")?.language).isEqualTo("tr")
        assertThat(matcher.match("Good morning", "tr")?.language).isEqualTo("en")
    }

    @Test
    fun chineseMatchesWithoutSpacesAndAnywhereInTheText() {
        assertThat(intent("你好！")).isEqualTo("greeting")
        assertThat(intent("谢谢谢谢谢谢")).isEqualTo("thanks")
        assertThat(intent("嗨，最近怎么样？")).isEqualTo("how_are_you")
        assertThat(intent("你明天来吗？")).isEqualTo("are_you_coming")
        assertThat(intent("你们周末聚会来不来")).isEqualTo("are_you_coming")
        assertThat(matcher.match("晚安啦", "zh")?.language).isEqualTo("zh")
    }

    @Test
    fun chineseDoubtsGiveNothing() {
        assertThat(intent("你不来吗")).isNull()
        assertThat(intent("奶奶去世了")).isNull()
        assertThat(intent("谁来了？")).isNull()
        assertThat(intent("你好吗？你妈妈好吗？")).isNull()
        assertThat(intent("今天天气不错，我们一起出去走走吧，顺便买点东西回家做饭，然后早点休息")).isNull()
        assertThat(intent("thanks 谢谢")).isNull()
    }

    @Test
    fun japaneseKoreanAndThaiMatchToo() {
        assertThat(intent("ありがとう！")).isEqualTo("thanks")
        assertThat(intent("おやすみー")).isEqualTo("good_night")
        assertThat(intent("今どこ？")).isEqualTo("where_are_you")
        assertThat(intent("가는 중이야")).isEqualTo("on_my_way")
        assertThat(intent("고마워 ㅎㅎ")).isEqualTo("thanks")
        assertThat(intent("내일 올래?")).isEqualTo("are_you_coming")
        assertThat(intent("สวัสดีครับ")).isEqualTo("greeting")
        assertThat(intent("ขอบคุณมากๆค่ะ")).isEqualTo("thanks")
        assertThat(intent("ถึงบ้านแล้วนะคะ")).isEqualTo("arrived_home")
    }

    private fun smallTable(language: String, script: String, phrase: String, replies: String = """"A1","A2"""") =
        SmartReplyTables.parse(
            """{"language":"$language","script":"$script","intents":[""" +
                """{"id":"a","exact":["$phrase"],"replies":[$replies]}]}""",
        )

    @Test
    fun aKanjiOnlyTextIsReadByTheJapaneseAndTheChineseTable() {
        val both = SmartReplyMatcher(listOf(smallTable("zh", "han", "了解"), smallTable("ja", "japanese", "了解")))
        assertThat(both.match("了解", "ja")?.language).isEqualTo("ja")
        assertThat(both.match("了解", "zh-CN")?.language).isEqualTo("zh")
        assertThat(both.match("了解こ", "zh")).isNull()
        val onlyChinese = SmartReplyMatcher(listOf(smallTable("zh", "han", "了解")))
        assertThat(onlyChinese.match("りょうかい", "zh")).isNull()
    }

    @Test
    fun theAppLanguageIsReadAsAndroidNamesIt() {
        val both = SmartReplyMatcher(
            listOf(smallTable("ru", "cyrillic", "привіт"), smallTable("uk", "cyrillic", "привіт")),
        )
        assertThat(both.match("привіт", "uk-UA")?.language).isEqualTo("uk")
        assertThat(both.match("привіт", "ru_RU")?.language).isEqualTo("ru")
        assertThat(both.match("привіт")).isNull()
        val indonesian = SmartReplyMatcher(
            listOf(smallTable("id", "cyrillic", "привіт"), smallTable("pl", "cyrillic", "привіт")),
        )
        assertThat(indonesian.match("привіт", "in")?.language).isEqualTo("id")
    }

    private fun poolMatcher(replyCount: Int): SmartReplyMatcher {
        val replies = (1..replyCount).joinToString(",") { "\"R$it\"" }
        return SmartReplyMatcher(listOf(smallTable("xx", "latin", "ping", replies)))
    }

    @Test
    fun repliesRotateByTheSeedAndStayStableForOneSeed() {
        val pooled = poolMatcher(8)
        val picks = (1L..40L).map { pooled.match("ping", null, it)!!.replies }
        assertThat(picks.all { it.size == 3 && it.distinct().size == 3 }).isTrue()
        assertThat(picks.distinct().size).isGreaterThan(5)
        assertThat(pooled.match("ping", null, 7L)!!.replies).isEqualTo(pooled.match("ping", null, 7L)!!.replies)
        assertThat(pooled.match("ping")!!.replies).containsExactly("R1", "R2", "R3").inOrder()
        assertThat(pooled.match("ping", null, 0L)!!.replies).containsExactly("R1", "R2", "R3").inOrder()
    }

    @Test
    fun aPoolOfThreeOrFewerIsNeverShuffled() {
        val three = poolMatcher(3)
        for (seed in 1L..10L) {
            assertThat(three.match("ping", null, seed)!!.replies).containsExactly("R1", "R2", "R3").inOrder()
        }
        assertThat(poolMatcher(1).match("ping", null, 5L)!!.replies).containsExactly("R1")
    }

    @Test
    fun aTableWithoutALanguageGivesNothing() {
        val onlyEnglish = SmartReplyMatcher(SmartReplyTestData.tables().filter { it.language == "en" })
        assertThat(onlyEnglish.match("سلام", "fa")).isNull()
        assertThat(onlyEnglish.match("Good night", "fa")?.intentId).isEqualTo("good_night")
    }

    @Test
    fun anIntentWithTwoEqualMatchesGivesNothing() {
        val table = SmartReplyTables.parse(
            """{"language":"xx","script":"latin","intents":[""" +
                """{"id":"a","exact":["ping"],"replies":["A1","A2"]},""" +
                """{"id":"b","exact":["ping"],"replies":["B1","B2"]}]}""",
        )
        assertThat(SmartReplyMatcher(listOf(table)).match("ping")).isNull()
    }

    @Test
    fun aStricterTableCanDropLooseMatches() {
        val loose = SmartReplyTables.parse(
            """{"language":"xx","script":"latin","intents":[""" +
                """{"id":"a","contains":["are you in"],"replies":["A1","A2"]}]}""",
        )
        assertThat(SmartReplyMatcher(listOf(loose)).match("are you in tonight")?.intentId).isEqualTo("a")
        assertThat(SmartReplyMatcher(listOf(loose.copy(minConfidence = 1.0))).match("are you in tonight")).isNull()
    }
}
