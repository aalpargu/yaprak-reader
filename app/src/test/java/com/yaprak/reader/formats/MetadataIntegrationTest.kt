package com.yaprak.reader.formats

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.yaprak.reader.domain.library.BookImportException
import com.yaprak.reader.domain.library.ImportFailure
import com.yaprak.reader.fixtures.TestBooks
import com.yaprak.reader.formats.epub.EpubMetadataReader
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MetadataIntegrationTest {
    @get:Rule val temp = TemporaryFolder()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Test fun `real EPUB metadata comes from Readium`() = runBlocking {
        val metadata = EpubMetadataReader(context.contentResolver).read(TestBooks.epub(temp.newFile("book.epub")))
        assertEquals("Bir Yaz Sabahı", metadata.title)
        assertEquals("Deniz Yazar", metadata.authors)
        assertNull(metadata.pageCount)
    }

    @Test fun `DRM EPUB rejected rather than claimed readable`() = runBlocking {
        val error = try {
            EpubMetadataReader(context.contentResolver).read(TestBooks.epub(temp.newFile("book.epub"), encrypted = true))
            null
        } catch (error: BookImportException) { error }
        assertEquals(ImportFailure.PROTECTED, error?.reason)
    }

    @Test fun `corrupt EPUB rejected`() = runBlocking {
        val file = temp.newFile("bad.epub").apply { writeText("PK\u0003\u0004broken") }
        val error = try {
            EpubMetadataReader(context.contentResolver).read(file)
            null
        } catch (error: BookImportException) { error }
        assertEquals(ImportFailure.INVALID, error?.reason)
    }

}
