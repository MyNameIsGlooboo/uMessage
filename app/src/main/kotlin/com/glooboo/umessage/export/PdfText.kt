package com.glooboo.umessage.export

import android.text.StaticLayout

/** Small text helpers of the PDF export. */
object PdfText {
    private const val FSI = "\u2068"
    private const val PDI = "\u2069"

    /** First-strong isolate: a Persian name beside an English line never reorders its neighbours. */
    fun isolate(text: String) = FSI + text + PDI

    fun lineHeights(layout: StaticLayout): List<Float> =
        (0 until layout.lineCount).map { (layout.getLineBottom(it) - layout.getLineTop(it)).toFloat() }
}
