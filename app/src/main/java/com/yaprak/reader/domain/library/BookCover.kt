package com.yaprak.reader.domain.library

import java.util.UUID

const val MAX_CUSTOM_COVER_BYTES = 8 * 1024 * 1024

/** Immutable private artwork revision; original book bytes are never rewritten. */
data class BookCover(
    val id: String,
    val storageName: String,
    val mediaType: String,
    val byteSize: Long,
    val sha256: String,
    val width: Int,
    val height: Int,
) {
    fun validated(): BookCover {
        require(runCatching { UUID.fromString(id).toString() }.getOrNull() == id)
        val extension = when (mediaType) {
            "image/jpeg" -> "jpg"
            "image/png" -> "png"
            "image/webp" -> "webp"
            else -> throw IllegalArgumentException("Unsupported cover image")
        }
        require(storageName == "$id.$extension")
        require(byteSize in 1..MAX_CUSTOM_COVER_BYTES.toLong())
        require(sha256.matches(Regex("[0-9a-f]{64}")))
        require(width in 1..16_384 && height in 1..16_384 && width.toLong() * height <= 40_000_000)
        return this
    }
}
