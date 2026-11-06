package com.glooboo.umessage.helpers

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LinkExtractorTest {

    private val extractor = LinkExtractor()

    @Test fun findsHttpHttpsAndWww() {
        val body = "a http://one.test/x b https://two.test c www.three.test/p"
        assertThat(extractor.extract(body))
            .containsExactly("http://one.test/x", "https://two.test", "www.three.test/p").inOrder()
    }

    @Test fun trailingPunctuationIsNotPartOfTheLink() {
        assertThat(extractor.extract("See https://one.test/a.")).containsExactly("https://one.test/a")
        assertThat(extractor.extract("(https://one.test/a), then www.two.test!"))
            .containsExactly("https://one.test/a", "www.two.test").inOrder()
    }

    @Test fun persianTextNextToALinkStaysOut() {
        assertThat(extractor.extract("لینک: https://one.test/a، لطفا بزنید")).containsExactly("https://one.test/a")
        assertThat(extractor.extract("https://one.test/aسلام")).containsExactly("https://one.test/a")
    }

    @Test fun aRepeatedLinkIsListedOnce() {
        assertThat(extractor.extract("www.one.test and again www.one.test")).containsExactly("www.one.test")
    }

    @Test fun textWithoutALinkHasNone() {
        assertThat(extractor.extract("")).isEmpty()
        assertThat(extractor.extract("Meet at 5, bring file.txt")).isEmpty()
        assertThat(extractor.extract("see http:// or www. alone")).isEmpty()
        assertThat(extractor.hasLink("no link here")).isFalse()
    }
}
