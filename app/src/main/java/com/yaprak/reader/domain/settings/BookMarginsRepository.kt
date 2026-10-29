package com.yaprak.reader.domain.settings

import kotlinx.coroutines.flow.Flow

/** Null inherits the current global EPUB margins; a saved 100% is an explicit choice. */
interface BookMarginsRepository {
    fun observe(bookId: String, contentSha256: String): Flow<Int?>
    suspend fun save(bookId: String, contentSha256: String, marginsPercent: Int?)
}
