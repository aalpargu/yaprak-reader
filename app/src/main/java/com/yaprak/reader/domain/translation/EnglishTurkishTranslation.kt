package com.yaprak.reader.domain.translation

import com.yaprak.reader.reader.SourceRangeAnchors

const val MAX_TRANSLATION_TEXT_UNITS = 5000
enum class TranslationModels { MISSING, READY, DOWNLOADING }
enum class TranslationFailure { INVALID_TEXT, LIMIT, MODEL_MISSING, BUSY, MODELS, DOWNLOAD, TRANSLATE, DELETE }
class TranslationException(val reason: TranslationFailure) : Exception(reason.name)

/** Selected text only. Model download and deletion are distinct explicit user actions. */
interface EnglishTurkishTranslation {
    suspend fun models(): TranslationModels
    suspend fun downloadOverWifi()
    suspend fun translate(text: String): String
    suspend fun deleteTurkishModel()
}

fun validateTranslationText(text: String): String {
    if (text.length > MAX_TRANSLATION_TEXT_UNITS) throw TranslationException(TranslationFailure.LIMIT)
    if (text.isBlank() || '\u0000' in text || !SourceRangeAnchors.validUtf16(text))
        throw TranslationException(TranslationFailure.INVALID_TEXT)
    return text
}
