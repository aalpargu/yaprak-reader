package com.yaprak.reader.feature.reader

import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.statistics.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

/** Main-confined owner and bounded cumulative outbox. The repository owns durable transactions. */
internal class ReaderDurationController(private val repository: ReadingDurationRepository,
    private val clock: ReadingDurationClock, private val scope: CoroutineScope) {
    private val meter = ReadingDurationMeter()
    private val pending = LinkedHashMap<String, ReadingDurationReceipt>()
    private val overflow = LinkedHashMap<String, ReadingDurationReceipt>()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val changed = MutableStateFlow(0L)
    private val mutableFailure = MutableStateFlow<ReadingDurationFailure?>(null)
    val failure = mutableFailure.asStateFlow()
    private var owner: String? = null
    private var identity: DurationBook? = null
    private var allowed = false

    init {
        scope.launch {
            for (signal in wake) {
                while (mutableFailure.value == null && pending.isNotEmpty()) {
                    val receipt = pending.values.first()
                    try {
                        repository.record(receipt)
                        if (pending[receipt.id] == receipt) pending.remove(receipt.id)
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: ReadingDurationException) { fail(failure.reason) }
                    catch (_: Exception) { fail(ReadingDurationFailure.SOURCE) }
                    if (mutableFailure.value == null && overflow.isNotEmpty() && pending.size < MAX_PENDING_RECEIPTS) {
                        val retained = overflow.values.first()
                        overflow.remove(retained.id)
                        pending[retained.id] = retained
                    }
                    changed.value++
                }
            }
        }
        scope.launch {
            while (isActive) {
                delay(ReadingDurationMeter.TICK_MILLIS)
                val current = owner ?: continue
                if (mutableFailure.value == null) enqueue(meter.tick(current, clock.now()))
            }
        }
    }
    /** Admission belongs to the newest real onResume, never to an asynchronous old callback. */
    fun claim(nextOwner: String) {
        if (owner == nextOwner) return
        owner?.let { enqueue(meter.release(it, clock.now())) }
        owner = nextOwner; identity = null; allowed = false
    }
    fun update(nextOwner: String, book: Book, eligible: Boolean) {
        if (owner != nextOwner) return
        val nextIdentity = DurationBook(book.id, book.sha256)
        val now = clock.now()
        if (identity != nextIdentity) {
            enqueue(meter.claim(nextOwner, nextIdentity, now)); identity = nextIdentity
        }
        allowed = eligible && mutableFailure.value == null
        enqueue(meter.update(nextOwner, nextIdentity, allowed, now))
        if (mutableFailure.value != null) settleFailure()
    }
    fun suspend(expectedOwner: String) {
        if (owner != expectedOwner) return
        allowed = false
        identity?.let { enqueue(meter.update(expectedOwner, it, false, clock.now())) }
    }
    fun release(expectedOwner: String) {
        if (owner != expectedOwner) return
        enqueue(meter.release(expectedOwner, clock.now())); owner = null; identity = null; allowed = false
    }
    fun retry() {
        if (mutableFailure.value == null) return
        mutableFailure.value = null
        if (pending.isEmpty() && overflow.isNotEmpty()) {
            val retained = overflow.values.first(); overflow.remove(retained.id); pending[retained.id] = retained
        }
        changed.value++; wake.trySend(Unit)
    }
    suspend fun flush(): Boolean {
        enqueue(meter.checkpoint(clock.now()))
        while ((pending.isNotEmpty() || overflow.isNotEmpty()) && mutableFailure.value == null) {
            val version = changed.value
            changed.first { it != version || (pending.isEmpty() && overflow.isEmpty()) || mutableFailure.value != null }
        }
        return pending.isEmpty() && overflow.isEmpty() && mutableFailure.value == null
    }
    private fun settleFailure() {
        allowed = false
        owner?.let { current -> identity?.let { identity ->
            retain(meter.update(current, identity, false, clock.now()))
        } }
    }
    private fun fail(reason: ReadingDurationFailure) {
        mutableFailure.value = reason
        settleFailure()
    }
    private fun enqueue(receipts: List<ReadingDurationReceipt>) {
        retain(receipts)
        if (mutableFailure.value != null) settleFailure()
        if (receipts.isNotEmpty()) { changed.value++; wake.trySend(Unit) }
    }
    private fun retain(receipts: List<ReadingDurationReceipt>) {
        for (receipt in receipts) {
            if (receipt.id !in pending && pending.size >= MAX_PENDING_RECEIPTS) {
                // A tick can cross midnight and close its second interval. Keep both final
                // receipts in the bounded reserve, then synchronously stop the meter.
                check(receipt.id in overflow || overflow.size < MAX_OVERFLOW_RECEIPTS)
                val old = overflow[receipt.id]
                if (old == null || receipt.elapsedMillis >= old.elapsedMillis) overflow[receipt.id] = receipt
                mutableFailure.value = ReadingDurationFailure.LIMIT
                allowed = false
                continue
            }
            val existing = pending[receipt.id]
            if (existing == null || receipt.elapsedMillis >= existing.elapsedMillis) pending[receipt.id] = receipt
        }
    }
    companion object { const val MAX_PENDING_RECEIPTS = 64; const val MAX_OVERFLOW_RECEIPTS = 2 }
}
