@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)

package com.yaprak.reader.formats.epub

import com.yaprak.reader.domain.settings.EpubSettings
import com.yaprak.reader.reader.EpubImageFilterMode
import org.readium.r2.navigator.epub.EpubPreferences

/** Both appearance inputs share one complete submission to the current navigator. */
internal class EpubAppearanceBinding(
    private val darkSystem: Boolean,
    private val textAlignmentSupported: Boolean = true,
    private val spacingSupport: EpubSpacingSupport = EpubSpacingSupport(),
    private val submit: (EpubPreferences) -> Unit,
) {
    private var settings: EpubSettings? = null
    private var imageFilterMode = EpubImageFilterMode.ORIGINAL
    private var submitted: EpubPreferences? = null

    fun applySettings(settings: EpubSettings) {
        this.settings = settings
        submitCurrent()
    }

    fun applyImageFilter(mode: EpubImageFilterMode) {
        val previous = imageFilterMode
        imageFilterMode = mode
        try {
            submitCurrent()
        } catch (error: Throwable) {
            imageFilterMode = previous
            throw error
        }
    }

    private fun submitCurrent() {
        val preferences = settings?.toReadium(darkSystem, imageFilterMode, textAlignmentSupported, spacingSupport) ?: return
        if (preferences == submitted) return
        submit(preferences)
        // A synchronous failure must leave this complete value eligible for retry.
        submitted = preferences
    }
}
