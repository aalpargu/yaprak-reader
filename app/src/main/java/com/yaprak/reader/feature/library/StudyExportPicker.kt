package com.yaprak.reader.feature.library

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.yaprak.reader.domain.library.StudyExportFormat
import kotlinx.coroutines.CancellationException

/** Compose this under key(requestId), so an old callback never receives a new reservation. */
@Composable
internal fun StudyExportPicker(request: BookStudyExportRequest,
    claimLaunch: (String, StudyExportFormat) -> Boolean,
    onResult: (String, StudyExportFormat, String?) -> Unit,
    onLaunchFailed: (String, StudyExportFormat) -> Unit) {
    val launcher = rememberLauncherForActivityResult(CreateLocalBook(request.format.mimeType)) { uri ->
        onResult(request.requestId, request.format, uri?.toString())
    }
    LaunchedEffect(request.requestId) {
        if (claimLaunch(request.requestId, request.format)) {
            try { launcher.launch(request.suggestedFileName) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: RuntimeException) { onLaunchFailed(request.requestId, request.format) }
        }
    }
}
