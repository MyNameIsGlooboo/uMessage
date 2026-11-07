package com.glooboo.umessage.helpers

/**
 * B12: message dates are Int seconds in the database, which run out on 19 January 2038. Narrowing a Long with
 * `toInt()` past that point wraps to a date in 1901 (and a message "older than everything"); this stops at the last
 * Int second instead. The database type itself is not changed (that is a schema migration of every date query).
 */
fun Long.toSecondsInt(): Int = coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
