package com.yaprak.reader.data.dictionary

import com.yaprak.reader.domain.dictionary.*
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
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

/** A single fixed HTTPS provider. Requests are only admitted by explicit word lookup. */
internal class OnlineEnglishDictionary(
    private val connections: (URL) -> HttpURLConnection = { it.openConnection() as HttpsURLConnection },
    private val executor: ThreadPoolExecutor = dictionaryExecutor(),
    private val monotonicNanos: () -> Long = System::nanoTime,
) : EnglishDictionary {
    override suspend fun lookup(selectedWord: String): DictionaryResult {
        val word = englishDictionaryWord(selectedWord)
        val url = dictionaryUrl(word)
        return suspendCancellableCoroutine { continuation ->
            val connection = AtomicReference<HttpURLConnection?>()
            val task = FutureTask<Unit> {
                try {
                    if (!continuation.isActive) throw CancellationException()
                    val started = monotonicNanos()
                    val active = connections(url)
                    connection.set(active)
                    if (!continuation.isActive) throw CancellationException()
                    active.requestMethod = "GET"
                    active.instanceFollowRedirects = false
                    active.useCaches = false
                    active.connectTimeout = 8000
                    active.readTimeout = 8000
                    active.setRequestProperty("Accept", "application/json")
                    active.setRequestProperty("Accept-Encoding", "identity")
                    active.setRequestProperty("User-Agent", "Yaprak/EnglishDictionary")
                    fun remainingMillis(): Int {
                        if (!continuation.isActive || Thread.currentThread().isInterrupted) throw CancellationException()
                        val elapsed = monotonicNanos() - started
                        if (elapsed < 0 || elapsed >= TimeUnit.SECONDS.toNanos(15)) throw SocketTimeoutException()
                        return TimeUnit.NANOSECONDS.toMillis(TimeUnit.SECONDS.toNanos(15) - elapsed).coerceIn(1, 8000).toInt()
                    }
                    remainingMillis()
                    val status = active.responseCode
                    remainingMillis()
                    when (status) {
                        HttpURLConnection.HTTP_NOT_FOUND -> throw DictionaryException(DictionaryFailure.NOT_FOUND)
                        429, HttpURLConnection.HTTP_UNAVAILABLE -> throw DictionaryException(DictionaryFailure.BUSY)
                        HttpURLConnection.HTTP_OK -> Unit
                        else -> throw DictionaryException(DictionaryFailure.PROVIDER)
                    }
                    val contentType = active.contentType?.lowercase(java.util.Locale.ROOT)?.split(';')?.map { it.trim() }
                    if (contentType == null || contentType.firstOrNull() != "application/json" || contentType.drop(1).any {
                            it.startsWith("charset=") && it.substringAfter('=').trim(' ', '"') != "utf-8" })
                        throw DictionaryException(DictionaryFailure.PROVIDER)
                    if (!active.contentEncoding.isNullOrBlank() && !active.contentEncoding.equals("identity", ignoreCase = true))
                        throw DictionaryException(DictionaryFailure.PROVIDER)
                    if (active.contentLengthLong > MAX_DICTIONARY_RESPONSE_BYTES) throw DictionaryException(DictionaryFailure.TOO_LARGE)
                    val bytes = active.inputStream.use { input ->
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            active.readTimeout = remainingMillis()
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (output.size() + count > MAX_DICTIONARY_RESPONSE_BYTES) throw DictionaryException(DictionaryFailure.TOO_LARGE)
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray()
                    }
                    remainingMillis()
                    val result = parseDictionaryResponse(word, bytes)
                    remainingMillis()
                    continuation.resume(result)
                } catch (cancelled: CancellationException) {
                    if (continuation.isActive) continuation.resumeWithException(cancelled)
                } catch (failure: DictionaryException) {
                    if (continuation.isActive) continuation.resumeWithException(failure)
                } catch (_: SocketTimeoutException) {
                    if (continuation.isActive) continuation.resumeWithException(DictionaryException(DictionaryFailure.TIMEOUT))
                } catch (_: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(DictionaryException(DictionaryFailure.NETWORK))
                } catch (_: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(DictionaryException(DictionaryFailure.PROVIDER))
                } finally {
                    disconnectDictionaryConnection(connection.getAndSet(null))
                }
            }
            continuation.invokeOnCancellation {
                task.cancel(true)
                executor.remove(task)
                disconnectDictionaryConnection(connection.getAndSet(null))
            }
            if (continuation.isActive) {
                try {
                    executor.execute(task)
                    if (!continuation.isActive) executor.remove(task)
                }
                catch (_: RejectedExecutionException) {
                    if (continuation.isActive) continuation.resumeWithException(DictionaryException(DictionaryFailure.BUSY))
                }
            }
        }
    }
}

private fun disconnectDictionaryConnection(connection: HttpURLConnection?) {
    try { connection?.disconnect() } catch (_: Exception) { /* The bounded request still exits; cleanup must not throw into Android Main. */ }
}

internal fun dictionaryUrl(word: String) = URL("https://api.dictionaryapi.dev/api/v2/entries/en/" +
    URLEncoder.encode(englishDictionaryWord(word), Charsets.UTF_8.name()).replace("+", "%20"))
internal fun dictionaryExecutor() = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(1),
    { work -> Thread(work, "YaprakDictionary").apply { isDaemon = true } }, ThreadPoolExecutor.AbortPolicy())
