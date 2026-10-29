package com.yaprak.reader.domain.library

interface BookImporter {
    suspend fun import(documentUri: String): ImportResult
}

sealed interface ImportResult {
    data object Added : ImportResult
    data object Duplicate : ImportResult
    data class Failed(val reason: ImportFailure) : ImportResult
}

enum class ImportFailure { UNSUPPORTED, INVALID, PROTECTED, TOO_LARGE, ACCESS, IO, UNEXPECTED }

class BookImportException(val reason: ImportFailure) : Exception(reason.name)
