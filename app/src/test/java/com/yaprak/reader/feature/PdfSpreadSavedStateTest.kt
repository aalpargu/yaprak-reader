package com.yaprak.reader.feature

import android.app.Application
import android.os.Bundle
import android.os.Parcel
import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.feature.reader.PdfSpreadChoice
import com.yaprak.reader.feature.reader.PdfSpreadSavedState
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PdfSpreadSavedStateTest {
    private val choice = PdfSpreadChoice("pdf-book", "a".repeat(64), true)
    private val keys = listOf("readerPdfSpreadBook", "readerPdfSpreadHash", "readerPdfSpreadEnabled")

    @Test fun `applied Boolean survives Android Parcel and clearing preserves unrelated settings`() {
        for (enabled in listOf(true, false)) {
            val bundle = Bundle().apply {
                putInt("readerBrightnessPercent", 35)
                PdfSpreadSavedState.write(this, choice.copy(enabled = enabled))
            }
            val parcel = Parcel.obtain()
            try {
                parcel.writeBundle(bundle); parcel.setDataPosition(0)
                val restored = parcel.readBundle(javaClass.classLoader)!!
                assertEquals(choice.copy(enabled = enabled), PdfSpreadSavedState.read(restored))
                PdfSpreadSavedState.write(restored, null)
                assertNull(PdfSpreadSavedState.read(restored)); assertEquals(35, restored.getInt("readerBrightnessPercent"))
                keys.forEach { assertFalse(restored.containsKey(it)) }
            } finally { parcel.recycle() }
        }
    }

    @Test fun `missing malformed or coerced identity and Boolean values are rejected`() {
        fun saved() = Bundle().apply { PdfSpreadSavedState.write(this, choice) }
        assertNull(PdfSpreadSavedState.read(null))
        keys.forEach { assertNull(PdfSpreadSavedState.read(saved().apply { remove(it) })) }
        for (value in listOf("", " ", "x".repeat(201)))
            assertNull(PdfSpreadSavedState.read(saved().apply { putString(keys[0], value) }))
        for (value in listOf("a".repeat(63), "A".repeat(64), "a".repeat(64) + "\n"))
            assertNull(PdfSpreadSavedState.read(saved().apply { putString(keys[1], value) }))
        assertNull(PdfSpreadSavedState.read(saved().apply { putString(keys[2], "true") }))
        assertNull(PdfSpreadSavedState.read(saved().apply { putInt(keys[2], 1) }))
        assertNull(PdfSpreadSavedState.read(saved().apply { putLong(keys[2], 1L) }))
        assertNull(PdfSpreadSavedState.read(saved().apply { putString(keys[2], null) }))
        assertNull(PdfSpreadSavedState.read(saved().apply { putInt(keys[0], 12) }))
    }

    @Test fun `restoration follows exact PDF content not mutable title last-read or other publications`() {
        val book = Book(choice.bookId, "PDF", null, BookFormat.PDF, "book.pdf", "book.pdf", 1,
            choice.contentSha256, 1, pageCount = 5)
        assertTrue(choice.matches(book.copy(title = "Edited", authors = "Author", lastReadAt = 22)))
        assertFalse(choice.matches(book.copy(id = "other")))
        assertFalse(choice.matches(book.copy(sha256 = "b".repeat(64))))
        assertFalse(choice.matches(book.copy(format = BookFormat.EPUB)))
    }
}
