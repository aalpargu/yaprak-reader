package com.yaprak.reader.feature

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import com.yaprak.reader.R
import com.yaprak.reader.domain.library.*
import com.yaprak.reader.feature.library.*
import com.yaprak.reader.ui.theme.YaprakTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w320dp-h480dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LibraryOrganizationUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val book = Book("one", "Bir Kitap", "Deniz", BookFormat.EPUB,
        "one.epub", "one.epub", 20, "current-hash", 1)

    @Test fun `organization form saves actual normalized draft with large type and small screen`() {
        var saved: BookOrganization? = null
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                YaprakTheme { BookOrganizationDialog(book, false, false, true, { saved = it }, {}) }
            }
        }
        compose.onNodeWithText(text(R.string.reading_status_reading)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.organization_favorite)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.organization_series)).performScrollTo().performTextReplacement(" Işık ")
        compose.onNodeWithText(text(R.string.organization_genre)).performScrollTo().performTextReplacement(" Teknik ")
        compose.onNodeWithText(text(R.string.organization_tags)).performScrollTo().performTextReplacement("Kotlin, kotlin, Mimari")
        compose.onNodeWithText(text(R.string.organization_collections)).performScrollTo().performTextReplacement("Ders, Okul")
        assertNull(saved)
        compose.onNodeWithText(text(R.string.organization_save)).performScrollTo().assertIsDisplayed().performClick()
        assertEquals(BookOrganization(BookReadingStatus.READING, true, "Işık", "Teknik",
            listOf("Kotlin", "Mimari"), listOf("Ders", "Okul")), saved)
    }

    @Test fun `error retains restored draft cancellation does not save and busy form cannot resubmit`() {
        var saving by mutableStateOf(false)
        var failed by mutableStateOf(false)
        var writes = 0
        var dismissed = 0
        val restoration = StateRestorationTester(compose)
        restoration.setContent { YaprakTheme {
            BookOrganizationDialog(book, saving, failed, true, { writes++; failed = true }, { dismissed++ })
        } }
        compose.onNodeWithText(text(R.string.organization_series)).performScrollTo().performTextReplacement("Taslak seri")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Taslak seri").performScrollTo().assertExists()
        compose.onNodeWithText(text(R.string.organization_save)).performScrollTo().performClick()
        assertEquals(1, writes)
        compose.onNodeWithText(text(R.string.organization_save_failed)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Taslak seri").performScrollTo().assertExists()
        compose.runOnIdle { saving = true }
        compose.onNodeWithText(text(R.string.organization_save)).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.cancel_remove)).performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { saving = false }
        compose.onNodeWithText(text(R.string.cancel_remove)).performScrollTo().performClick()
        assertEquals(1, writes)
        assertEquals(1, dismissed)
    }

    @Test fun `filter choices use all source books and apply remains reachable with large type`() {
        val source = listOf(book.copy(organization = BookOrganization(BookReadingStatus.READING, true,
            "Seri", "Teknik", listOf("Kotlin"), listOf("Ders"))),
            book.copy(id = "other", authors = "Başka", organization = BookOrganization(tags = listOf("Diğer"))))
        var applied: LibraryOrganizationFilters? = null
        var resets = 0
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                YaprakTheme { LibraryOrganizationFiltersDialog(source, LibraryOrganizationFilters(),
                    { applied = it }, {}, { resets++ }) }
            }
        }
        compose.onNodeWithContentDescription(text(R.string.organization_filter_field,
            text(R.string.organization_tag))).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.organization_search_choices)).performTextReplacement("Diğer")
        compose.waitUntil(10_000) {
            compose.onAllNodes(choice("Diğer")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(choice("Diğer"))
        compose.onNode(choice("Diğer")).assertIsDisplayed()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(text(R.string.organization_search_choices)))
        compose.onNodeWithText(text(R.string.organization_search_choices)).performTextReplacement("Kotlin")
        compose.waitUntil(10_000) {
            compose.onAllNodes(choice("Kotlin")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(choice("Kotlin"))
        compose.onNode(choice("Kotlin")).performClick()
        compose.onNodeWithText(text(R.string.reading_status_reading)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.organization_favorites_only)).performScrollTo().performClick()
        assertNull(applied)
        compose.onNodeWithText(text(R.string.organization_apply_filters)).performScrollTo().assertIsDisplayed().performClick()
        assertEquals(LibraryOrganizationFilters(tag = "Kotlin", readingStatus = BookReadingStatus.READING,
            favoritesOnly = true), applied)
        compose.onNodeWithText(text(R.string.organization_reset_filters)).performScrollTo().performClick()
        assertEquals(1, resets)
    }

    @Test fun `many metadata choices stay lazy and searchable`() {
        val source = (0 until 500).map { number -> book.copy(id = "$number",
            organization = BookOrganization(tags = (0 until 20).map { "Etiket$number-$it" })) }
        var applied: LibraryOrganizationFilters? = null
        compose.setContent { YaprakTheme {
            LibraryOrganizationFiltersDialog(source, LibraryOrganizationFilters(), { applied = it }, {}, {})
        } }
        compose.onNodeWithContentDescription(text(R.string.organization_filter_field,
            text(R.string.organization_tag))).performScrollTo().performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodes(choice("Etiket0-0")).fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(compose.onAllNodes(hasText("Etiket", substring = true))
            .fetchSemanticsNodes().size < 50)
        compose.onNodeWithText(text(R.string.organization_search_choices))
            .performTextReplacement("Etiket499-19")
        compose.waitUntil(10_000) {
            compose.onAllNodes(choice("Etiket499-19")).fetchSemanticsNodes().size == 1
        }
        compose.onNode(choice("Etiket499-19")).performClick()
        compose.onNodeWithText(text(R.string.organization_apply_filters)).performScrollTo().performClick()
        assertEquals("Etiket499-19", applied?.tag)
    }

    private fun text(id: Int, vararg args: Any): String = compose.activity.getString(id, *args)
    private fun choice(value: String): SemanticsMatcher = hasText(value) and hasClickAction() and !hasSetTextAction()
}
