package com.glooboo.umessage.ui.settings

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** Icons only Settings uses, drawn like [com.glooboo.umessage.ui.icons.Symbols] (Material Symbols, 960 viewport). */
internal object SettingsIcons {
    private const val VIEWPORT = 960f

    /** Material Symbols "open_in_new": the row opens a screen outside the app (SE-10). */
    val OpenInNew: ImageVector by lazy {
        ImageVector.Builder(
            name = "OpenInNew",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = VIEWPORT,
            viewportHeight = VIEWPORT,
            autoMirror = true,
        ).addPath(
            PathParser().parsePathString(
                "M200,840Q167,840 143.5,816.5Q120,793 120,760L120,200Q120,167 143.5,143.5Q167,120 200,120L480,120" +
                    "L480,200L200,200L200,760L760,760L760,480L840,480L840,760Q840,793 816.5,816.5Q793,840 760,840" +
                    "L200,840ZM388,628L332,572L704,200L560,200L560,120L840,120L840,400L760,400L760,256L388,628Z",
            ).toNodes(),
            fill = SolidColor(Color.Black),
        ).build()
    }
}
