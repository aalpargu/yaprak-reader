package com.yaprak.reader.feature.reader

import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.reader.ReaderSurface

/** Applies an accepted preference to each PDF surface without touching its navigation or observers. */
internal class ReaderPdfNightModeBinding {
    private var appliedSurface: ReaderSurface? = null
    private var appliedEnabled: Boolean? = null

    fun update(surface: ReaderSurface?, format: BookFormat?, preference: PdfNightModeState,
        readerReady: Boolean) {
        if (!readerReady || format != BookFormat.PDF || !preference.canToggle) return
        val target = surface ?: return
        val controller = target.pdfNightMode ?: return
        if (appliedSurface === target && appliedEnabled == preference.enabled) return
        controller.apply(preference.enabled)
        appliedSurface = target
        appliedEnabled = preference.enabled
    }
}
