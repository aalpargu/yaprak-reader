package com.yaprak.reader.feature.librarynotes

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.yaprak.reader.domain.notes.LibraryNotesExportFormat
import com.yaprak.reader.feature.library.CreateLocalBook
import kotlinx.coroutines.CancellationException

/** Keep registered through native-picker pause. Only a resumed current owner can claim a launch. */
@Composable
internal fun LibraryNotesExportPicker(request: LibraryNotesExportRequest, mayLaunch: Boolean,
    claim: (String, LibraryNotesExportFormat) -> Boolean,
    onResult: (String, LibraryNotesExportFormat, String?) -> Unit,
    onLaunchFailed: (String, LibraryNotesExportFormat) -> Unit) {
    val launcher = rememberLauncherForActivityResult(CreateLocalBook(request.format.mimeType)) { uri ->
        onResult(request.requestId, request.format, uri?.toString())
    }
    LaunchedEffect(request.requestId, mayLaunch) {
        if (mayLaunch && claim(request.requestId, request.format)) {
            try { launcher.launch(request.suggestedFileName) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: RuntimeException) { onLaunchFailed(request.requestId, request.format) }
        }
    }
}
