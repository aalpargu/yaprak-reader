package com.yaprak.reader.data.translation

import com.yaprak.reader.domain.translation.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*

/** A callback owns native work until completion, independently of a cancelled UI waiter. */
internal interface TranslationBackend {
    fun models(done: (Result<Boolean>) -> Unit)
    fun downloadOverWifi(done: (Result<Unit>) -> Unit)
    fun translate(text: String, done: (Result<String>) -> Unit)
    fun deleteTurkishModel(done: (Result<Unit>) -> Unit)
}

/** App singleton; at most one native operation, no detached coroutine or blocked download worker. */
internal class LocalEnglishTurkishTranslation(
    private val backend: TranslationBackend,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : EnglishTurkishTranslation {
    private enum class Kind { MODELS, DOWNLOAD, TRANSLATE, DELETE }
    private class Operation(val kind: Kind) { val completed = AtomicBoolean(false) }
    private val active = AtomicReference<Operation?>(null)

    override suspend fun models(): TranslationModels {
        currentCoroutineContext().ensureActive()
        val ongoing = active.get()
        if (ongoing?.kind == Kind.DOWNLOAD) return TranslationModels.DOWNLOADING
        if (ongoing != null) throw TranslationException(TranslationFailure.BUSY)
        return if (operate(Kind.MODELS, TranslationFailure.MODELS, backend::models)) TranslationModels.READY else TranslationModels.MISSING
    }
    override suspend fun downloadOverWifi() {
        operate(Kind.DOWNLOAD, TranslationFailure.DOWNLOAD, backend::downloadOverWifi)
        if (models() != TranslationModels.READY) throw TranslationException(TranslationFailure.DOWNLOAD)
    }
    override suspend fun translate(text: String): String {
        validateTranslationText(text)
        return operate<String>(Kind.TRANSLATE, TranslationFailure.TRANSLATE) { done ->
            val checked = AtomicBoolean(false)
            backend.models { status ->
                if (!checked.compareAndSet(false, true)) return@models
                if (status.isFailure) done(Result.failure(TranslationException(TranslationFailure.MODELS)))
                else if (status.getOrNull() != true) done(Result.failure(TranslationException(TranslationFailure.MODEL_MISSING)))
                else try { backend.translate(text, done) }
                catch (_: Exception) { done(Result.failure(TranslationException(TranslationFailure.TRANSLATE))) }
            }
        }.let { output ->
            try { validateTranslationText(output) } catch (error: TranslationException) {
                throw TranslationException(if (error.reason == TranslationFailure.LIMIT) TranslationFailure.LIMIT else TranslationFailure.TRANSLATE)
            }
        }
    }
    override suspend fun deleteTurkishModel() {
        operate(Kind.DELETE, TranslationFailure.DELETE, backend::deleteTurkishModel)
        if (models() != TranslationModels.MISSING) throw TranslationException(TranslationFailure.DELETE)
    }

    private suspend fun <T> operate(kind: Kind, failure: TranslationFailure,
        start: ((Result<T>) -> Unit) -> Unit): T {
        val caller = currentCoroutineContext(); caller.ensureActive()
        val operation = Operation(kind)
        if (!active.compareAndSet(null, operation)) throw TranslationException(TranslationFailure.BUSY)
        val result = CompletableDeferred<T>()
        val started = AtomicBoolean(false)
        fun finish(value: Result<T>) {
            if (!operation.completed.compareAndSet(false, true)) return
            active.compareAndSet(operation, null)
            value.fold({ result.complete(it) }, { error ->
                result.completeExceptionally(if (error is TranslationException) error else TranslationException(failure))
            })
        }
        try {
            // Nonblocking registration finishes before cancellation can hand back to the caller.
            // The callback owns its client; only this short setup runs in NonCancellable.
            withContext(NonCancellable + io) {
                caller.ensureActive(); started.set(true)
                try { start(::finish) } catch (_: Exception) { finish(Result.failure(TranslationException(failure))) }
            }
        } catch (cancelled: CancellationException) {
            if (!started.get()) finish(Result.failure(cancelled))
            throw cancelled
        } catch (_: Exception) {
            finish(Result.failure(TranslationException(failure)))
        }
        caller.ensureActive()
        return result.await()
    }
}
