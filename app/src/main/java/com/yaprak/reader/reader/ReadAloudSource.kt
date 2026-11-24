package com.yaprak.reader.reader

/** One spoken piece of the book's own text and an opaque position the reader can show. */
class ReadAloudChunk(val text: String, internal val position: Any)

/**
 * Text for read-aloud, starting at the reader's current position. Implementations return a bounded
 * batch; [next] continues after the last chunk of the previous batch. Nothing is sent off the device.
 */
interface ReadAloudSource {
    /** BCP-47 language of the book when known, so speech uses a matching voice. */
    val language: String?
    suspend fun fromCurrent(): List<ReadAloudChunk>
    suspend fun next(after: ReadAloudChunk): List<ReadAloudChunk>
    /** Moves the page to the chunk being spoken; false when the reader can't follow right now. */
    fun show(chunk: ReadAloudChunk): Boolean
}
