package com.yaprak.reader.feature

import android.content.ComponentName
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import com.yaprak.reader.YaprakApplication
import com.yaprak.reader.domain.library.*
import com.yaprak.reader.feature.librarylabels.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = YaprakApplication::class, qualifiers = "w411dp-h891dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LibraryLabelsActivityTest {
    @get:Rule val compose = createAndroidComposeRule<LibraryLabelsActivity>()
    private val book = Book("labels-one", "Kitap", "Deniz", BookFormat.EPUB, "one.epub", "one.epub", 1, "a".repeat(64), 1)
    private val other = book.copy(id = "labels-two", sha256 = "b".repeat(64), storageName = "two.epub")
    private fun model() = ViewModelProvider(compose.activity)[LibraryLabelsViewModel::class.java]
    private fun container() = (compose.activity.application as YaprakApplication).container
    private fun ready() { compose.waitUntil(15_000) {
        compose.onAllNodesWithTag("library-labels").fetchSemanticsNodes().isNotEmpty() &&
            model().state.value.active && !model().state.value.loading && model().state.value.failure == null
    } }
    private fun seed() {
        ready(); runBlocking {
            container().library.addImported(book, BookOrganization(tags = listOf("İş", "Yeni"), collections = listOf("Raf")))
            container().library.addImported(other, BookOrganization(tags = listOf("ış"), collections = listOf("Raf")))
        }
        compose.waitUntil(15_000) { model().state.value.snapshot?.members?.size == 2 }
    }
    private fun clickLabel(name: String) {
        compose.onNodeWithTag("library-labels").performScrollToNode(hasTestTag("label-" + libraryLabelKey(name)))
        compose.onNodeWithTag("label-" + libraryLabelKey(name)).performClick()
    }
    @Test fun actualActivityRenamesMergesRecreatesDraftAndRemovesOnlyChosenKind() {
        seed(); clickLabel("İş")
        compose.onNodeWithTag("label-new-name").performScrollTo().performTextReplacement("Yeni")
        compose.onNodeWithText("İlgili kitap: 2").assertExists()
        compose.activityRule.scenario.recreate(); ready()
        compose.onNodeWithTag("label-new-name").performScrollTo().assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("Yeni")))
        compose.onNodeWithTag("label-apply").performClick()
        compose.waitUntil(15_000) { model().state.value.changedBooks == 2 }
        runBlocking {
            assertEquals(listOf("Yeni"), container().library.findById(book.id)!!.organization.tags)
            assertEquals(listOf("Yeni"), container().library.findById(other.id)!!.organization.tags)
            assertEquals(listOf("Raf"), container().library.findById(book.id)!!.organization.collections)
        }
        compose.onNodeWithTag("library-labels").performScrollToNode(hasText("Koleksiyon") and hasClickAction())
        compose.onNodeWithText("Koleksiyon").performClick(); clickLabel("Raf")
        compose.onNodeWithText("Kitaplardan kaldır").performScrollTo().performClick()
        compose.onNodeWithText("Vazgeç").performClick()
        runBlocking { assertEquals(listOf("Raf"), container().library.findById(book.id)!!.organization.collections) }
        clickLabel("Raf"); compose.onNodeWithText("Kitaplardan kaldır").performScrollTo().performClick()
        compose.onNodeWithTag("label-apply").performClick()
        compose.waitUntil(15_000) { model().state.value.editor == null && model().state.value.changedBooks == 2 }
        runBlocking {
            assertTrue(container().library.findById(book.id)!!.organization.collections.isEmpty())
            assertEquals(listOf("Yeni"), container().library.findById(book.id)!!.organization.tags)
            assertNull(container().reading.resume(book.id, book.sha256))
        }
    }
    @Test fun actualExternalMetadataChangeDisablesDraftAndReopeningUsesCurrentMembership() {
        seed(); clickLabel("İş")
        compose.onNodeWithTag("label-new-name").performScrollTo().performTextReplacement("Yeni")
        runBlocking { val current = container().library.findById(other.id)!!
            container().library.updateOrganization(current.id, current.sha256, current.organization.copy(favorite = true)) }
        compose.waitUntil(15_000) { !model().state.value.fresh(model().state.value.editor!!) }
        compose.onNodeWithTag("label-apply").assertIsNotEnabled()
        compose.onNodeWithText("Vazgeç").performClick(); clickLabel("İş")
        compose.onNodeWithTag("label-new-name").performScrollTo().performTextReplacement("Yeni")
        compose.onNodeWithTag("label-apply").performClick()
        compose.waitUntil(15_000) { model().state.value.changedBooks == 2 }
        runBlocking { assertTrue(container().library.findById(other.id)!!.organization.favorite) }
    }
    @Test fun pauseAndRecoveryStopSourceAndKeepUnsubmittedDraftWithoutWriting() {
        seed(); clickLabel("İş")
        compose.onNodeWithTag("label-new-name").performScrollTo().performTextReplacement("Taslak")
        val retained = model()
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        assertFalse(retained.state.value.active); assertNull(retained.state.value.snapshot)
        assertEquals("Taslak", retained.state.value.editor!!.target)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED); ready()
        compose.runOnIdle { container().startup.requireRecovery() }
        compose.onNodeWithText("Kitaplık kurtarılamadı.").assertIsDisplayed()
        compose.onNodeWithTag("library-labels").assertDoesNotExist(); assertFalse(retained.state.value.active)
        compose.onNodeWithText("Yeniden dene").performClick(); ready()
        compose.onNodeWithTag("label-new-name").performScrollTo().assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("Taslak")))
        runBlocking { assertEquals(listOf("İş", "Yeni"), container().library.findById(book.id)!!.organization.tags) }
    }
    @Test fun activityIntentIsInternalAndDoesNotAcceptBatchArguments() {
        val intent = LibraryLabelsActivity.intent(compose.activity)
        assertEquals(LibraryLabelsActivity::class.java.name, intent.component!!.className); assertNull(intent.extras)
        assertFalse(compose.activity.packageManager.getActivityInfo(ComponentName(compose.activity, LibraryLabelsActivity::class.java), 0).exported)
    }
}
