package com.yaprak.reader.formats

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.yaprak.reader.domain.library.*
import com.yaprak.reader.fixtures.TestBooks
import com.yaprak.reader.formats.epub.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.readium.r2.shared.publication.Metadata
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class EpubImportedOrganizationTest {
    @get:Rule val temp = TemporaryFolder()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private fun metadata(series: List<String> = emptyList(), subjects: List<String> = emptyList(),
        collections: List<String> = emptyList()): Metadata = requireNotNull(Metadata.fromJSON(
        JSONObject().put("title", "A title that is not a series").put("author", JSONArray(listOf("Not a series either")))
            .put("subject", JSONArray(subjects)).put("belongsTo", JSONObject()
                .put("series", JSONArray(series)).put("collection", JSONArray(collections)))))

    @Test fun declaredSeriesSubjectsAndCollectionsUseExistingOrganizationWithoutReadingState() {
        val actual = initialEpubOrganization(metadata(listOf("  Seri 🌿  "),
            listOf(" Yazılım ", "Kotlin"), listOf(" Başvuru ")))
        assertEquals(BookOrganization(series = "Seri 🌿", tags = listOf("Yazılım", "Kotlin"),
            collections = listOf("Başvuru")), actual)
        assertNull(actual.genre)
        assertEquals(BookReadingStatus.UNSET, actual.readingStatus); assertFalse(actual.favorite)
    }
    @Test fun distinctSeriesAreAmbiguousAndTurkishDuplicateLabelsKeepFirstDeclaredName() {
        assertNull(initialEpubOrganization(metadata(series = listOf("Bir", "İki"))).series)
        val actual = initialEpubOrganization(metadata(listOf(" BİLGİ ", "bılgı"),
            listOf(" BİLGİ ", "bilgi", "bılgı", "Diğer"), listOf("Ders", "DERS")))
        assertEquals("BİLGİ", actual.series)
        assertEquals(listOf("BİLGİ", "Diğer"), actual.tags)
        assertEquals(listOf("Ders"), actual.collections)
    }
    @Test fun invalidOptionalNamesAreOmittedRatherThanSplitTruncatedOrInvented() {
        val invalid = listOf("", "   ", "bad\nname", "x".repeat(101), "İki, ayrı konu")
        val actual = initialEpubOrganization(metadata(listOf("x".repeat(201), "Seri"),
            invalid + "Doğru", invalid + "Raf"))
        assertEquals("Seri", actual.series)
        assertEquals(listOf("Doğru"), actual.tags); assertEquals(listOf("Raf"), actual.collections)
        assertNull(actual.genre)
    }
    @Test fun firstFiftyValidLabelsFitEditorAndBackupBounds() {
        val names = listOf("bad\nname") + (0 until 60).map { "Konu$it" }
        val actual = initialEpubOrganization(metadata(subjects = names, collections = names))
        assertEquals((0 until 50).map { "Konu$it" }, actual.tags)
        assertEquals(actual.tags, actual.collections); assertEquals(actual, actual.normalized())
    }
    @Test fun actualEpubThreeMetadataIsParsedByPinnedReadium() = runBlocking {
        val extra = """<dc:subject>Yazılım</dc:subject><dc:subject>Kotlin</dc:subject>
            <meta property="belongs-to-collection" id="series">Yaprak Serisi</meta>
            <meta refines="#series" property="collection-type">series</meta>
            <meta property="belongs-to-collection" id="collection">Teknik kitaplar</meta>
            <meta refines="#collection" property="collection-type">set</meta>"""
        val file = TestBooks.epub(temp.newFile("declared.epub"), additionalMetadata = extra)
        val actual = EpubMetadataReader(context.contentResolver).read(file)
        assertEquals("Bir Yaz Sabahı", actual.title); assertEquals("Deniz Yazar", actual.authors)
        assertEquals(BookOrganization(series = "Yaprak Serisi", tags = listOf("Yazılım", "Kotlin"),
            collections = listOf("Teknik kitaplar")), actual.initialOrganization)
    }
    @Test fun absentPublicationMetadataDoesNotUseTitleAuthorOrFileNameAsLabels() = runBlocking {
        val file = TestBooks.epub(temp.newFile("series-fantasy-collection.epub"))
        val actual = EpubMetadataReader(context.contentResolver).read(file)
        assertEquals(BookOrganization(), actual.initialOrganization)
    }
}
