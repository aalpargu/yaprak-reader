package com.yaprak.reader.feature

import android.app.Application
import android.os.Bundle
import android.os.Parcel
import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.feature.reader.PdfCropChoice
import com.yaprak.reader.feature.reader.PdfCropSavedState
import com.yaprak.reader.reader.PdfPageCrop
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Android Bundle/Parcel acceptance for applied visual choices, separate from transient editor state. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PdfCropSavedStateTest {
    private val keys = listOf("readerPdfCropBook", "readerPdfCropHash", "readerPdfCropHorizontal", "readerPdfCropVertical")
    private val choice = PdfCropChoice("crop-book", "a".repeat(64), PdfPageCrop(7, 14))

    @Test fun `applied choice survives real Android Parcel roundtrip with zero and maximum percentages`() {
        for (crop in listOf(PdfPageCrop(), PdfPageCrop(20, 20), choice.crop)) {
            val original = choice.copy(crop = crop)
            val bundle = Bundle().apply {
                putBoolean("readerPdfNightChoice", true)
                PdfCropSavedState.write(this, original)
            }
            val parcel = Parcel.obtain()
            try {
                parcel.writeBundle(bundle); parcel.setDataPosition(0)
                val restored = checkNotNull(parcel.readBundle(javaClass.classLoader))
                assertEquals(original, PdfCropSavedState.read(restored))
                assertTrue(restored.getBoolean("readerPdfNightChoice"))
            } finally { parcel.recycle() }
        }
    }

    @Test fun `incomplete crop keys never restore a partial choice`() {
        assertNull(PdfCropSavedState.read(null)); assertNull(PdfCropSavedState.read(Bundle()))
        for (missing in keys) {
            val bundle = saved().apply { remove(missing) }
            assertNull("Missing $missing", PdfCropSavedState.read(bundle))
        }
    }

    @Test fun `wrong Bundle types are rejected rather than converted into a saved preference`() {
        val wrong = listOf<Pair<String, Any?>>(
            keys[0] to 123, keys[1] to 123,
            keys[2] to "7", keys[2] to 7L, keys[2] to 7.0, keys[2] to true,
            keys[3] to "14", keys[3] to 14L, keys[3] to 14.0, keys[3] to false,
            keys[0] to null, keys[1] to null, keys[2] to null, keys[3] to null,
        )
        for ((key, value) in wrong) {
            val bundle = saved()
            when (value) {
                is String -> bundle.putString(key, value)
                is Int -> bundle.putInt(key, value)
                is Long -> bundle.putLong(key, value)
                is Double -> bundle.putDouble(key, value)
                is Boolean -> bundle.putBoolean(key, value)
                null -> bundle.putString(key, null)
            }
            assertNull("Wrong type for $key", PdfCropSavedState.read(bundle))
        }
    }

    @Test fun `out of range percentages are discarded without clamping`() {
        for (key in keys.takeLast(2)) for (value in listOf(Int.MIN_VALUE, -1, 21, Int.MAX_VALUE)) {
            assertNull("Invalid $key=$value", PdfCropSavedState.read(saved().apply { putInt(key, value) }))
        }
    }

    @Test fun `malformed identity or noncanonical content hash never restores a crop`() {
        for (id in listOf("", " ", "\n\t", "x".repeat(201))) {
            assertNull(PdfCropSavedState.read(saved().apply { putString(keys[0], id) }))
        }
        for (hash in listOf("", "a".repeat(63), "a".repeat(65), "A".repeat(64), "g".repeat(64),
            " " + "a".repeat(64), "a".repeat(64) + "\n")) {
            assertNull(PdfCropSavedState.read(saved().apply { putString(keys[1], hash) }))
        }
    }

    @Test fun `replacement and clearing affect only crop keys leaving unrelated reader settings intact`() {
        val bundle = saved().apply {
            putBoolean("readerPdfNightChoice", true)
            putInt("readerBrightnessPercent", 35)
            putString("readerEpubImageFilter", "DARKEN")
        }
        val replacement = choice.copy(bookId = "next-book", contentSha256 = "b".repeat(64), crop = PdfPageCrop(2, 3))
        PdfCropSavedState.write(bundle, replacement)
        assertEquals(replacement, PdfCropSavedState.read(bundle))
        PdfCropSavedState.write(bundle, null)
        assertNull(PdfCropSavedState.read(bundle)); keys.forEach { assertFalse(bundle.containsKey(it)) }
        assertTrue(bundle.getBoolean("readerPdfNightChoice"))
        assertEquals(35, bundle.getInt("readerBrightnessPercent"))
        assertEquals("DARKEN", bundle.getString("readerEpubImageFilter"))
    }

    @Test fun `applied crop restoration matches publication identity across metadata changes but rejects other content or format`() {
        val book = Book(choice.bookId, "PDF title", "Author", BookFormat.PDF,
            "book.pdf", "book.pdf", 1, choice.contentSha256, 1, pageCount = 12)
        val restored = checkNotNull(PdfCropSavedState.read(saved()))
        assertTrue(restored.matches(book))
        assertTrue(restored.matches(book.copy(title = "Edited metadata", authors = "Another author", lastReadAt = 20)))
        assertFalse(restored.matches(book.copy(id = "other-book")))
        assertFalse(restored.matches(book.copy(sha256 = "b".repeat(64))))
        assertFalse(restored.matches(book.copy(format = BookFormat.EPUB, pageCount = null)))
    }

    private fun saved() = Bundle().apply { PdfCropSavedState.write(this, choice) }
}
