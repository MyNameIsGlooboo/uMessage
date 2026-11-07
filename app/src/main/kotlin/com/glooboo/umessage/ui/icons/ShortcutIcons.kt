package com.glooboo.umessage.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** Icons the generated [Symbols] object does not have: the star (empty and filled) and the link. */
object ShortcutIcons {
    val Star: ImageVector by lazy {
        vector(
            "Star",
            "M354,673L480,597L606,674L573,530L684,434L538,421L480,285L422,420L276,433L387,530L354,673Z" +
                "M233,840L298,559L80,370L368,345L480,80L592,345L880,370L662,559L727,840L480,691L233,840Z",
        )
    }

    val StarFilled: ImageVector by lazy {
        vector(
            "StarFilled",
            "M233,840L298,559L80,370L368,345L480,80L592,345L880,370L662,559L727,840L480,691L233,840Z",
        )
    }

    /** A filled dot: the Unread tile. */
    val UnreadDot: ImageVector by lazy {
        vector("UnreadDot", "M280,480A200,200 0 1,0 680,480A200,200 0 1,0 280,480Z")
    }

    /** A person with a question mark beside them: the Unknown tile. */
    val UnknownPerson: ImageVector by lazy {
        vector(
            "UnknownPerson",
            "M150,360A130,130 0 1,0 410,360A130,130 0 1,0 150,360Z" +
                "M90,780L90,700Q90,600 280,600Q470,600 470,700L470,780Z" +
                "M720,740A42,42 0 1,0 804,740A42,42 0 1,0 720,740Z" +
                "M720,640L720,600Q720,540 765,500Q810,462 810,420Q810,370 762,370Q716,370 708,430L626,414" +
                "Q644,290 762,290Q840,290 884,334Q924,374 924,430Q924,490 870,534Q820,574 820,610L820,640Z",
        )
    }

    val Mail: ImageVector by lazy {
        vector(
            "Mail",
            "M160,800Q127,800 103.5,776.5Q80,753 80,720L80,240Q80,207 103.5,183.5Q127,160 160,160L800,160" +
                "Q833,160 856.5,183.5Q880,207 880,240L880,720Q880,753 856.5,776.5Q833,800 800,800L160,800Z" +
                "M480,520L160,320L160,720L800,720L800,320L480,520Z" +
                "M480,440L800,240L160,240L480,440Z",
        )
    }

    val Link: ImageVector by lazy {
        vector(
            "Link",
            "M440,680L280,680Q197,680 138.5,621.5Q80,563 80,480Q80,397 138.5,338.5Q197,280 280,280L440,280L440,360" +
                "L280,360Q230,360 195,395Q160,430 160,480Q160,530 195,565Q230,600 280,600L440,600L440,680Z" +
                "M320,520L320,440L640,440L640,520L320,520Z" +
                "M520,680L520,600L680,600Q730,600 765,565Q800,530 800,480Q800,430 765,395Q730,360 680,360L520,360" +
                "L520,280L680,280Q763,280 821.5,338.5Q880,397 880,480Q880,563 821.5,621.5Q763,680 680,680L520,680Z",
        )
    }

    private fun vector(name: String, pathData: String): ImageVector = ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 960f,
        viewportHeight = 960f,
    ).addPath(
        PathParser().parsePathString(pathData).toNodes(),
        pathFillType = PathFillType.EvenOdd,
        fill = SolidColor(Color.Black),
    ).build()
}
