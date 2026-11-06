package com.glooboo.umessage.ui.thread

object EditDistance {

    /** Edit distance counting a swap of two neighbours as one change. */
    fun between(a: String, b: String): Int {
        val d = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) d[i][0] = i
        for (j in 0..b.length) d[0][j] = j
        for (i in 1..a.length) {
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + cost)
                if (isSwap(a, b, i, j)) d[i][j] = minOf(d[i][j], d[i - 2][j - 2] + 1)
            }
        }
        return d[a.length][b.length]
    }

    private fun isSwap(a: String, b: String, i: Int, j: Int): Boolean =
        i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]
}
