package com.yaprak.reader.feature.reader

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.reader.ReaderSession
import com.yaprak.reader.reader.ReaderSurface
import com.yaprak.reader.reader.TextSelectionException
import com.yaprak.reader.reader.TextSelectionFailure
import com.yaprak.reader.reader.TextSelectionSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

internal class ReaderSelectionOwner(val session: ReaderSession, val surface: ReaderSurface) {
    val bookIdentity = session.book.id to session.book.sha256
    val format = session.book.format
    fun matches(other: ReaderSelectionOwner?): Boolean = other != null && session === other.session &&
        surface === other.surface && bookIdentity == other.bookIdentity && format == other.format
}

/** Owns only transient source capture. Text never enters presentation state or a durable work item. */
internal class ReaderSelectionCoordinator(
    private val scope: CoroutineScope,
    private val lifecycle: Lifecycle,
    private val currentOwner: () -> ReaderSelectionOwner?,
    private val onCaptureStarted: () -> Unit,
    private val onCleared: () -> Unit,
    private val onReady: (SelectedTextPreview.Ready) -> Unit,
    private val onRejected: () -> Unit,
) {
    var preview by mutableStateOf<SelectedTextPreview?>(null)
        private set
    private var owner: ReaderSelectionOwner? = null
    private var source: TextSelectionSource? = null
    val bookIdentity: Pair<String, String>? get() = owner?.bookIdentity
    private var generation = 0L
    private var captureJob: Job? = null
    private var notifications: Job? = null

    fun bindNotifications(surface: ReaderSurface) {
        notifications?.cancel()
        val selection = surface.textSelection ?: return
        notifications = scope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                selection.requests.collect {
                    if (currentOwner()?.surface === surface && surface.textSelection === selection && selection.isCurrent(null))
                        capture(surface, selection, ocr = false)
                }
            }
        }
    }

    fun capture(surface: ReaderSurface, selection: TextSelectionSource, ocr: Boolean) {
        val admitted = currentOwner() ?: return
        if (admitted.surface !== surface || (if (ocr) surface.pdfOcr else surface.textSelection) !== selection || preview != null) return
        val requestGeneration = ++generation
        if (!ocr) surface.pdfOcr?.clear()
        val loading = SelectedTextPreview.Loading(pdfOcr = ocr)
        owner = admitted
        source = selection
        preview = loading
        onCaptureStarted()
        val job = scope.launch(start = CoroutineStart.LAZY) {
            fun owned() = generation == requestGeneration && owner === admitted && preview === loading
            try {
                val result = capturePreview(admitted, selection, ocr, loading)
                currentCoroutineContext().ensureActive()
                if (!owned()) return@launch
                if (!admitted.matches(currentOwner()) || source !== selection ||
                    (if (ocr) surface.pdfOcr else surface.textSelection) !== selection ||
                    (result is SelectedTextPreview.Ready && !selection.isCurrent(result.sourceSelectionId))) {
                    clear(); onRejected(); return@launch
                }
                preview = result
                if (result is SelectedTextPreview.Ready) onReady(result)
            } catch (cancelled: CancellationException) {
                if (owned()) { clear(); onRejected() }
                throw cancelled
            } finally {
                if (generation == requestGeneration) captureJob = null
            }
        }
        captureJob = job
        job.start()
    }

    private suspend fun capturePreview(owner: ReaderSelectionOwner, source: TextSelectionSource, ocr: Boolean,
        loading: SelectedTextPreview.Loading): SelectedTextPreview = try {
        source.capture()?.let { selected ->
            currentCoroutineContext().ensureActive()
            if (selected.pdfOcr != ocr || (ocr && (selected.sourceAnchor != null || selected.rangeCandidate != null || selected.pdfSourceAnchor != null)))
                throw TextSelectionException(TextSelectionFailure.INVALID_RESOURCE)
            if (ocr && (com.yaprak.reader.reader.validatedPdfOcrText(selected.text) == null ||
                owner.surface.pageNavigator?.captureStablePage() != selected.pdfPageIndex))
                throw TextSelectionException(TextSelectionFailure.INVALID_RESOURCE)
            if (owner.format == BookFormat.PDF) {
                val page = selected.pdfPageIndex
                val id = selected.sourceSelectionId
                if (page == null || page !in 0 until (owner.surface.pageNavigator?.pageCount ?: 0) || id == null ||
                    runCatching { java.util.UUID.fromString(id).toString() == id }.getOrDefault(false) != true)
                    throw TextSelectionException(TextSelectionFailure.INVALID_RESOURCE)
                if (selected.sourceAnchor != null || (selected.pdfSourceAnchor != null && selected.pdfSourceAnchor.pageIndex != page))
                    throw TextSelectionException(TextSelectionFailure.INVALID_RESOURCE)
            } else if (selected.pdfPageIndex != null || selected.pdfSourceAnchor != null)
                throw TextSelectionException(TextSelectionFailure.INVALID_RESOURCE)
            if (!source.isCurrent(selected.sourceSelectionId)) throw CancellationException("Selected source changed")
            SelectedTextPreview.Ready(selected.text, rangeCandidate = selected.rangeCandidate, sourceAnchor = selected.sourceAnchor,
                sessionId = loading.sessionId, pdfPageIndex = selected.pdfPageIndex, sourceSelectionId = selected.sourceSelectionId,
                pdfSourceAnchor = selected.pdfSourceAnchor, pdfOcr = ocr)
        } ?: SelectedTextPreview.Error(if (ocr) "Bu sayfada OCR metni bulunamadı. Daha net, Latin harfli bir sayfada yeniden dene."
            else if (owner.format == BookFormat.PDF)
                "Seçili PDF metni bulunamadı. Yazıya uzun bas veya basılı tutup sürükle. Resim olarak taranmış sayfalarda OCR yapılmaz."
            else "Seçili metin bulunamadı. Araçlar açıkken kitap metnini seçip yeniden dene.", loading.sessionId)
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: com.yaprak.reader.reader.PdfOcrException) {
        SelectedTextPreview.Error(when (failure.reason) {
            com.yaprak.reader.reader.PdfOcrFailure.BUSY -> "Önceki OCR işlemi kaynaklarını bırakmayı bekliyor. Biraz sonra yeniden dene."
            com.yaprak.reader.reader.PdfOcrFailure.LIMIT -> "OCR metni çok uzun. En fazla 5000 karakterlik bir sayfa işlenebilir."
            com.yaprak.reader.reader.PdfOcrFailure.SOURCE -> "OCR sayfası veya özgün PDF dosyası doğrulanamadı. Kitabı yeniden aç."
            com.yaprak.reader.reader.PdfOcrFailure.MODEL -> "OCR motoru metni çıkaramadı. Yeniden dene."
        }, loading.sessionId)
    } catch (failure: TextSelectionException) {
        SelectedTextPreview.Error(when (failure.reason) {
            TextSelectionFailure.TOO_LONG -> "Seçim çok uzun. En fazla 5000 karakter seç."
            TextSelectionFailure.INVALID_RESOURCE -> "Seçim, açık kitabın okuma içeriğinde doğrulanamadı. Yeniden seç."
            TextSelectionFailure.UNSUPPORTED -> "PDF metin seçimi Android 15 veya sonrasında kullanılabilir."
        }, loading.sessionId)
    } catch (_: Exception) { SelectedTextPreview.Error("Seçili metin alınamadı. Yeniden dene.", loading.sessionId) }

    fun isCurrent(): Boolean {
        val admitted = owner ?: return false
        if (preview == null || !admitted.matches(currentOwner())) return false
        val selected = preview as? SelectedTextPreview.Ready ?: return true
        val expected = if (selected.pdfOcr) admitted.surface.pdfOcr else admitted.surface.textSelection
        return expected != null && source === expected && expected.isCurrent(selected.sourceSelectionId) &&
            (!selected.pdfOcr || admitted.surface.pageNavigator?.captureStablePage() == selected.pdfPageIndex)
    }

    fun updatePreview(expected: SelectedTextPreview.Ready, updated: SelectedTextPreview.Ready) {
        if (preview === expected && isCurrent() && updated.sessionId == expected.sessionId && updated.text == expected.text)
            preview = updated
    }

    fun clear() {
        generation++
        captureJob?.cancel()
        captureJob = null
        source?.clear()
        owner?.surface?.textSelection?.clear()
        source = null
        owner = null
        preview = null
        onCleared()
    }

    fun dispose() { notifications?.cancel(); notifications = null; clear() }
}
