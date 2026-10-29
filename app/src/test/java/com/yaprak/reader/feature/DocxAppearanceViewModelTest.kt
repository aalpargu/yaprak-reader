package com.yaprak.reader.feature

import androidx.lifecycle.ViewModelStore
import com.yaprak.reader.domain.library.*
import com.yaprak.reader.domain.settings.*
import com.yaprak.reader.feature.settings.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DocxAppearanceViewModelTest {
    @Test fun everyReaderAppearanceEditorBindsDocxAndWritesOnlyItsCurrentIdentityThenResetCanComplete() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler)); val owner = ViewModelStore()
        val book = Book("text", "DOCX", null, BookFormat.DOCX, "source.docx", "text.docx", 1, "a".repeat(64), 1)
        val writes = mutableListOf<String>()
        fun write(name: String, id: String, hash: String) { assertEquals(book.id, id); assertEquals(book.sha256, hash); writes += name }
        try {
            val theme = BookThemeViewModel(object : BookThemeRepository {
                override fun observe(bookId: String, contentSha256: String) = flowOf<ReadingTheme?>(null)
                override suspend fun save(bookId: String, contentSha256: String, theme: ReadingTheme?) = write("theme", bookId, contentSha256)
            }).also { owner.put("theme", it); it.bind(book) }
            val font = BookFontViewModel(object : BookFontRepository {
                override fun observe(bookId: String, contentSha256: String) = flowOf<ReadingFont?>(null)
                override suspend fun save(bookId: String, contentSha256: String, font: ReadingFont?) = write("font", bookId, contentSha256)
            }).also { owner.put("font", it); it.bind(book) }
            val size = BookFontSizeViewModel(object : BookFontSizeRepository {
                override fun observe(bookId: String, contentSha256: String) = flowOf<Int?>(null)
                override suspend fun save(bookId: String, contentSha256: String, fontSizePercent: Int?) = write("size", bookId, contentSha256)
            }).also { owner.put("size", it); it.bind(book) }
            val margin = BookMarginsViewModel(object : BookMarginsRepository {
                override fun observe(bookId: String, contentSha256: String) = flowOf<Int?>(null)
                override suspend fun save(bookId: String, contentSha256: String, marginsPercent: Int?) = write("margin", bookId, contentSha256)
            }).also { owner.put("margin", it); it.bind(book) }
            val line = BookLineHeightViewModel(object : BookLineHeightRepository {
                override fun observe(bookId: String, contentSha256: String) = flowOf<BookLineHeightChoice>(BookLineHeightChoice.Inherit)
                override suspend fun save(bookId: String, contentSha256: String, choice: BookLineHeightChoice) = write("line", bookId, contentSha256)
            }).also { owner.put("line", it); it.bind(book) }
            val spacing = BookSpacingViewModel(object : BookSpacingRepository {
                override fun observe(bookId: String, contentSha256: String) = flowOf(BookSpacingSettings())
                override suspend fun save(bookId: String, contentSha256: String, choice: BookSpacingSettings) = write("spacing", bookId, contentSha256)
            }).also { owner.put("spacing", it); it.bind(book) }
            val alignment = BookTextAlignmentViewModel(object : BookTextAlignmentRepository {
                override fun observe(bookId: String, contentSha256: String) = flowOf<ReadingTextAlignment?>(null)
                override suspend fun save(bookId: String, contentSha256: String, alignment: ReadingTextAlignment?) = write("alignment", bookId, contentSha256)
            }).also { owner.put("alignment", it); it.bind(book) }
            val mode = BookReadingModeViewModel(object : BookReadingModeRepository {
                override fun observe(bookId: String, contentSha256: String) = flowOf<BookReadingMode?>(null)
                override suspend fun save(bookId: String, contentSha256: String, mode: BookReadingMode?) = write("mode", bookId, contentSha256)
            }).also { owner.put("mode", it); it.bind(book) }
            val reset = BookAppearanceResetViewModel(object : BookAppearanceResetRepository {
                override suspend fun reset(bookId: String, contentSha256: String) = write("reset", bookId, contentSha256)
            })
                .also { owner.put("reset", it); it.bind(book) }
            runCurrent(); assertTrue(writes.isEmpty())
            assertFalse(theme.state.value.loading); assertFalse(font.state.value.loading); assertFalse(size.state.value.loading)
            assertFalse(margin.state.value.loading); assertFalse(line.state.value.loading); assertFalse(spacing.state.value.loading)
            assertFalse(alignment.state.value.loading); assertFalse(mode.state.value.loading)
            theme.open(); theme.edit(ReadingTheme.AMOLED); theme.save()
            font.open(); font.edit(ReadingFont.SERIF); font.save()
            size.open(); size.edit(125); size.save()
            margin.open(); margin.edit(150); margin.save()
            line.open(); line.edit(BookLineHeightChoice.Custom(170)); line.save()
            spacing.open(); spacing.edit(BookSpacingSettings(BookSpacingChoice.Custom(200), BookSpacingChoice.Custom(100))); spacing.save()
            alignment.open(); alignment.edit(ReadingTextAlignment.START); alignment.save()
            mode.open(); mode.edit(BookReadingMode.PAGED); mode.save()
            runCurrent(); assertEquals(listOf("theme", "font", "size", "margin", "line", "spacing", "alignment", "mode"), writes)
            reset.open(); reset.confirm(); runCurrent(); assertTrue(reset.state.value.completed)
            assertEquals("reset", writes.last())
        } finally { owner.clear(); Dispatchers.resetMain() }
    }
}
