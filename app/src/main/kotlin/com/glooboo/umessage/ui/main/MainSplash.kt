package com.glooboo.umessage.ui.main

/** Longest the launch icon waits for the saved list. */
const val SPLASH_MAX_MS = 1500L

/**
 * Keep the launch icon until the saved list is ready, so the screen doesn't flash "Loading messages" first. Capped,
 * so a slow start still shows the screen.
 */
fun keepSplash(loading: Boolean, shownForMs: Long): Boolean = loading && shownForMs < SPLASH_MAX_MS
