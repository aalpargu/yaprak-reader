package com.yaprak.reader.formats.cbz

import java.io.Closeable
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** A concrete file pin for CBZ originals and the bounded CBR-derived page archive. */
internal class ComicArchiveFileLease(val file: File, private val release: () -> Unit = {}) : Closeable {
    private val closed = AtomicBoolean(false)
    override fun close() { if (closed.compareAndSet(false, true)) release() }
}
