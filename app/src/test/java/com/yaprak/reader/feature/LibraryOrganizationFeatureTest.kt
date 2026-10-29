package com.yaprak.reader.feature

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.yaprak.reader.domain.library.*
import com.yaprak.reader.feature.library.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryOrganizationFeatureTest {
    private val organized = Book("a", "Dependency Injection", "Deniz", BookFormat.EPUB,
        "a.epub", "a.epub", 10, "hash-a", 1, organization = BookOrganization(
            BookReadingStatus.READING, true, "IŞIK", "Teknik", listOf("Kotlin", "Mimari"), listOf("Ders")))
    private val other = organized.copy(id = "b", sha256 = "hash-b", format = BookFormat.PDF,
        organization = BookOrganization(BookReadingStatus.TO_READ))
    private val importer = object : BookImporter {
        override suspend fun import(documentUri: String): ImportResult = error("Organization must not import")
    }

    @Test fun `organization filters intersect and preserve source records`() {
        val source = listOf(other, organized)
        val filters = LibraryOrganizationFilters(author = "DENİZ", series = "ışık", genre = "teknik",
            tag = "KOTLIN", collection = "ders", readingStatus = BookReadingStatus.READING, favoritesOnly = true)
        assertEquals(listOf(organized), filterOrganizationBooks(source, filters))
        assertTrue(filterOrganizationBooks(source, filters.copy(collection = "Eksik")).isEmpty())
        assertEquals(listOf(other, organized), source)
        assertSame(source, filterOrganizationBooks(source, LibraryOrganizationFilters()))
        assertEquals(listOf(other), filterOrganizationBooks(source,
            LibraryOrganizationFilters(readingStatus = BookReadingStatus.TO_READ)))
    }

    @Test fun `metadata search covers series genre tags and collections across the same book`() {
        val source = listOf(organized, other)
        assertEquals(listOf(organized), searchBooks(source, "injection ışık teknik kotlin ders"))
        assertTrue(searchBooks(source, "kotlin olmayan").isEmpty())
        assertTrue(searchBooks(source, "a.epub").isEmpty())
        assertEquals(listOf(organized, other), source)
    }

    @Test fun `hundreds of records preserve identity under combined metadata filters`() {
        val source = (0 until 750).map { number -> organized.copy(id = "$number", sha256 = "hash-$number",
            organization = organized.organization.copy(
                readingStatus = if (number % 3 == 0) BookReadingStatus.COMPLETED else BookReadingStatus.READING,
                favorite = number % 2 == 0)) }
        val actual = filterOrganizationBooks(searchBooks(source, "MİMARİ DERS"),
            LibraryOrganizationFilters(readingStatus = BookReadingStatus.COMPLETED, favoritesOnly = true))
        assertEquals((0 until 750 step 6).map(Int::toString), actual.map(Book::id))
        assertTrue(actual.all { source[it.id.toInt()] === it })
    }

    @Test fun `saving organization updates repository and active filter without touching identity`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val owner = ViewModelStore()
        try {
            val original = organized.copy(organization = BookOrganization())
            val repository = Repository(listOf(original, other))
            val model = LibraryViewModel(repository, importer, SavedStateHandle(), dispatcher).also { owner.put("model", it) }
            backgroundScope.launch(dispatcher) { model.library.collect() }
            model.chooseOrganizationFilters(LibraryOrganizationFilters(favoritesOnly = true))
            runCurrent()
            assertTrue((model.library.value as LibraryState.Ready).books.isEmpty())
            model.saveBookOrganization(original, organized.organization.copy(series = " IŞIK "))
            runCurrent()
            assertTrue(model.organizationEdit.value is BookOrganizationEditState.Saved)
            assertEquals(listOf(organized), (model.library.value as LibraryState.Ready).books)
            assertEquals(listOf(organized, other), repository.books.value)
            assertEquals(1, repository.organizationWrites)
            assertEquals(0, repository.metadataWrites)
            assertEquals(organized.id, repository.books.value.first().id)
            // A previously rendered editor with the same id/hash cannot overwrite a newer organization.
            model.saveBookOrganization(original, BookOrganization())
            runCurrent()
            assertTrue(model.organizationEdit.value is BookOrganizationEditState.Failed)
            assertEquals(1, repository.organizationWrites)
            assertEquals(organized, repository.books.value.first())
        } finally { owner.clear(); Dispatchers.resetMain() }
    }

    @Test fun `invalid input rejected hash and busy picker never report saved organization`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val owner = ViewModelStore()
        try {
            val repository = Repository(listOf(organized))
            val model = LibraryViewModel(repository, importer, SavedStateHandle(), dispatcher).also { owner.put("model", it) }
            backgroundScope.launch(dispatcher) { model.library.collect() }
            runCurrent()
            model.saveBookOrganization(organized, organized.organization.copy(series = "x".repeat(201)))
            runCurrent()
            assertTrue(model.organizationEdit.value is BookOrganizationEditState.Failed)
            assertEquals(0, repository.organizationWrites)
            model.clearBookOrganizationState(organized.id, organized.sha256)
            model.saveBookOrganization(organized.copy(sha256 = "stale"), organized.organization.copy(favorite = false))
            runCurrent()
            assertFalse(model.organizationEdit.value is BookOrganizationEditState.Saved)
            assertEquals(organized, repository.books.value.single())
            model.clearBookOrganizationState(organized.id, "stale")
            val writesBefore = repository.organizationWrites
            assertTrue(model.prepareImport())
            model.saveBookOrganization(organized, organized.organization.copy(favorite = false))
            runCurrent()
            assertEquals(writesBefore, repository.organizationWrites)
            assertFalse(model.organizationEdit.value is BookOrganizationEditState.Saved)
            model.onImportDocumentResult(emptyList())
            repository.reject = true
            model.saveBookOrganization(organized, organized.organization.copy(favorite = false))
            runCurrent()
            assertTrue(model.organizationEdit.value is BookOrganizationEditState.Failed)
            repository.reject = false
            model.saveBookOrganization(organized, organized.organization.copy(favorite = false))
            runCurrent()
            assertTrue(model.organizationEdit.value is BookOrganizationEditState.Saved)
            assertFalse(repository.books.value.single().organization.favorite)
        } finally { owner.clear(); Dispatchers.resetMain() }
    }

    @Test fun `saved filters survive model recreation and clear resets every search condition`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val firstOwner = ViewModelStore()
        val secondOwner = ViewModelStore()
        try {
            val saved = SavedStateHandle()
            val repository = Repository(listOf(organized, other))
            val first = LibraryViewModel(repository, importer, saved, dispatcher).also { firstOwner.put("model", it) }
            first.chooseOrganizationFilters(LibraryOrganizationFilters(tag = "Kotlin", favoritesOnly = true))
            first.chooseFilter(LibraryFilter.EPUB)
            first.search("injection")
            firstOwner.clear()
            val restored = LibraryViewModel(repository, importer, saved, dispatcher).also { secondOwner.put("model", it) }
            backgroundScope.launch(dispatcher) { restored.library.collect() }
            runCurrent()
            assertEquals(listOf(organized), (restored.library.value as LibraryState.Ready).books)
            assertEquals(2, (restored.library.value as LibraryState.Ready).allBooks.size)
            assertTrue(restored.organizationFilters.value.favoritesOnly)
            restored.clearFilters()
            runCurrent()
            assertEquals("", restored.query.value)
            assertEquals(LibraryFilter.ALL, restored.filter.value)
            assertFalse(restored.organizationFilters.value.isActive)
            assertEquals(2, (restored.library.value as LibraryState.Ready).books.size)
        } finally { firstOwner.clear(); secondOwner.clear(); Dispatchers.resetMain() }
    }

    private class Repository(initial: List<Book>) : LibraryRepository {
        val books = MutableStateFlow(initial)
        var organizationWrites = 0
        var metadataWrites = 0
        var reject = false
        override fun observeBooks(): Flow<List<Book>> = books
        override suspend fun findById(id: String) = books.value.singleOrNull { it.id == id }
        override suspend fun findByHash(sha256: String) = books.value.singleOrNull { it.sha256 == sha256 }
        override suspend fun storedFileNames() = books.value.map(Book::storageName).toSet()
        override suspend fun add(book: Book) { books.value += book }
        override suspend fun updateMetadata(id: String, sha256: String, title: String, authors: String?): Boolean {
            metadataWrites++
            return false
        }
        override suspend fun updateOrganization(id: String, sha256: String, organization: BookOrganization): Boolean {
            organizationWrites++
            if (reject || books.value.none { it.id == id && it.sha256 == sha256 }) return false
            books.value = books.value.map { if (it.id == id && it.sha256 == sha256) it.copy(organization = organization) else it }
            return true
        }
    }
}
