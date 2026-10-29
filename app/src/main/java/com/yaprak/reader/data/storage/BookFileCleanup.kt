package com.yaprak.reader.data.storage

/** Unlinks only a committed private book copy; missing copies count as clean. */
fun interface BookFileCleanup {
    fun removeCommitted(storageName: String): Boolean
}
