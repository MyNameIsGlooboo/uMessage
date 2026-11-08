package com.glooboo.umessage.ui.components

import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * Landscape on phones: the app rotates on every screen. These two helpers keep wide windows tidy: content stays out
 * from under a side cutout or a side navigation bar, and message bubbles stop growing with the window.
 */

/** A message bubble never grows wider than this, however wide the window is (landscape phones, tablets). */
const val BUBBLE_MAX_WIDTH_DP = 480

private const val BUBBLE_WIDTH_FRACTION = 0.8f

/** The widest a message bubble may be in a window [windowWidthDp] wide: 80 percent of it, but at most 480dp. */
fun bubbleMaxWidthDp(windowWidthDp: Int): Dp =
    minOf(windowWidthDp * BUBBLE_WIDTH_FRACTION, BUBBLE_MAX_WIDTH_DP.toFloat()).dp

/** [bubbleMaxWidthDp] for the current window. */
@Composable
fun rememberBubbleMaxWidth(): Dp = bubbleMaxWidthDp(LocalConfiguration.current.screenWidthDp)

/**
 * Lets the body of a dialog scroll. A dialog is as tall as the window, and in landscape (or with the keyboard up) a
 * picker or a form is taller than that; without this its buttons or fields would be cut off.
 */
@Composable
fun Modifier.dialogScroll(): Modifier = verticalScroll(rememberScrollState())

/**
 * Moves content in from a display cutout and from system bars at the left and right edge. Backgrounds drawn before
 * this modifier still reach the screen edge. The insets are consumed, so nothing inside pads them a second time.
 */
@Composable
fun Modifier.horizontalChromePadding(): Modifier =
    windowInsetsPadding(chromeInsets().only(WindowInsetsSides.Horizontal))
