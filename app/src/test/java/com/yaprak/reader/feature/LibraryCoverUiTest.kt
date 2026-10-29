package com.yaprak.reader.feature

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
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
class LibraryCoverUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val cover = BookCover("123e4567-e89b-12d3-a456-426614174000",
        "123e4567-e89b-12d3-a456-426614174000.png", "image/png", 10, "b".repeat(64), 20, 30)
    private val book = Book("one", "Bir Kitap", "Deniz", BookFormat.EPUB,
        "one.epub", "one.epub", 20, "a".repeat(64), 1, customCover = cover)

    @Test fun `cover actions remain reachable at two hundred percent type`() {
        var changes = 0
        var resets = 0
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                YaprakTheme { BookDetailsDialog(book, {}, {}, true, {},
                    onChangeCover = { changes++ }, onResetCover = { resets++ }) }
            }
        }
        compose.onNodeWithText(text(R.string.custom_cover_change)).performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText(text(R.string.custom_cover_reset)).performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText(text(R.string.custom_cover_help)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.close)).assertIsDisplayed()
        assertEquals(1, changes)
        assertEquals(1, resets)
    }

    @Test fun `pending and writing cover lock details while errors retain retry actions`() {
        var state by mutableStateOf<CoverEditState>(CoverEditState.Selecting(book.id, book.sha256))
        var resets = 0
        compose.setContent { YaprakTheme { BookDetailsDialog(book, {}, {}, true, {},
            coverEdit = state, onChangeCover = { error("Busy cover change") }, onResetCover = { resets++ }) } }
        compose.onNodeWithText(text(R.string.custom_cover_change)).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.custom_cover_reset)).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.custom_cover_selecting)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Oku").assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.close)).assertIsNotEnabled()
        compose.onNodeWithText("Başlığı ve yazarı düzenle").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.export_book)).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.remove_book)).performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { state = CoverEditState.Changing(book.id, book.sha256) }
        compose.onNodeWithText(text(R.string.custom_cover_changing)).performScrollTo().assertIsDisplayed()
        compose.runOnIdle { state = CoverEditState.Finished(book.id, book.sha256,
            CoverEditResult.Failed(CoverEditFailure.INVALID_IMAGE), 1) }
        val failure = compose.onNodeWithText(text(R.string.custom_cover_invalid)).performScrollTo().assertIsDisplayed()
        assertEquals(LiveRegionMode.Polite, failure.fetchSemanticsNode().config[SemanticsProperties.LiveRegion])
        compose.onNodeWithText(text(R.string.custom_cover_reset)).performScrollTo().assertIsEnabled().performClick()
        assertEquals(1, resets)
        compose.onNodeWithText(text(R.string.custom_cover_changed)).assertDoesNotExist()
    }

    @Test fun `old reset event cannot remove a newly replaced same hash cover`() {
        var current by mutableStateOf(book)
        var resets = 0
        compose.setContent { YaprakTheme { BookDetailsDialog(current, {}, {}, true, {},
            onChangeCover = {}, onResetCover = { resets++ }) } }
        val oldAction = compose.onNodeWithText(text(R.string.custom_cover_reset)).performScrollTo()
            .fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.runOnIdle { current = current.copy(customCover = cover.copy(id = "new", storageName = "new.png")) }
        compose.runOnIdle { oldAction() }
        assertEquals(0, resets)
        compose.onNodeWithText(text(R.string.custom_cover_reset)).performScrollTo().performClick()
        assertEquals(1, resets)
    }

    @Test fun `original cover has no reset and absent editor has no cover controls`() {
        var available by mutableStateOf(true)
        compose.setContent { YaprakTheme { BookDetailsDialog(book.copy(customCover = null), {}, {}, true, {},
            onChangeCover = if (available) ({}) else null, onResetCover = {}) } }
        compose.onNodeWithText(text(R.string.custom_cover_change)).performScrollTo().assertIsEnabled()
        compose.onNodeWithText(text(R.string.custom_cover_reset)).assertDoesNotExist()
        compose.runOnIdle { available = false }
        compose.onNodeWithText(text(R.string.custom_cover_change)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.custom_cover_help)).assertDoesNotExist()
    }

    @Test fun `only matching committed results display success and cleanup warning`() {
        var state by mutableStateOf<CoverEditState>(CoverEditState.Finished("another-book", book.sha256,
            CoverEditResult.Changed(), 1))
        compose.setContent { YaprakTheme { BookDetailsDialog(book, {}, {}, true, {},
            coverEdit = state, onChangeCover = {}, onResetCover = {}) } }
        compose.onNodeWithText(text(R.string.custom_cover_changed)).assertDoesNotExist()
        compose.runOnIdle { state = CoverEditState.Finished(book.id, book.sha256,
            CoverEditResult.Reset(privateFileMayRemain = true), 2) }
        compose.onNodeWithText(text(R.string.custom_cover_resetted) + " " + text(R.string.custom_cover_cleanup_warning))
            .performScrollTo().assertIsDisplayed()
    }

    @Test fun `image picker requests local openable raster types and cancellation yields no URI`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val contract = OpenLocalCoverImage()
        val intent = contract.createIntent(context, arrayOf("*/*"))
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, intent.action)
        assertEquals("*/*", intent.type)
        assertArrayEquals(arrayOf("image/jpeg", "image/png", "image/webp"), intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES))
        assertTrue(intent.getBooleanExtra(Intent.EXTRA_LOCAL_ONLY, false))
        assertTrue(intent.categories.contains(Intent.CATEGORY_OPENABLE))
        assertFalse(intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false))
        assertNull(contract.parseResult(Activity.RESULT_CANCELED, null))
    }

    private fun text(resource: Int) = compose.activity.getString(resource)
}
