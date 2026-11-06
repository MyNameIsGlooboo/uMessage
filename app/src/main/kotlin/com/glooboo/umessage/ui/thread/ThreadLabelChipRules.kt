package com.glooboo.umessage.ui.thread

import com.glooboo.umessage.models.Label
import com.glooboo.umessage.ui.labels.LabelsUiState

/** The chips under the thread title: the labels [shown] and how many more there are ([hidden]). */
data class ThreadLabelChips(val shown: List<Label>, val hidden: Int) {
    val isEmpty: Boolean get() = shown.isEmpty()
}

object ThreadLabelChipRules {
    /** Three chips fit next to the number; the rest are counted. */
    const val MAX_CHIPS = 3

    /** The labels of conversation [threadId] in the order of the label list, at most [MAX_CHIPS] of them. */
    fun chipsFor(state: LabelsUiState, threadId: Long): ThreadLabelChips {
        if (threadId == 0L) return ThreadLabelChips(emptyList(), 0)
        val own = state.labels.filter { threadId in state.threadsOf(it.id) }
        return ThreadLabelChips(own.take(MAX_CHIPS), (own.size - MAX_CHIPS).coerceAtLeast(0))
    }

    /**
     * How many of the chips with the given [chipWidths] fit in [available] pixels next to the "+n" chip that counts
     * the rest ([hidden] more plus the ones left out), with [gap] between neighbours. Null when not even the "+n"
     * chip fits. [moreWidth] gives the width of the "+n" chip for a count.
     */
    fun fitCount(chipWidths: List<Int>, hidden: Int, available: Int, gap: Int, moreWidth: (Int) -> Int): Int? {
        for (count in chipWidths.size downTo 0) {
            val remaining = chipWidths.size - count + hidden
            var width = chipWidths.take(count).sum() + gap * (count - 1).coerceAtLeast(0)
            if (remaining > 0) width += moreWidth(remaining) + if (count > 0) gap else 0
            if (width <= available) return count
        }
        return null
    }
}
