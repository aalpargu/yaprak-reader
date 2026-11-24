package com.yaprak.reader.feature

import com.yaprak.reader.readerMethod
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import java.io.File
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.yaprak.reader.domain.pdfannotations.*
import com.yaprak.reader.feature.pdfannotations.*
import com.yaprak.reader.feature.reader.*
import com.yaprak.reader.ui.theme.YaprakTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],application=Application::class,qualifiers="w411dp-h891dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PdfAnnotationsDialogTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    @Test @Config(qualifiers="w320dp-h800dp-mdpi") fun allFourColorsAndExactSourceTextScrollWhileExplicitSaveRemainsAccessibleAtFontTwo() {
        fontTwo(); val request=request(); var state by mutableStateOf(PdfAnnotationEditorState(PdfHighlightColor.YELLOW,"")); var saves=0
        compose.setContent { YaprakTheme { PdfAnnotationEditorDialog(request,state,true,{ state=state.copy(color=it,revision=state.revision+1) },{ state=state.copy(note=it,revision=state.revision+1) },{ saves++ },{}) } }
        compose.onNodeWithText("PDF sayfası: 4").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(PDF_TEST_TEXT).performScrollTo().assertIsDisplayed()
        for(label in listOf("Sarı","Yeşil","Mavi","Pembe")) compose.onNodeWithText(label).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Pembe").performClick(); assertEquals(PdfHighlightColor.PINK,state.color)
        compose.onNodeWithTag("pdf-annotation-save").assertIsDisplayed(); compose.onNodeWithTag("pdf-annotation-cancel").assertIsDisplayed()
        screenshot("pdf-annotation-editor-narrow-font2"); compose.onNodeWithTag("pdf-annotation-save").performClick(); assertEquals(1,saves)
        request.close()
    }
    @Test @Config(qualifiers="w480dp-h320dp-land-mdpi") fun aPendingFreshNativeProofHasAFixedCancelActionAtFontTwo() {
        fontTwo(); val request=request(); var closes=0
        compose.setContent { YaprakTheme { PdfAnnotationEditorDialog(request,PdfAnnotationEditorState(PdfHighlightColor.BLUE,"note",busy=true),true,
            { error("Busy cannot recolor") },{ error("Busy cannot edit") },{ error("Busy cannot save") },{ closes++; request.close() }) } }
        compose.onNodeWithTag("pdf-annotation-save").assertIsNotEnabled().assertIsDisplayed()
        compose.onNodeWithTag("pdf-annotation-cancel").assertIsDisplayed()
        screenshot("pdf-annotation-saving-landscape-font2"); compose.onNodeWithTag("pdf-annotation-cancel").performClick()
        assertEquals(1,closes); assertFalse(request.lifetime.isActive)
    }
    @Test fun aCapturedSaveCannotTargetANewerDraftAClosedLifetimeOrADisposedWindow() {
        val request=request(); var state by mutableStateOf(PdfAnnotationEditorState(PdfHighlightColor.YELLOW,"")); var show by mutableStateOf(true); var saves=0
        compose.setContent { YaprakTheme { if(show) PdfAnnotationEditorDialog(request,state,true,{}, {}, { saves++ },{}) } }
        val old=compose.onNodeWithTag("pdf-annotation-save").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.runOnIdle { state=state.copy(color=PdfHighlightColor.BLUE,revision=1) }; compose.waitForIdle(); compose.runOnIdle { old() }; assertEquals(0,saves)
        val current=compose.onNodeWithTag("pdf-annotation-save").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.runOnIdle { request.close(); current() }; assertEquals(0,saves)
        compose.runOnIdle { show=false }; compose.waitForIdle(); compose.runOnIdle { current() }; assertEquals(0,saves)
    }
    @Test fun toolbarAcceptsOnlyMatchingNativePdfGeometryAndNeverExposesEpubPassageOrAiActions() {
        var preview by mutableStateOf(SelectedTextPreview.Ready(PDF_TEST_TEXT,pdfPageIndex=3,pdfSourceAnchor=PDF_TEST_ANCHOR)); var annotations=0
        compose.setContent { YaprakTheme { SelectedTextDialog(preview,{}, {},onAnnotate={ annotations++ },onTransferPassage={},
            assistantContent={ error("PDF cannot borrow EPUB AI sources") }) } }
        compose.onNodeWithText("Vurgula").performScrollTo().performClick(); assertEquals(1,annotations)
        compose.onNodeWithText("Pasaj taslağına aktar").assertDoesNotExist()
        compose.runOnIdle { preview=preview.copy(pdfPageIndex=2) }; compose.onNodeWithText("Vurgula").assertDoesNotExist()
        compose.runOnIdle { preview=preview.copy(pdfSourceAnchor=null) }; compose.onNodeWithText("Vurgula").assertDoesNotExist()
    }
    @Test @Config(qualifiers="w480dp-h320dp-land-mdpi") fun legacyAndroidRecordListKeepsItsCloseActionVisibleAndSourcePageReachableAtFontTwo() {
        fontTwo(); val request=PdfAnnotationsWindow("book","a".repeat(64),false); val repo=FakePdfAnnotationsRepository(listOf(pdfTestRecord())); var closed=0
        compose.setContent { YaprakTheme { PdfAnnotationsRoute(request,repo,{ true },{ true },{ closed++ },{}) } }
        compose.onNodeWithText("PDF sayfası 4 · Sarı").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Düzenle").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("pdf-annotations-close").assertIsDisplayed(); screenshot("pdf-annotations-list-landscape-font2")
        compose.onNodeWithTag("pdf-annotations-close").performClick(); assertEquals(1,closed); assertFalse(request.lifetime.isActive)
    }
    @Test @Config(qualifiers="w320dp-h800dp-mdpi") fun sourceOpeningShowsFreshProofFailureAndRetriesTheExactAnchorAtFontTwo() {
        fontTwo(); val saved=pdfTestRecord(); val request=PdfAnnotationsWindow("book","a".repeat(64),true)
        val repo=FakePdfAnnotationsRepository(listOf(saved)); var opened: Any?=null
        repo.beforeProof={ throw PdfAnnotationSourceException() }
        compose.setContent { YaprakTheme { PdfAnnotationsRoute(request,repo,{ true },{ opened=it; true },{}, {}) } }
        compose.onNodeWithText("Kaydı aç").performScrollTo().performClick()
        compose.onNodeWithText("Özgün PDF veya kayıt doğrulanamadı. İşlemi yeniden deneyebilirsin.").performScrollTo().assertIsDisplayed()
        assertEquals(1,repo.proofs); assertNull(opened)
        compose.onNodeWithTag("pdf-annotations-close").assertIsDisplayed()
        screenshot("pdf-annotation-source-failure-font2")
        repo.beforeProof={ assertEquals(listOf(saved),it) }
        compose.onNodeWithText("Kaydı aç").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(saved.anchor,opened); assertEquals(2,repo.proofs) }
        compose.onNodeWithText("Kaydı aç").performScrollTo().assertIsDisplayed()
        screenshot("pdf-annotation-source-ready-font2")
        request.close()
    }

    @Test fun listRowCallbacksFromBeforeAQueryOrEditorCannotActOnTheReturningList() {
        val saved=pdfTestRecord(); val window=PdfAnnotationsWindow("book","a".repeat(64),true)
        val repo=FakePdfAnnotationsRepository(listOf(saved)); val copied=mutableListOf<String>()
        compose.setContent { YaprakTheme { PdfAnnotationsRoute(window,repo,{ true },
            { error("This case cannot navigate") },{}, { copied+=it }) } }
        compose.onNodeWithText("Kopyala").performScrollTo().assertIsDisplayed()
        val beforeQueryCopy=applicationClick(compose.onNodeWithText("Kopyala"))
        val beforeQueryEdit=applicationClick(compose.onNodeWithTag("pdf-annotation-edit-${saved.id}"))
        compose.onNodeWithTag("pdf-annotations-search").performScrollTo().performTextReplacement("note")
        compose.onNodeWithTag("pdf-annotation-row-${saved.id}").assertExists()
        compose.runOnIdle { beforeQueryCopy(); beforeQueryEdit() }
        compose.onNodeWithTag("pdf-annotation-body").assertDoesNotExist()
        assertTrue(copied.isEmpty())

        compose.onNodeWithText("Kopyala").performScrollTo()
        val beforeEditorCopy=applicationClick(compose.onNodeWithText("Kopyala"))
        compose.onNodeWithTag("pdf-annotation-edit-${saved.id}").performScrollTo().performClick()
        compose.onNodeWithTag("pdf-annotation-body").assertIsDisplayed()
        compose.onNodeWithTag("pdf-annotations-body").assertDoesNotExist()
        compose.onNodeWithTag("pdf-annotation-cancel").performClick()
        compose.onNodeWithTag("pdf-annotations-body").assertIsDisplayed()
        compose.runOnIdle { beforeEditorCopy() }
        assertTrue(copied.isEmpty())
        compose.onNodeWithText("Kopyala").performScrollTo().performClick()
        assertEquals(listOf(saved.text+"\n\nBağlı not:\n"+saved.attachedNote),copied)
        assertEquals(listOf(saved),repo.records.value)
        assertEquals(0,repo.creates+repo.updates+repo.removes+repo.proofs)
        compose.runOnIdle { window.close() }
    }

    @Suppress("UNCHECKED_CAST")
    private fun applicationClick(node: SemanticsNodeInteraction): () -> Unit {
        // Pin the app callback, rather than Material's wrapper which reads a node's latest onClick.
        val semantics=node.fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        check(semantics.javaClass.name=="androidx.compose.foundation.AbstractClickableNode\$applySemantics\$1")
        val clickable=semantics.javaClass.getDeclaredField("this\$0").apply { isAccessible=true }.get(semantics)
        val declaring = generateSequence(clickable.javaClass as Class<*>?) { it.superclass }
            .first { it.name=="androidx.compose.foundation.AbstractClickableNode" }
        return declaring.readerMethod("getOnClick")
            .apply { isAccessible=true }.invoke(clickable) as () -> Unit
    }

    private fun request()=PdfAnnotationRequest("book","a".repeat(64),PDF_TEST_TEXT,PDF_TEST_ANCHOR)
    private fun fontTwo() { compose.runOnUiThread { RuntimeEnvironment.setFontScale(2f) }; compose.activityRule.scenario.recreate() }
    private fun screenshot(name: String) {
        val path=System.getProperty("yaprak.screenshotDir")?:return
        compose.runOnIdle { val view=requireNotNull(ShadowDialog.getLatestDialog().window).decorView
            val bitmap=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
            try { view.draw(Canvas(bitmap)); val dir=File(path,"pdf-annotations-0.98.0").apply { mkdirs() }; File(dir,"$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) } }
            finally { bitmap.recycle() }
        }
    }
}
