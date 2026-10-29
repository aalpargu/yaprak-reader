package com.yaprak.reader.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.domain.settings.BookAppearanceResetRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class BookAppearanceResetState(
    val open: Boolean = false,
    val saving: Boolean = false,
    val saveFailed: Boolean = false,
    /** The transaction committed, but the five observed settings may not all have refreshed yet. */
    val completed: Boolean = false,
)

/** Clears only this verified EPUB's book-specific appearance choices. */
class BookAppearanceResetViewModel(private val repository: BookAppearanceResetRepository) : ViewModel() {
    private val mutable = MutableStateFlow(BookAppearanceResetState())
    val state = mutable.asStateFlow()
    private var bookKey: Pair<String, String>? = null

    fun bind(book: Book) {
        require(book.format.reflowable && book.id.isNotBlank() && book.sha256.isNotBlank())
        val key = book.id to book.sha256
        check(bookKey == null || bookKey == key) { "Book appearance reset is bound to another book" }
        bookKey = key
    }

    fun open() {
        if (bookKey != null && !state.value.saving && !state.value.completed) {
            mutable.update { it.copy(open = true, saveFailed = false) }
        }
    }

    fun dismiss() {
        if (state.value.open && !state.value.saving) {
            mutable.update { it.copy(open = false, saveFailed = false, completed = false) }
        }
    }

    fun confirm() {
        val (bookId, hash) = bookKey ?: return
        if (!state.value.open || state.value.saving || state.value.completed) return
        mutable.update { it.copy(saving = true, saveFailed = false) }
        viewModelScope.launch {
            try {
                repository.reset(bookId, hash)
                mutable.update { it.copy(open = false, saving = false, saveFailed = false, completed = true) }
            } catch (cancelled: CancellationException) {
                mutable.update { it.copy(saving = false) }
                throw cancelled
            } catch (_: Exception) {
                mutable.update { it.copy(saving = false, saveFailed = true) }
            }
        }
    }

    /** Called after all five Room flows have applied the committed reset to the reader. */
    fun ackApplied() {
        if (state.value.completed) mutable.update { it.copy(completed = false) }
    }
}
