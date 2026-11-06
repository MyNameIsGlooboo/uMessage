package com.glooboo.umessage.export

/**
 * Decides which lines of which blocks go on which page, from the line heights alone, so the PDF layout can be tested
 * without drawing. A block (one message) stays whole on a page when it fits on one; a block taller than a page is
 * split between lines; a block that does not fit in what is left of the page moves to the next one.
 */
object PdfPaginator {

    /** Lines [firstLine] until [endLine] of block [block], drawn at [y] from the top of the page and [height] tall. */
    data class Slice(val block: Int, val firstLine: Int, val endLine: Int, val y: Float, val height: Float)

    /**
     * [blocks] holds the line heights of every block in order; [pageHeight] is the room for content on a page and
     * [gap] the space left under a block. At least one page comes back; no page but the first is empty.
     */
    fun paginate(blocks: List<List<Float>>, pageHeight: Float, gap: Float): List<List<Slice>> {
        val pager = Pager(pageHeight, gap)
        for ((index, lines) in blocks.withIndex()) {
            if (lines.isNotEmpty()) pager.place(index, lines)
        }
        return pager.pages
    }

    private class Pager(private val pageHeight: Float, private val gap: Float) {
        val pages = mutableListOf(mutableListOf<Slice>())
        private var y = 0f

        fun place(index: Int, lines: List<Float>) {
            val total = lines.sum()
            if (pages.last().isNotEmpty() && y + total > pageHeight && total <= pageHeight) newPage()
            var line = 0
            while (line < lines.size) {
                val end = fit(lines, line)
                if (end == line) {
                    newPage()
                    continue
                }
                val height = lines.subList(line, end).sum()
                pages.last().add(Slice(index, line, end, y, height))
                line = end
                if (line < lines.size) newPage() else y += height + gap
            }
        }

        /** The line the slice that starts at [from] ends before, on the page as it is now. */
        private fun fit(lines: List<Float>, from: Int): Int {
            var end = from
            var height = 0f
            while (end < lines.size && fits(height + lines[end], first = end == from)) {
                height += lines[end]
                end++
            }
            return end
        }

        /** The first line of an empty page is always taken, so a line taller than the page cannot loop. */
        private fun fits(height: Float, first: Boolean) = height <= pageHeight - y || first && y == 0f

        private fun newPage() {
            pages.add(mutableListOf())
            y = 0f
        }
    }
}
