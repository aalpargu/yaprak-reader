package com.yaprak.reader.formats

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.yaprak.reader.domain.pdfannotations.*
import com.yaprak.reader.formats.pdf.*
import com.yaprak.reader.reader.*
import com.yaprak.reader.ui.theme.YaprakTheme
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Real native Compose pixels with controlled verified geometry; actual decoder proof is a separate device test. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],application=Application::class,qualifiers="w411dp-h891dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PdfAnnotationDrawingTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    @Test fun coloredNativeBoundsUseTheSharedZoomLayerCropAndNightGeometryWithoutChangingTheSourceBitmap() {
        val f=show(); waitForPage(f)
        assertEquals(Color.WHITE,sample(f,160,240)); assertHighlight(sample(f,100,240)); assertEquals(Color.WHITE,sample(f,100,200))
        compose.onNodeWithTag("pdf-annotation-viewport").performTouchInput { doubleClick(center) }; assertHighlight(sample(f,160,240))
        compose.runOnIdle { f.night.value=true }; assertEquals(Color.BLACK,sample(f,100,100)); assertNotEquals(Color.BLACK,sample(f,100,240))
        compose.runOnIdle { f.night.value=false; f.crop.value=PdfCropRequest(PdfPageCrop(10,10),1) }; compose.waitForIdle()
        assertHighlight(sample(f,35,240)); assertEquals(Color.WHITE,sample(f,160,240))
        screenshot("pdf-annotation-real-canvas-crop",f)
        assertTrue(pixels(f.bitmap).all { it==Color.WHITE }); assertEquals(1,f.renders)
    }
    @Test fun aSavedRightPageAnchorCannotPaintTheLeftPageOrSurviveRemovingVerifiedGeometry() {
        val f=show(); waitForPage(f); assertHighlight(sample(f,100,240))
        val record=f.records.value.single(); val a=record.anchor
        compose.runOnIdle { f.records.value=listOf(record.copy(anchor=PdfSourceAnchor(1,a.pageWidth,a.pageHeight,a.start,a.stop,a.bounds))) }
        assertEquals(Color.WHITE,sample(f,100,240)); compose.runOnIdle { f.records.value=emptyList() }; assertEquals(Color.WHITE,sample(f,100,240))
        assertEquals(1,f.renders); assertTrue(pixels(f.bitmap).all { it==Color.WHITE })
    }
    private class Fixture {
        val bitmap=Bitmap.createBitmap(300,600,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val status=PdfRenderStatus(2,0); val crop=mutableStateOf(PdfCropRequest()); val night=mutableStateOf(false)
        val anchor=PdfSourceAnchor(0,300,600,PdfPagePoint(60,300),PdfPagePoint(180,300),listOf(PdfTextBounds(60.0,280.0,180.0,320.0)))
        val records=mutableStateOf(listOf(PdfAnnotation("controlled", "text",anchor,1,PdfHighlightColor.YELLOW)))
        var bounds=Rect.Zero; var renders=0
        val render: suspend (Int,Int)->Bitmap={ _,_ -> renders++; bitmap }
    }
    private fun show(): Fixture { val f=Fixture(); compose.setContent { YaprakTheme {
        Box(Modifier.size(240.dp,480.dp).testTag("pdf-annotation-viewport").onGloballyPositioned { f.bounds=it.boundsInWindow() }) {
            PdfPage(f.render,0,{},f.status::startRender,f.status::finishRender,nightMode=f.night.value,
                cropRequest=f.crop.value,applySystemBarsPadding=false,verifiedAnnotations=f.records.value)
        }
    } }; return f }
    private fun waitForPage(f: Fixture) { compose.waitForIdle(); compose.waitUntil(5000) { f.status.renderedPage.value==0 }; compose.waitForIdle() }
    private fun capture()=compose.runOnIdle { val view=compose.activity.window.decorView; Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) } }
    private fun sample(f: Fixture,x: Int,y: Int)=capture().let { try { it.getPixel((f.bounds.left+x).toInt(),(f.bounds.top+y).toInt()) } finally { it.recycle() } }
    private fun assertHighlight(pixel: Int) { assertEquals(255,Color.red(pixel)); assertTrue(Color.green(pixel)<255); assertTrue(Color.blue(pixel)<230) }
    private fun pixels(image: Bitmap)=IntArray(image.width*image.height).also { image.getPixels(it,0,image.width,0,0,image.width,image.height) }
    private fun screenshot(name: String,f: Fixture) {
        val path=System.getProperty("yaprak.screenshotDir")?:return; val image=capture()
        try { val cropped=Bitmap.createBitmap(image,f.bounds.left.toInt(),f.bounds.top.toInt(),f.bounds.width.toInt(),f.bounds.height.toInt())
            try { val dir=File(path,"pdf-annotations-0.98.0").apply { mkdirs() }; File(dir,"$name.png").outputStream().use { cropped.compress(Bitmap.CompressFormat.PNG,100,it) } } finally { cropped.recycle() }
        } finally { image.recycle() }
    }
}
