package com.yaprak.reader.feature

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import com.yaprak.reader.domain.library.*
import com.yaprak.reader.feature.librarylabels.*
import com.yaprak.reader.ui.theme.YaprakTheme
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w320dp-h720dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LibraryLabelsUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun largeTypeDialogKeepsApplyCancelAndRemovalPreviewReachableWithoutImplicitWrite() {
        var writes = 0
        val repo = object : LibraryLabelsRepository {
            override fun observe() = flowOf(LibraryLabelSnapshot(listOf(LibraryLabelMember("book", "a".repeat(64),
                BookOrganization(tags = listOf("İş"))))))
            override suspend fun change(kind: LibraryLabelKind, source: String, target: String?, expected: List<LibraryLabelMember>): Int { writes++; return 1 }
        }
        val model = LibraryLabelsViewModel(repo)
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
            YaprakTheme { LibraryLabelsRoute(model) {} }
        } }
        compose.waitUntil(10_000) { model.state.value.snapshot != null }
        compose.onNodeWithTag("library-labels").performScrollToNode(hasTestTag("label-iş"))
        compose.onNodeWithTag("label-iş").performClick()
        compose.onNodeWithTag("label-new-name").performScrollTo().performTextReplacement("Yeni")
        compose.onNodeWithTag("label-apply").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("Vazgeç").assertIsDisplayed()
        assertEquals(0, writes)
        compose.onNodeWithText("Kitaplardan kaldır").performScrollTo().performClick()
        compose.onNodeWithTag("label-editor").performScrollToNode(hasText("Bu işlem 1 kitaptaki bu adı kaldırır."))
        compose.onNodeWithText("Bu işlem 1 kitaptaki bu adı kaldırır.").assertIsDisplayed()
        compose.onNodeWithText("Vazgeç").performClick()
        assertEquals(0, writes)
    }
}
