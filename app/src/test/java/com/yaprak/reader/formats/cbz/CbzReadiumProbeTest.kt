package com.yaprak.reader.formats.cbz

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.yaprak.reader.fixtures.CoverTestImages
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.positions
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.file.FileResource
import org.readium.r2.shared.util.getOrElse
import org.readium.r2.shared.util.http.DefaultHttpClient
import org.readium.r2.shared.util.resource.borrow
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.image.ImageParser
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The pinned parser's actual order is lexical. Native CBZ deliberately owns natural page order. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CbzReadiumProbeTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun pinnedParserActuallyOpensOriginalArchiveResourcesAndDivinaPositions() = runBlocking(Dispatchers.IO) {
        val file = temp.newFile("original.cbz")
        ZipOutputStream(file.outputStream()).use { zip ->
            for (name in listOf("pages/2.png", "pages/10.png", "pages/1.png")) {
                zip.putNextEntry(ZipEntry(name)); zip.write(CoverTestImages.png()); zip.closeEntry()
            }
        }
        val context = ApplicationProvider.getApplicationContext<Context>()
        val retriever = AssetRetriever(context.contentResolver, DefaultHttpClient())
        val resource = FileResource(file)
        try {
            val asset = retriever.retrieve(resource.borrow()).getOrElse { error("Asset refused") }
            try {
                val publication = PublicationOpener(ImageParser(retriever)).open(asset, allowUserInteraction = false)
                    .getOrElse { error("Publication refused") }
                try {
                    assertTrue(publication.conformsTo(Publication.Profile.DIVINA))
                    assertEquals(listOf("pages/1.png", "pages/10.png", "pages/2.png"), publication.readingOrder.map { link -> link.url().toString() })
                    val positions = publication.positions(); assertEquals(3, positions.size)
                    assertEquals(publication.readingOrder.map { link -> link.url() }, positions.map { locator -> locator.href })
                    for (link in publication.readingOrder) {
                        val bytes = publication.get(link)!!.read().getOrElse { error("Resource refused") }
                        assertArrayEquals(CoverTestImages.png(), bytes)
                    }
                } finally { publication.close() }
            } finally { asset.close() }
        } finally { resource.close() }
    }
}
