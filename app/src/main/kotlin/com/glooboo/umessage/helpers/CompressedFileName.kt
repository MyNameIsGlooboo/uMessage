package com.glooboo.umessage.helpers

import java.util.UUID

/**
 * The name of a compressed copy of a picked picture. It used to be the millisecond clock, so two pictures picked
 * together (or handled by two threads in the same millisecond) shared one file and the second overwrote the first.
 * A random id makes every copy its own file.
 */
fun compressedFileName(extension: String): String = UUID.randomUUID().toString() + extension
