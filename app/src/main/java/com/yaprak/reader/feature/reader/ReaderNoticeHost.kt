package com.yaprak.reader.feature.reader

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Persistent failures remain visible until their real owner clears them; no automatic menu expansion. */
@Composable
internal fun ReaderNoticeHost(notice: ReaderNotice?, onRetry: (() -> Unit)?, onDetails: () -> Unit,
    onLeave: (() -> Unit)?, modifier: Modifier = Modifier) {
    notice ?: return
    Surface(modifier.padding(16.dp).testTag("reader-notice"), shape = MaterialTheme.shapes.medium, tonalElevation = 6.dp) {
        Column(Modifier.padding(12.dp).semantics { liveRegion = LiveRegionMode.Polite }) {
            Text(when (notice.kind) {
                ReaderNoticeKind.SAVE -> "Okuma konumu kaydedilemedi."
                ReaderNoticeKind.PRESENTATION_RESTORE -> "Önceki panel geri açılamadı. Kitap kaynağını yeniden doğrulayabilirsin."
                ReaderNoticeKind.LOAD -> "Bu bölüm yüklenemedi. Kitabı yeniden açmak için kitaplığa dön."
                ReaderNoticeKind.RENDER -> "Bu sayfa çizilemedi. Kitabı yeniden açmak için kitaplığa dön."
                ReaderNoticeKind.DURATION -> "Okuma süresi kaydedilemedi."
                ReaderNoticeKind.INDEXED_SOURCE -> "Kaynak konumu açılamadı."
                ReaderNoticeKind.PDF_APPEARANCE -> "PDF görünümü uygulanamadı."
                ReaderNoticeKind.COMIC_APPEARANCE -> "Sayfa görünümü uygulanamadı."
                ReaderNoticeKind.ORIENTATION -> "Ekran yönü uygulanamadı."
                ReaderNoticeKind.PDF_CROP -> "Sayfa kırpması uygulanamadı."
            }, color = MaterialTheme.colorScheme.error)
            onRetry?.let { retry -> TextButton(retry) { Text(if (notice.kind == ReaderNoticeKind.SAVE) "Tekrar kaydet" else "Yeniden dene") } }
            TextButton(onDetails) { Text("Ayrıntılar ve yeniden dene") }
            onLeave?.let { leave -> TextButton(leave) { Text("Kitaplığa dön") } }
        }
    }
}
