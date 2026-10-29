@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)

package com.yaprak.reader.formats

import android.app.Application
import com.yaprak.reader.domain.reader.ReadingLocation
import com.yaprak.reader.fixtures.TestBooks
import com.yaprak.reader.formats.epub.EpubBookPosition
import com.yaprak.reader.formats.epub.EpubPublicationSource
import com.yaprak.reader.formats.epub.decodeEpubBookPositionLocation
import com.yaprak.reader.formats.epub.decodeEpubLocation
import com.yaprak.reader.formats.epub.epubBookPositionLocation
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.LocalizedString
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Manifest
import org.readium.r2.shared.publication.Metadata
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.PositionsService
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.mediatype.MediaType
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class EpubBookPositionNavigationTest {
    @get:Rule val temp = TemporaryFolder()
    private val first = Url("EPUB/first.xhtml")!!
    private val second = Url("EPUB/second.xhtml")!!
    private val order = listOf(Link(first, MediaType.XHTML), Link(second, MediaType.XHTML))

    private fun position(href: String = first.toString(), progression: Double? = 0.0,
        total: Double? = 0.0, index: Int = 1) = Locator(Url(href)!!, MediaType.XHTML,
        locations = Locator.Locations(progression = progression, totalProgression = total, position = index))

    private fun publication(fixed: Boolean = false,
        loadPositions: suspend () -> List<List<Locator>>): Publication = Publication(
        manifest = Manifest(metadata = Metadata(localizedTitle = LocalizedString("Local test"),
            otherMetadata = if (fixed) mapOf("presentation" to mapOf("layout" to "fixed")) else emptyMap()),
            readingOrder = order),
        servicesBuilder = Publication.ServicesBuilder(positions = {
            object : PositionsService {
                override suspend fun positionsByReadingOrder() = loadPositions()
            }
        }),
    )

    @Test fun `real EPUB positions resolve endpoints and interior percentage to a valid local locator`() = runBlocking {
        val opened = EpubPublicationSource(RuntimeEnvironment.getApplication().contentResolver)
            .open(TestBooks.epub(temp.newFile("percentage.epub")))
        opened.use {
            val resolver = EpubBookPosition(opened.publication)
            assertTrue(resolver.available)
            listOf(0, 37, 100).forEach { percent ->
                val location = resolver.resolvePercent(percent)
                assertNotNull(location)
                val locator = decodeEpubLocation(location, opened.publication.readingOrder)!!
                assertEquals(percent / 100.0, locator.locations.totalProgression!!, 0.0000001)
                assertTrue(locator.locations.progression!! in 0.0..1.0)
                assertTrue(opened.publication.readingOrder.any {
                    it.url().removeFragment() == locator.href.removeFragment()
                })
                assertEquals(Locator.Text(), locator.text)
            }
        }
    }

    @Test fun `real Readium locator service maps whole book positions across resources on a worker`() = runBlocking {
        val caller = Thread.currentThread()
        var serviceThread: Thread? = null
        val publication = publication {
            serviceThread = Thread.currentThread()
            listOf(listOf(position(), position(progression = 0.5, total = 0.25, index = 2)),
                listOf(position(second.toString(), total = 0.5, index = 3),
                    position(second.toString(), progression = 0.5, total = 0.75, index = 4)))
        }
        try {
            val resolver = EpubBookPosition(publication)
            listOf(Triple(0, first, 0.0), Triple(25, first, 0.5), Triple(50, second, 0.0),
                Triple(75, second, 0.5), Triple(100, second, 1.0)).forEach { (percent, href, progression) ->
                val location = resolver.resolvePercent(percent)!!
                val locator = decodeEpubBookPositionLocation(location, order)!!
                assertEquals(href, locator.href)
                assertEquals(progression, locator.locations.progression!!, 0.0000001)
                assertEquals(percent / 100.0, locator.locations.totalProgression!!, 0.0000001)
            }
            assertNotNull(serviceThread)
            assertNotSame(caller, serviceThread)
        } finally { publication.close() }
    }

    @Test fun `invalid percentage and fixed layout never request publication positions`() = runBlocking {
        val reflow = publication { error("Invalid input must not read positions") }
        val fixed = publication(fixed = true) { error("Fixed layout must not read positions") }
        try {
            val resolver = EpubBookPosition(reflow)
            listOf(Int.MIN_VALUE, -1, 101, Int.MAX_VALUE).forEach { assertNull(resolver.resolvePercent(it)) }
            assertFalse(EpubBookPosition(fixed).available)
            listOf(0, 50, 100).forEach { assertNull(EpubBookPosition(fixed).resolvePercent(it)) }
        } finally { reflow.close(); fixed.close() }
    }

    @Test fun `missing or empty position services yield no fabricated destination`() = runBlocking {
        val noService = Publication(Manifest(metadata = Metadata(localizedTitle = LocalizedString("No service")),
            readingOrder = order))
        val empty = publication { emptyList() }
        try {
            listOf(noService, empty).forEach { publication ->
                listOf(0, 50, 100).forEach { assertNull(EpubBookPosition(publication).resolvePercent(it)) }
            }
        } finally { noService.close(); empty.close() }
    }

    @Test fun `foreign absolute protocol relative and malformed targets are rejected before navigation`() {
        listOf("EPUB/missing.xhtml", "https://example.org/first.xhtml", "//example.org/first.xhtml").forEach {
            assertNull(epubBookPositionLocation(position(href = it), order))
        }
        val valid = epubBookPositionLocation(position(), order)!!
        assertNull(decodeEpubBookPositionLocation(valid.copy(resourceHref = "EPUB/other.xhtml"), order))
        assertNull(decodeEpubBookPositionLocation(valid.copy(locatorVersion = 2), order))
        assertNull(decodeEpubBookPositionLocation(ReadingLocation("not JSON", first.toString()), order))
        assertNull(decodeEpubBookPositionLocation(valid.copy(locatorJson = " ".repeat(64_001)), order))
    }

    @Test fun `missing nonfinite and outside progression never become valid percentage targets`() {
        listOf(null, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -0.1, 1.1).forEach {
            assertNull(epubBookPositionLocation(position(progression = it), order))
            assertNull(epubBookPositionLocation(position(total = it), order))
        }
        assertNull(epubBookPositionLocation(position(index = 0), order))
        val invalid = ReadingLocation("""{"href":"EPUB/first.xhtml","type":"application/xhtml+xml",
            "locations":{"progression":1.2,"totalProgression":0.5}}""", first.toString())
        assertNull(decodeEpubBookPositionLocation(invalid, order))
    }

    @Test fun `percentage destination strips competing fragment targets and private text context`() {
        val source = position(href = "EPUB/first.xhtml#old", progression = 0.4, total = 0.2).copy(
            title = "Old title", text = Locator.Text("private before", "old target", "private after"),
            locations = Locator.Locations(fragments = listOf("old"), progression = 0.4, totalProgression = 0.2,
                position = 1, otherLocations = mapOf("cssSelector" to "#old", "htmlId" to "old")))
        val location = epubBookPositionLocation(source, order)!!
        val decoded = decodeEpubBookPositionLocation(location, order)!!
        assertEquals(first, decoded.href)
        assertEquals(0.4, decoded.locations.progression!!, 0.0000001)
        assertEquals(0.2, decoded.locations.totalProgression!!, 0.0000001)
        assertEquals(1, decoded.locations.position)
        assertTrue(decoded.locations.fragments.isEmpty() && decoded.locations.otherLocations.isEmpty())
        assertEquals(Locator.Text(), decoded.text)
        assertNull(decoded.title)
        assertFalse(location.locatorJson.contains("private"))
        assertFalse(location.locatorJson.contains("old"))
    }

    @Test fun `publication read failure propagates rather than creating a fallback jump`() = runBlocking {
        val publication = publication { throw IOException("Positions unavailable") }
        try {
            try {
                EpubBookPosition(publication).resolvePercent(50)
                fail("Expected the real read failure")
            } catch (expected: IOException) {
                assertEquals("Positions unavailable", expected.message)
            }
        } finally { publication.close() }
    }

    @Test fun `cancelled resolution cancels position loading without returning a destination`() = runTest {
        val read = CompletableDeferred<List<List<Locator>>>()
        var entered = false
        var cancelled = false
        val publication = publication {
            entered = true
            try { read.await() } finally { cancelled = true }
        }
        try {
            val resolver = EpubBookPosition(publication, StandardTestDispatcher(testScheduler))
            val pending = async { resolver.resolvePercent(50) }
            runCurrent()
            assertTrue(entered)
            pending.cancelAndJoin()
            assertTrue(pending.isCancelled)
            assertTrue(cancelled)
            assertFalse(read.isCompleted)
        } finally { publication.close() }
    }
}
