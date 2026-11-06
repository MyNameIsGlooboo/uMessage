package com.glooboo.umessage.ui.main

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainSplashTest {

    @Test
    fun staysWhileTheListLoadsUpToTheCap() {
        assertTrue(keepSplash(loading = true, shownForMs = 0))
        assertTrue(keepSplash(loading = true, shownForMs = SPLASH_MAX_MS - 1))
        assertFalse(keepSplash(loading = true, shownForMs = SPLASH_MAX_MS))
    }

    @Test
    fun goesAsSoonAsTheListIsReady() {
        assertFalse(keepSplash(loading = false, shownForMs = 0))
    }
}
