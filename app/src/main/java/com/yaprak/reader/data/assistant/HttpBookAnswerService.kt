package com.yaprak.reader.data.assistant

import com.yaprak.reader.domain.assistant.*
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HttpsURLConnection
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine

/** An explicitly configured Yaprak backend. Provider credentials never enter Android. No retries. */
internal class HttpBookAnswerService(
    private val connections: (URL) -> HttpURLConnection = { it.openConnection() as HttpsURLConnection },
    private val executor: ThreadPoolExecutor = answerExecutor(),
    private val monotonicNanos: () -> Long = System::nanoTime,
) : BookAnswerService, SelectedTextAnswerService {
    override suspend fun answer(request: BookQuestionRequest, connection: BookAnswerConnection): BookAnswer {
        return perform(bookQuestionBytes(request), connection) { parseBookAnswer(request, it) }
    }
    override suspend fun answerSelection(request: SelectedTextQuestionRequest, connection: BookAnswerConnection): SelectedTextAnswer =
        perform(selectedTextQuestionBytes(request), connection) { parseSelectedTextAnswer(request, it) }

    private suspend fun <T> perform(payload: ByteArray, connection: BookAnswerConnection, decode: (ByteArray) -> T): T {
        return suspendCancellableCoroutine { continuation ->
            val socket = AtomicReference<HttpURLConnection?>()
            fun finishFailure(error: Exception) {
                disconnectAnswer(socket.getAndSet(null))
                if (continuation.isActive) continuation.resumeWithException(error)
            }
            val task = FutureTask<Unit> {
                try {
                    if (!continuation.isActive) throw CancellationException()
                    val started = monotonicNanos()
                    val active = connections(connection.endpoint.toURL())
                    socket.set(active)
                    fun remainingMillis(): Int {
                        if (!continuation.isActive || Thread.currentThread().isInterrupted) throw CancellationException()
                        val elapsed = monotonicNanos() - started
                        val budget = TimeUnit.SECONDS.toNanos(40)
                        if (elapsed < 0 || elapsed >= budget) throw SocketTimeoutException()
                        return TimeUnit.NANOSECONDS.toMillis(budget - elapsed).coerceIn(1, 35_000).toInt()
                    }
                    remainingMillis()
                    active.requestMethod = "POST"
                    active.instanceFollowRedirects = false
                    active.useCaches = false
                    active.doOutput = true
                    active.connectTimeout = 8000
                    active.readTimeout = remainingMillis()
                    active.setFixedLengthStreamingMode(payload.size)
                    active.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    active.setRequestProperty("Accept", "application/json")
                    active.setRequestProperty("Accept-Encoding", "identity")
                    active.setRequestProperty("Cache-Control", "no-store")
                    active.setRequestProperty("Authorization", "Bearer ${connection.accessToken}")
                    active.outputStream.use { output ->
                        var offset = 0
                        while (offset < payload.size) {
                            remainingMillis()
                            val count = minOf(8192, payload.size - offset)
                            output.write(payload, offset, count)
                            offset += count
                        }
                    }
                    remainingMillis()
                    when (active.responseCode) {
                        200 -> Unit
                        401, 403 -> failAnswer(BookAnswerFailure.UNAUTHORIZED)
                        409 -> failAnswer(BookAnswerFailure.INCOMPLETE)
                        413 -> failAnswer(BookAnswerFailure.LIMIT)
                        422 -> failAnswer(BookAnswerFailure.REFUSED)
                        429 -> failAnswer(BookAnswerFailure.BUSY)
                        503 -> failAnswer(BookAnswerFailure.UNAVAILABLE)
                        504 -> failAnswer(BookAnswerFailure.TIMEOUT)
                        else -> failAnswer(BookAnswerFailure.INVALID_RESPONSE)
                    }
                    remainingMillis()
                    val type = active.contentType?.lowercase(java.util.Locale.ROOT)?.split(';')?.map(String::trim)
                    if (type == null || type.firstOrNull() != "application/json" || type.drop(1).any {
                            it.startsWith("charset=") && it.substringAfter('=').trim(' ', '"') != "utf-8" } ||
                        !active.contentEncoding.isNullOrBlank() && !active.contentEncoding.equals("identity", true))
                        failAnswer(BookAnswerFailure.INVALID_RESPONSE)
                    if (active.contentLengthLong > MAX_BOOK_ANSWER_BYTES) failAnswer(BookAnswerFailure.LIMIT)
                    val bytes = active.inputStream.use { input ->
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            active.readTimeout = remainingMillis()
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (output.size() + count > MAX_BOOK_ANSWER_BYTES) failAnswer(BookAnswerFailure.LIMIT)
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray()
                    }
                    remainingMillis()
                    val answer = decode(bytes)
                    remainingMillis()
                    // Completion owns socket cleanup; a resumed caller cannot observe an open request.
                    disconnectAnswer(socket.getAndSet(null))
                    remainingMillis()
                    continuation.resume(answer)
                } catch (cancelled: CancellationException) {
                    finishFailure(cancelled)
                } catch (failure: BookAnswerException) {
                    finishFailure(failure)
                } catch (_: SocketTimeoutException) {
                    finishFailure(BookAnswerException(BookAnswerFailure.TIMEOUT))
                } catch (_: IOException) {
                    finishFailure(BookAnswerException(BookAnswerFailure.NETWORK))
                } catch (_: Exception) {
                    finishFailure(BookAnswerException(BookAnswerFailure.INVALID_RESPONSE))
                } finally { disconnectAnswer(socket.getAndSet(null)) }
            }
            continuation.invokeOnCancellation {
                task.cancel(true)
                executor.remove(task)
                disconnectAnswer(socket.getAndSet(null))
            }
            if (continuation.isActive) try {
                executor.execute(task)
                if (!continuation.isActive) executor.remove(task)
            } catch (_: RejectedExecutionException) {
                if (continuation.isActive) continuation.resumeWithException(BookAnswerException(BookAnswerFailure.BUSY))
            }
        }
    }
}
private fun disconnectAnswer(connection: HttpURLConnection?) {
    try { connection?.disconnect() } catch (_: Exception) { /* Cleanup cannot throw into Android Main. */ }
}
internal fun answerExecutor() = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(1),
    { work -> Thread(work, "YaprakBookAnswer").apply { isDaemon = true } }, ThreadPoolExecutor.AbortPolicy())
