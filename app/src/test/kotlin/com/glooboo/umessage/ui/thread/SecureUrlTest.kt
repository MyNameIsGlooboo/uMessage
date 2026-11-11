package com.glooboo.umessage.ui.thread

import org.junit.Assert.assertEquals
import org.junit.Test

class SecureUrlTest {
    @Test
    fun linkWrittenWithoutSchemeOpensOnHttps() {
        assertEquals("https://example.com/a", secureUrl("http://example.com/a", "example.com/a"))
    }

    @Test
    fun writtenHttpStaysHttp() {
        assertEquals("http://example.com", secureUrl("http://example.com", "http://example.com"))
    }

    @Test
    fun mailAndHttpsAreUntouched() {
        assertEquals("mailto:a@b.test", secureUrl("mailto:a@b.test", "a@b.test"))
        assertEquals("https://x.test", secureUrl("https://x.test", "https://x.test"))
    }
}
