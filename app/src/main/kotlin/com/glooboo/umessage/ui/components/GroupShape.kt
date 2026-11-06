package com.glooboo.umessage.ui.components

/** Corner radii (dp) of one row in a grouped settings section: large outside, small between rows. */
data class RowCorners(val topDp: Int, val bottomDp: Int)

object GroupShape {
    const val OUTER_DP = 28
    const val INNER_DP = 4
    const val GAP_DP = 2

    /** First row rounds its top, last row its bottom, a single row both; every other corner is small. */
    fun corners(index: Int, count: Int): RowCorners = RowCorners(
        topDp = if (index == 0) OUTER_DP else INNER_DP,
        bottomDp = if (index == count - 1) OUTER_DP else INNER_DP,
    )
}
