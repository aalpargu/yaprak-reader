package com.yaprak.reader.feature

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.yaprak.reader.YaprakApplication
import com.yaprak.reader.feature.reader.ReaderActivity
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = YaprakApplication::class, qualifiers = "w411dp-h891dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReaderActivityTest {
    @get:Rule val compose = createAndroidComposeRule<ReaderActivity>()

    @Test fun `real activity handles missing identity and returns to library`() {
        // No bookId is supplied: the actual container, database and loader must fail safely.
        compose.waitUntil(15_000) {
            compose.onAllNodesWithText("Kitabın yerel dosyası bulunamadı.").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Kitabın yerel dosyası bulunamadı.").assertIsDisplayed()
        listOf("Koyu tema görselleri", "Özgün görseller", "Karart", "Renkleri ters çevir", "Uygula")
            .forEach { compose.onNodeWithText(it).assertDoesNotExist() }
        val activity = compose.activity
        compose.onNodeWithText("Kitaplığa dön").performClick()
        compose.waitUntil(5_000) { activity.isFinishing }
        assertTrue(activity.isFinishing)
    }
}
