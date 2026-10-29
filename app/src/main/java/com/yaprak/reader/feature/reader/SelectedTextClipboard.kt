package com.yaprak.reader.feature.reader

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context

/** Explicit preview, passage, note and saved-word actions write their plain text payload. */
internal fun copyPlainSelectedText(context: Context, text: String, label: String = "Yaprak seçili metin") {
    val clipboard = context.getSystemService(ClipboardManager::class.java)
        ?: throw IllegalStateException("Clipboard unavailable")
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
}
