package com.glooboo.umessage.export

import com.glooboo.umessage.export.PdfPaginator.paginate
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Which lines land on which page, from line heights alone. */
class PdfPaginatorTest {
    private fun block(lines: Int, height: Float = 10f) = List(lines) { height }

    @Test
    fun noBlocks_stillGiveOneEmptyPage() {
        assertThat(paginate(emptyList(), 100f, 5f)).hasSize(1)
        assertThat(paginate(emptyList(), 100f, 5f).single()).isEmpty()
    }

    @Test
    fun blocksThatFit_shareOnePageStackedWithTheGap() {
        val pages = paginate(listOf(block(2), block(3)), 100f, 5f)
        assertThat(pages).hasSize(1)
        assertThat(pages[0].map { it.y }).containsExactly(0f, 25f).inOrder()
        assertThat(pages[0].map { it.height }).containsExactly(20f, 30f).inOrder()
    }

    @Test
    fun aBlockThatDoesNotFitTheRest_movesWholeToTheNextPage() {
        val pages = paginate(listOf(block(6), block(5)), 100f, 5f)
        assertThat(pages).hasSize(2)
        assertThat(pages[1].single().block).isEqualTo(1)
        assertThat(pages[1].single().firstLine).isEqualTo(0)
        assertThat(pages[1].single().endLine).isEqualTo(5)
        assertThat(pages[1].single().y).isEqualTo(0f)
    }

    @Test
    fun aBlockTallerThanAPage_isSplitBetweenLines() {
        val pages = paginate(listOf(block(25)), 100f, 5f)
        assertThat(pages).hasSize(3)
        assertThat(pages.map { slices -> slices.sumOf { it.endLine - it.firstLine } }).containsExactly(10, 10, 5)
        assertThat(pages[1].single().firstLine).isEqualTo(10)
    }

    @Test
    fun aGiantBlockStartedMidPage_fillsTheRestFirst() {
        val pages = paginate(listOf(block(3), block(25)), 100f, 5f)
        val first = pages[0].last()
        assertThat(first.block).isEqualTo(1)
        assertThat(first.endLine - first.firstLine).isEqualTo(6)
        assertThat(pages).hasSize(3)
    }

    @Test
    fun aLineTallerThanThePage_stillGetsAPageAndEndsTheLoop() {
        val pages = paginate(listOf(listOf(300f), block(1)), 100f, 5f)
        assertThat(pages).hasSize(2)
        assertThat(pages[0].single().height).isEqualTo(300f)
    }

    @Test
    fun morePages_comeWithMoreMessages() {
        fun pageCount(messages: Int) = paginate(List(messages) { block(4) }, 200f, 8f).size
        assertThat(pageCount(1)).isEqualTo(1)
        assertThat(pageCount(10)).isLessThan(pageCount(40))
        assertThat(pageCount(40)).isLessThan(pageCount(400))
    }

    @Test
    fun noPageIsEmpty_andEveryLineIsPlacedOnce() {
        val blocks = List(37) { block(it % 7 + 1, 9f + it % 3) } + listOf(block(60))
        val pages = paginate(blocks, 120f, 6f)
        assertThat(pages.all { it.isNotEmpty() }).isTrue()
        val placed = pages.flatten().groupBy { it.block }.mapValues { (_, s) -> s.sumOf { it.endLine - it.firstLine } }
        assertThat(placed).isEqualTo(blocks.indices.associateWith { blocks[it].size })
        assertThat(pages.flatten().all { it.y + it.height <= 120f + 1f || it.endLine - it.firstLine == 1 }).isTrue()
    }

    @Test
    fun anEmptyBlock_isSkipped() {
        val pages = paginate(listOf(block(2), emptyList(), block(2)), 100f, 5f)
        assertThat(pages.single().map { it.block }).containsExactly(0, 2).inOrder()
    }
}
