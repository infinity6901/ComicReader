package com.infinity.comicreader.util

/**
 * Natural ordering comparator: "page2" sorts before "page10", and digit runs are
 * compared numerically. Falls back to case-insensitive comparison otherwise.
 *
 * Built from a lambda (rather than an `object : Comparator`) because [Comparator] is
 * a functional interface.
 */
val NaturalOrder: Comparator<String> = Comparator { a, b -> compareNatural(a, b) }

private fun compareNatural(a: String, b: String): Int {
    var i = 0
    var j = 0
    while (i < a.length && j < b.length) {
        val ca = a[i]
        val cb = b[j]
        if (ca.isDigit() && cb.isDigit()) {
            val ni = endOfDigitRun(a, i)
            val nj = endOfDigitRun(b, j)
            val comparison = compareDigitRuns(a, i, ni, b, j, nj)
            if (comparison != 0) return comparison
            i = ni
            j = nj
        } else {
            val comparison = ca.lowercaseChar().compareTo(cb.lowercaseChar())
            if (comparison != 0) return comparison
            i++
            j++
        }
    }
    return (a.length - i) - (b.length - j)
}

/** Index just past the run of digits in [s] starting at [from]. */
private fun endOfDigitRun(s: String, from: Int): Int {
    var k = from
    while (k < s.length && s[k].isDigit()) k++
    return k
}

/** Numeric comparison of the digit runs `a[ai, an)` and `b[bi, bn)`, ignoring leading zeros. */
private fun compareDigitRuns(a: String, ai: Int, an: Int, b: String, bi: Int, bn: Int): Int {
    val na = a.substring(ai, an).trimStart('0')
    val nb = b.substring(bi, bn).trimStart('0')
    if (na.length != nb.length) return na.length - nb.length
    return na.compareTo(nb)
}
