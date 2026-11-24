package com.yaprak.reader.feature.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.library.BookReadingStatus
import com.yaprak.reader.domain.library.LibraryRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Offer once per reader session: reaching the end of a book that is not yet marked finished. */
internal data class FinishBookState(val offeredFor: String? = null, val saving: Boolean = false,
    val done: Boolean = false, val failed: Boolean = false)

internal class FinishBookViewModel(private val repository: LibraryRepository) : ViewModel() {
    private val mutable = MutableStateFlow(FinishBookState())
    val state = mutable.asStateFlow()
    private val dismissed = mutableSetOf<String>()

    /** Called when the reader shows the book's end (position 100 %). */
    fun reachedEnd(book: Book) {
        if (book.organization.readingStatus == BookReadingStatus.COMPLETED || book.id in dismissed) return
        if (mutable.value.offeredFor == book.id) return
        mutable.value = FinishBookState(offeredFor = book.id)
    }

    fun dismiss() {
        mutable.value.offeredFor?.let(dismissed::add)
        mutable.value = FinishBookState()
    }

    fun markFinished(book: Book) {
        val current = mutable.value
        if (current.offeredFor != book.id || current.saving) return
        mutable.value = current.copy(saving = true, failed = false)
        viewModelScope.launch {
            val ok = try {
                // Exact id + hash; the repository records the completion date on this transition.
                repository.updateOrganization(book.id, book.sha256,
                    book.organization.copy(readingStatus = BookReadingStatus.COMPLETED))
            } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { false }
            if (mutable.value.offeredFor != book.id) return@launch
            if (ok) { dismissed += book.id; mutable.value = FinishBookState(offeredFor = book.id, done = true) }
            else mutable.value = mutable.value.copy(saving = false, failed = true)
        }
    }
}
