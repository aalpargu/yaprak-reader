package com.yaprak.reader.formats.epub

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One surface's immutable SDK catalogue, shared by independent position and pace collectors. */
internal class EpubPaceMapCache(
    private val current: () -> Boolean,
    private val load: suspend () -> EpubPaceMap?,
) {
    private val mutex = Mutex()
    private var initialized = false
    private var mapping: EpubPaceMap? = null

    suspend fun get(): EpubPaceMap? = mutex.withLock {
        currentCoroutineContext().ensureActive()
        if (!current()) return@withLock null
        if (!initialized) {
            val result = load()
            currentCoroutineContext().ensureActive()
            if (!current()) return@withLock null
            mapping = result
            initialized = true
        }
        mapping
    }
}
