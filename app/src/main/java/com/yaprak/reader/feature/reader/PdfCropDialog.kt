package com.yaprak.reader.feature.reader

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.yaprak.reader.R
import com.yaprak.reader.reader.PdfPageCrop
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/** Draft-only controls and a draw-time preview; the source always owns its returned image. */
@Composable
internal fun PdfCropDialog(
    requestId: String,
    saved: PdfPageCrop,
    previewPage: Int?,
    renderPreview: (suspend () -> Bitmap)?,
    isCurrent: () -> Boolean,
    onApply: suspend (PdfPageCrop) -> Boolean,
    onDismiss: () -> Unit,
    preferenceState: PdfBookViewPreferencesState? = null,
) {
    key(requestId) {
        val lease = remember { PdfCropCompositionLease() }
        val gate by rememberUpdatedState(isCurrent)
        val renderer by rememberUpdatedState(renderPreview)
        val apply by rememberUpdatedState(onApply)
        val dismiss by rememberUpdatedState(onDismiss)
        val preference by rememberUpdatedState(preferenceState)
        val scope = rememberCoroutineScope()
        var saving by remember { mutableStateOf(false) }
        DisposableEffect(lease) { lease.active = true; onDispose { lease.active = false } }
        fun mayAct() = lease.active && gate()
        fun close() { if (mayAct()) dismiss() }

        var draft by remember { mutableStateOf(saved) }
        var draftRevision by remember { mutableLongStateOf(0L) }
        var applyFailure by remember { mutableStateOf<Any?>(null) }
        fun edit(value: PdfPageCrop) {
            if (mayAct() && !saving && preference?.status != PdfBookViewStatus.APPLY_FAILED && value != draft) {
                draft = value
                draftRevision++
                applyFailure = null
            }
        }
        val renderedDraft = draft
        val renderedRevision = draftRevision
        val canEdit = !saving && preferenceState?.status != PdfBookViewStatus.APPLY_FAILED
        val bodyScroll = rememberScrollState()
        LaunchedEffect(applyFailure) {
            if (applyFailure != null && mayAct()) bodyScroll.scrollTo(0)
        }

        val hasPreview = previewPage != null && previewPage >= 0 && renderPreview != null
        var image by remember { mutableStateOf<Bitmap?>(null) }
        var previewFailed by remember { mutableStateOf(false) }
        var previewAttempt by remember { mutableIntStateOf(0) }
        LaunchedEffect(requestId, previewPage, hasPreview, previewAttempt) {
            image = null
            previewFailed = false
            if (!hasPreview || !mayAct()) return@LaunchedEffect
            try {
                val result = renderer?.invoke() ?: return@LaunchedEffect
                currentCoroutineContext().ensureActive()
                if (mayAct()) {
                    check(!result.isRecycled && result.width > 0 && result.height > 0)
                    image = result
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (mayAct()) previewFailed = true
            }
        }

        Dialog(onDismissRequest = ::close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            BoxWithConstraints(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                val height = minOf(maxHeight, 560.dp)
                val previewHeight = if (height < 400.dp) 96.dp else 192.dp
                Surface(Modifier.widthIn(max = 560.dp).fillMaxWidth().height(height),
                    shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp) {
                    Column {
                        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(bodyScroll)
                            .padding(16.dp).testTag("pdf-crop-body"),
                            verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(stringResource(R.string.pdf_crop_title), Modifier.fillMaxWidth(),
                                style = MaterialTheme.typography.titleLarge)
                            if (applyFailure != null) Text(stringResource(R.string.pdf_crop_apply_error),
                                Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.error)
                            if (saving && preferenceState?.status != PdfBookViewStatus.SAVING)
                                Text(stringResource(R.string.pdf_book_view_saving), Modifier.fillMaxWidth())
                            if (preferenceState?.failed == true) PdfBookViewPreferenceMessage(preferenceState)
                            Text(stringResource(R.string.pdf_crop_help), Modifier.fillMaxWidth(),
                                style = MaterialTheme.typography.bodySmall)
                            PdfCropSlider(R.string.pdf_crop_horizontal, "pdf-crop-horizontal", draft.horizontalPercent,
                                enabled = mayAct() && canEdit) { value -> edit(draft.copy(horizontalPercent = value)) }
                            PdfCropSlider(R.string.pdf_crop_vertical, "pdf-crop-vertical", draft.verticalPercent,
                                enabled = mayAct() && canEdit) { value -> edit(draft.copy(verticalPercent = value)) }
                            if (!hasPreview) Text(stringResource(R.string.pdf_crop_preview_unavailable),
                                Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall)
                            else {
                                val pageLabel = stringResource(R.string.pdf_crop_preview_page, checkNotNull(previewPage).toLong() + 1L)
                                Text(pageLabel, Modifier.fillMaxWidth(), style = MaterialTheme.typography.titleSmall)
                                val bitmap = image
                                when {
                                    bitmap != null -> {
                                        val painter = remember(bitmap, draft) {
                                            val area = draft.bounds(bitmap.width, bitmap.height)
                                            BitmapPainter(bitmap.asImageBitmap(), IntOffset(area.left, area.top),
                                                IntSize(area.width, area.height))
                                        }
                                        Image(painter, pageLabel, Modifier.fillMaxWidth().height(previewHeight)
                                            .background(Color.White).testTag("pdf-crop-preview"), contentScale = ContentScale.Fit)
                                    }
                                    previewFailed -> {
                                        Text(stringResource(R.string.pdf_crop_failed), Modifier.fillMaxWidth(),
                                            color = MaterialTheme.colorScheme.error)
                                        val renderedAttempt = previewAttempt
                                        key(renderedAttempt) {
                                            TextButton(onClick = {
                                                if (mayAct() && previewFailed && previewAttempt == renderedAttempt) {
                                                    previewFailed = false
                                                    previewAttempt++
                                                }
                                            }, enabled = mayAct(), modifier = Modifier.fillMaxWidth().testTag("pdf-crop-retry")) {
                                                Text(stringResource(R.string.pdf_crop_retry), Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                                            }
                                        }
                                    }
                                    else -> {
                                        CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
                                        Text(stringResource(R.string.pdf_crop_loading), Modifier.fillMaxWidth())
                                    }
                                }
                            }
                            key(renderedRevision) {
                                TextButton(onClick = {
                                    if (mayAct() && canEdit && draftRevision == renderedRevision) edit(PdfPageCrop())
                                }, enabled = mayAct() && canEdit, modifier = Modifier.fillMaxWidth().testTag("pdf-crop-reset")) {
                                    Text(stringResource(R.string.pdf_crop_reset), Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                                }
                            }
                        }
                        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
                            .testTag("pdf-crop-actions")) {
                            key(renderedRevision) {
                                TextButton(onClick = {
                                    if (mayAct() && !saving && draftRevision == renderedRevision && draft == renderedDraft) {
                                        saving = true
                                        scope.launch {
                                            try {
                                                val accepted = try { apply(renderedDraft) }
                                                catch (cancelled: CancellationException) { throw cancelled }
                                                catch (_: Exception) { false }
                                                currentCoroutineContext().ensureActive()
                                                if (accepted) close() else if (mayAct()) applyFailure = Any()
                                            } finally { saving = false }
                                        }
                                    }
                                }, enabled = mayAct() && !saving, modifier = Modifier.fillMaxWidth().testTag("pdf-crop-apply")) {
                                    Text(stringResource(if (preferenceState?.status == PdfBookViewStatus.APPLY_FAILED)
                                        R.string.pdf_book_view_retry else R.string.pdf_crop_apply),
                                        Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                                }
                            }
                            TextButton(onClick = ::close, enabled = mayAct(),
                                modifier = Modifier.fillMaxWidth().testTag("pdf-crop-cancel")) {
                                Text(stringResource(R.string.pdf_crop_cancel), Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PdfCropSlider(labelId: Int, tag: String, percent: Int, enabled: Boolean, onChange: (Int) -> Unit) {
    val label = stringResource(labelId)
    val amount = stringResource(R.string.pdf_crop_percent, percent)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, Modifier.fillMaxWidth(), style = MaterialTheme.typography.titleSmall)
        Text(amount, Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall)
        Slider(value = percent.toFloat(), onValueChange = { value ->
            if (value.isFinite()) onChange(value.roundToInt().coerceIn(0, 20))
        }, valueRange = 0f..20f, steps = 19, enabled = enabled,
            modifier = Modifier.fillMaxWidth().testTag(tag).semantics {
                contentDescription = label
                stateDescription = amount
            })
    }
}

private class PdfCropCompositionLease { var active = true }
