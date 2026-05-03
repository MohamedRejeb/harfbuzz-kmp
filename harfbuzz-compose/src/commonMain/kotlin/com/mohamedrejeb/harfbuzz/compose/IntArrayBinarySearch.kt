package com.mohamedrejeb.harfbuzz.compose

/**
 * Standard binary search on a sorted [IntArray]. Mirrors the JVM stdlib's
 * `IntArray.binarySearch(element)` shape (positive index when found,
 * `-(insertion_point) - 1` when not), but is hand-rolled so it is also
 * available on Kotlin/Native targets where the primitive-array overload
 * isn't part of the stdlib.
 */
internal fun IntArray.binarySearchInt(element: Int): Int {
    var lo = 0
    var hi = size - 1
    while (lo <= hi) {
        val mid = (lo + hi).ushr(1)
        val v = this[mid]
        when {
            v < element -> lo = mid + 1
            v > element -> hi = mid - 1
            else -> return mid
        }
    }
    return -(lo + 1)
}
