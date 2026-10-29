package com.yaprak.reader.data

import com.yaprak.reader.domain.library.BookCover
import com.yaprak.reader.domain.library.MAX_CUSTOM_COVER_BYTES
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class BookCoverValidationTest {
    private val id = UUID.randomUUID().toString()
    private val cover = BookCover(id, "$id.png", "image/png", 42, "a".repeat(64), 80, 120)

    @Test fun `canonical metadata accepted`() {
        assertSame(cover, cover.validated())
        val jpeg = cover.copy(mediaType = "image/jpeg", storageName = "$id.jpg")
        val webp = cover.copy(mediaType = "image/webp", storageName = "$id.webp")
        assertSame(jpeg, jpeg.validated()); assertSame(webp, webp.validated())
    }

    @Test fun `unsafe metadata rejected`() {
        listOf(
            cover.copy(id = "book"), cover.copy(id = id.uppercase()),
            cover.copy(storageName = "../$id.png"), cover.copy(storageName = "${id}.jpg"),
            cover.copy(mediaType = "image/svg+xml"), cover.copy(byteSize = 0),
            cover.copy(byteSize = MAX_CUSTOM_COVER_BYTES.toLong() + 1), cover.copy(sha256 = "a".repeat(63)),
            cover.copy(sha256 = "A".repeat(64)), cover.copy(width = 0), cover.copy(height = 16_385),
            cover.copy(width = 10_000, height = 10_000),
        ).forEach { invalid -> assertTrue(invalid.toString(), runCatching { invalid.validated() }.exceptionOrNull() is IllegalArgumentException) }
    }
}
