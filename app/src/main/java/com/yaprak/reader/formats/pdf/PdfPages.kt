package com.yaprak.reader.formats.pdf

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import com.yaprak.reader.reader.ReaderTap
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.yaprak.reader.reader.PdfTextPoint

/** No synthetic PDF page or combined raster: each pane owns one bounded, original page render. */
@Composable
internal fun PdfPages(
    renderPage: suspend (Int, Int) -> Bitmap,
    page: Int,
    pageCount: Int,
    doublePagePreferred: Boolean,
    status: PdfRenderStatus,
    onTap: () -> Unit,
    onSwipePage: (Int) -> Boolean,
    nightMode: Boolean = false,
    fitRequest: PdfFitRequest = PdfFitRequest(),
    cropRequest: PdfCropRequest = PdfCropRequest(),
    onTextSelection: ((Int, PdfTextPoint, PdfTextPoint) -> Unit)? = null,
    annotationPaint: PdfAnnotationPaintState? = null,
    onRetryAnnotations: ((PdfAnnotationPaintState) -> Unit)? = null,
    onReaderTap: ((ReaderTap) -> Boolean)? = null,
    drawProofHost: PdfDrawProofView? = null,
    scrolling: Boolean = false,
    loadPageDimensions: (suspend (Int) -> PdfPageDimensions)? = null,
    scrollAllowed: () -> Boolean = { true },
    onScrolledPrimary: () -> Unit = {},
    horizontalScrolling: Boolean = false,
) {
    BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xFF232725)).systemBarsPadding()) {
        val doublePage = !scrolling && doublePagePreferred && maxWidth >= 600.dp && pageCount > 1
        SideEffect { status.setDoublePage(doublePage) }
        DisposableEffect(status) { onDispose { status.invalidate() } }
        val pages = if (doublePage && page < pageCount - 1) listOf(page, page + 1) else listOf(page)
        if (scrolling) {
            val density = LocalDensity.current
            key(horizontalScrolling) {
                if (horizontalScrolling) PdfHorizontalPages(renderPage, checkNotNull(loadPageDimensions), pageCount, status,
                    with(density) { maxWidth.roundToPx() }, with(density) { maxHeight.roundToPx() }, onTap,
                    nightMode, fitRequest, cropRequest, scrollAllowed, onScrolledPrimary, onTextSelection,
                    annotationPaint, drawProofHost, onReaderTap)
                else PdfScrollPages(renderPage, checkNotNull(loadPageDimensions), pageCount, status,
                    with(density) { maxWidth.roundToPx() }, with(density) { maxHeight.roundToPx() }, onTap,
                    nightMode, fitRequest, cropRequest, scrollAllowed, onScrolledPrimary, onTextSelection,
                    annotationPaint, drawProofHost, onReaderTap)
            }
        } else {
            // PDF page indices have a stable left-to-right ordering, independent of app UI locale.
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                var rowCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
                Row(Modifier.fillMaxSize().onGloballyPositioned { rowCoordinates = it }, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    pages.forEach { index ->
                        key(page, index, doublePage) {
                            val witness = remember(drawProofHost, index) { drawProofHost?.let { host ->
                                PdfPageDrawWitness { host.revoke(); host.invalidate() }
                            } }
                            DisposableEffect(drawProofHost, witness, index) {
                                if (witness != null) drawProofHost?.register(index, witness)
                                onDispose { if (witness != null) drawProofHost?.unregister(index, witness) }
                            }
                            var paneCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
                            Box(Modifier.weight(1f).fillMaxHeight().onGloballyPositioned { paneCoordinates = it }) {
                                PdfPage(renderPage, index, onTap, status::startRender, { rendered, ticket ->
                                    status.finishRender(rendered, ticket); drawProofHost?.invalidate()
                                },
                                    nightMode, onSwipePage, status::failRender, fitRequest, cropRequest,
                                    applySystemBarsPadding = false, onTextSelection = onTextSelection, drawWitness = witness,
                                    verifiedAnnotations = (annotationPaint as? PdfAnnotationPaintState.Ready)?.annotations.orEmpty(),
                                    onContentTap = { point, viewport ->
                                        val row = rowCoordinates
                                        val pane = paneCoordinates
                                        val tap = if (row?.isAttached == true && pane?.isAttached == true &&
                                            pane.size.width == viewport.width && pane.size.height == viewport.height) {
                                            val position = row.localPositionOf(pane, point)
                                            ReaderTap.fromPixels(position.x, position.y, row.size.width, row.size.height)
                                        } else ReaderTap(Float.NaN, Float.NaN)
                                        val dispatch: () -> Unit = { if (onReaderTap?.invoke(tap) != true) onTap() }
                                        dispatch
                                    })
                            }
                        }
                    }
                }
            }
        }
        if (annotationPaint is PdfAnnotationPaintState.Failed || annotationPaint == PdfAnnotationPaintState.Unsupported) {
            androidx.compose.material3.Surface(Modifier.align(Alignment.TopCenter).padding(12.dp),
                shape = androidx.compose.material3.MaterialTheme.shapes.medium) {
                Column(Modifier.padding(12.dp)) {
                    androidx.compose.material3.Text(if (annotationPaint == PdfAnnotationPaintState.Unsupported)
                        "Kayıtlı PDF vurgularını görüntülemek Android 15 veya sonrasını gerektirir. Kayıtları araçlardan açabilirsin."
                        else "PDF vurguları bu sayfada doğrulanamadı. Kitap görüntülenmeye devam ediyor.")
                    if (annotationPaint is PdfAnnotationPaintState.Failed && onRetryAnnotations != null)
                        androidx.compose.material3.TextButton(onClick = { onRetryAnnotations(annotationPaint) }) {
                            androidx.compose.material3.Text("Vurguları yeniden dene")
                        }
                }
            }
        }
    }
}
