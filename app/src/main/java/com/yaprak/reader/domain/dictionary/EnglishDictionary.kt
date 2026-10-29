package com.yaprak.reader.domain.dictionary

import java.util.Locale

const val MAX_DICTIONARY_WORD_LENGTH = 100
data class DictionaryDefinition(val partOfSpeech: String, val definition: String, val example: String?)
data class DictionaryLicense(val name: String, val url: String?)
data class DictionaryEntry(val word: String, val phonetic: String?, val definitions: List<DictionaryDefinition>,
    val license: DictionaryLicense?, val sources: List<String>)
data class DictionaryResult(val query: String, val entries: List<DictionaryEntry>)
enum class DictionaryFailure { INVALID_WORD, NOT_FOUND, NETWORK, TIMEOUT, PROVIDER, TOO_LARGE, BUSY }
class DictionaryException(val reason: DictionaryFailure) : Exception(reason.name)
fun interface EnglishDictionary { suspend fun lookup(selectedWord: String): DictionaryResult }

/** One explicitly selected lexeme only; surrounding book text is never a lookup query. */
fun englishDictionaryWord(selection: String): String {
    val word = selection.trim()
    if (word.isEmpty() || word.length > MAX_DICTIONARY_WORD_LENGTH) invalidWord()
    var previousLetter = false
    var index = 0
    while (index < word.length) {
        val codePoint = word.codePointAt(index)
        when {
            Character.isLetter(codePoint) -> previousLetter = true
            Character.getType(codePoint) in setOf(Character.NON_SPACING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt()) ->
                if (!previousLetter) invalidWord()
            codePoint == '-'.code || codePoint == '\''.code || codePoint == '’'.code -> {
                if (!previousLetter || index + Character.charCount(codePoint) >= word.length) invalidWord()
                previousLetter = false
            }
            else -> invalidWord()
        }
        index += Character.charCount(codePoint)
    }
    val normalized = word.lowercase(Locale.ROOT)
    if (normalized.length > MAX_DICTIONARY_WORD_LENGTH) invalidWord()
    return normalized
}
private fun invalidWord(): Nothing = throw DictionaryException(DictionaryFailure.INVALID_WORD)
