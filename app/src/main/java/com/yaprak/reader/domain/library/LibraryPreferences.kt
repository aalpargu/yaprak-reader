package com.yaprak.reader.domain.library

import kotlinx.coroutines.flow.Flow

enum class LibraryLayout { GRID, LIST }
enum class LibrarySort { RECENT, LAST_READ, TITLE, AUTHOR }

/** Device-local library presentation preferences, excluded from the book backup. */
data class LibraryPreferences(
    val layout: LibraryLayout = LibraryLayout.GRID,
    val sort: LibrarySort = LibrarySort.RECENT,
)

interface LibraryPreferencesRepository {
    val libraryPreferences: Flow<LibraryPreferences>
    /** Completes only after both preferences have been durably written together. */
    suspend fun saveLibraryPreferences(preferences: LibraryPreferences)
}
