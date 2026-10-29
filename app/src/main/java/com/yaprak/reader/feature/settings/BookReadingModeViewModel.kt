package com.yaprak.reader.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.settings.BookReadingModeRepository
import com.yaprak.reader.domain.settings.BookReadingMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Null means that this book inherits the global EPUB mode. */
data class BookReadingModeState(
    val saved: BookReadingMode? = null,
    val draft: BookReadingMode? = null,
    val loading: Boolean = true,
    val loadFailed: Boolean = false,
    val saveFailed: Boolean = false,
    val saving: Boolean = false,
    val open: Boolean = false,
)

/** Edits one verified reflowable book's mode without changing global preferences. */
class BookReadingModeViewModel(private val repository: BookReadingModeRepository) : ViewModel() {
    private val mutable = MutableStateFlow(BookReadingModeState())
    val state = mutable.asStateFlow()
    private var bookKey: Pair<String, String>? = null
    private var loadingJob: Job? = null

    fun bind(book: Book) {
        require(book.format.reflowable && book.id.isNotBlank() && book.sha256.isNotBlank())
        val key = book.id to book.sha256
        check(bookKey == null || bookKey == key) { "Book mode editor is bound to another book" }
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

    fun edit(mode: BookReadingMode?) {
        if (state.value.open && !state.value.saving && !state.value.loading && !state.value.loadFailed) {
            mutable.update { it.copy(draft = mode, saveFailed = false) }
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
}

