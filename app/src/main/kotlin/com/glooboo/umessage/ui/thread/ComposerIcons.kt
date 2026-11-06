package com.glooboo.umessage.ui.thread

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** Icons of the attach sheet that Symbols does not have yet, drawn on the same 960 grid. */
internal object ComposerIcons {

    /** A map pin: an outline with a dot in it. */
    val Location: ImageVector by lazy {
        val pin = "M480,880Q160,600 160,408Q160,258 256,169Q353,80 480,80Q607,80 704,169" +
            "Q800,258 800,408Q800,600 480,880Z" +
            "M480,774Q240,550 240,408Q240,299 310,229Q381,160 480,160Q579,160 650,229" +
            "Q720,299 720,408Q720,550 480,774Z" +
            "M480,480A80,80 0 1,0 480,320A80,80 0 1,0 480,480Z"
        ImageVector.Builder(
            name = "Location",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 960f,
            viewportHeight = 960f,
        ).addPath(
            PathParser().parsePathString(pin).toNodes(),
            fill = SolidColor(Color.Black),
            pathFillType = PathFillType.EvenOdd,
        ).build()
    }

    /** U60: sound bars for a recorded voice message, so the microphone stays the sign of voice typing. */
    val Waveform: ImageVector by lazy {
        val bars = "M160,400H240V560H160Z M320,240H400V720H320Z M440,120H520V840H440Z " +
            "M560,280H640V680H560Z M720,400H800V560H720Z"
        ImageVector.Builder(
            name = "Waveform",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 960f,
            viewportHeight = 960f,
        ).addPath(
            PathParser().parsePathString(bars).toNodes(),
            fill = SolidColor(Color.Black),
        ).build()
    }
}
