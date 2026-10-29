package com.yaprak.reader.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.domain.settings.BookFontSizeRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Null inherits the current global EPUB font size; explicit 100 is a separate choice. */
data class BookFontSizeState(
    val saved: Int? = null,
    val draft: Int? = null,
    val loading: Boolean = true,
    val loadFailed: Boolean = false,
    val saveFailed: Boolean = false,
    val saving: Boolean = false,
    val open: Boolean = false,
)

/** Edits one verified EPUB's font size without changing global appearance preferences. */
class BookFontSizeViewModel(private val repository: BookFontSizeRepository) : ViewModel() {
    private val mutable = MutableStateFlow(BookFontSizeState())
    val state = mutable.asStateFlow()
    private var bookKey: Pair<String, String>? = null
    private var loadingJob: Job? = null

    fun bind(book: Book) {
        require(book.format.reflowable && book.id.isNotBlank() && book.sha256.isNotBlank())
        val key = book.id to book.sha256
        check(bookKey == null || bookKey == key) { "Book font size editor is bound to another book" }
        if (bookKey == key) return
        bookKey = key
        reload()
    }

    fun reload() {
        val (bookId, hash) = bookKey ?: return
        if (state.value.saving) return
        loadingJob?.cancel()
        mutable.update { it.copy(loading = true, loadFailed = false, saveFailed = false) }
        loadingJob = viewModelScope.launch {
            try {
                repository.observe(bookId, hash).collect { saved ->
                    require(valid(saved)) { "Invalid book font size" }
                    mutable.update { state ->
                        state.copy(
                            saved = saved,
                            draft = if (state.open && !state.loading) state.draft else saved,
                            loading = false,
                            loadFailed = false,
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutable.update { it.copy(loading = false, loadFailed = true) }
            }
        }
    }

    fun open() {
        if (bookKey != null) mutable.update { it.copy(open = true, draft = it.saved, saveFailed = false) }
    }

    fun dismiss() {
        if (!state.value.saving) mutable.update { it.copy(open = false, draft = it.saved, saveFailed = false) }
    }

    fun edit(fontSizePercent: Int?) {
        require(valid(fontSizePercent)) { "Book font size must be 75–200 in steps of 5" }
        if (state.value.open && !state.value.saving && !state.value.loading && !state.value.loadFailed) {
            mutable.update { it.copy(draft = fontSizePercent, saveFailed = false) }
        }
    }

    fun save() {
        val (bookId, hash) = bookKey ?: return
        val current = state.value
        if (!current.open || current.loading || current.loadFailed || current.saving) return
        mutable.update { it.copy(saving = true, saveFailed = false) }
        viewModelScope.launch {
            try {
                repository.save(bookId, hash, current.draft)
                mutable.update { it.copy(saved = current.draft, draft = current.draft, saving = false, open = false) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutable.update { it.copy(saving = false, saveFailed = true) }
            }
        }
    }

    private fun valid(value: Int?): Boolean = value == null || (value in 75..200 && value % 5 == 0)
}
