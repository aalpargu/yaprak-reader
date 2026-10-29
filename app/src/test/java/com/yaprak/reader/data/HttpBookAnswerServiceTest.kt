package com.yaprak.reader.data

import android.app.Application
import com.yaprak.reader.data.assistant.*
import com.yaprak.reader.domain.assistant.*
import com.yaprak.reader.fixtures.AssistantFixtures as F
import java.io.*
import java.net.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class HttpBookAnswerServiceTest {
    private class Connection(url: URL, private val body: ByteArray) : HttpURLConnection(url) {
        val sent = ByteArrayOutputStream(); val disconnected = AtomicInteger(); val closed = AtomicInteger()
        var status = 200; var mime: String? = "application/json; charset=utf-8"; var encoding: String? = null
        var size = -1L; var error: Exception? = null; var stream: InputStream? = null
        var onDisconnect: () -> Unit = {}
        override fun connect() = Unit
        override fun usingProxy() = false
        override fun disconnect() { disconnected.incrementAndGet(); onDisconnect() }
        override fun getResponseCode(): Int { error?.let { throw it }; return status }
        override fun getContentType() = mime
        override fun getContentEncoding() = encoding
        override fun getContentLengthLong() = size
        override fun getOutputStream(): OutputStream = sent
        override fun getInputStream(): InputStream = stream ?: object : ByteArrayInputStream(body) {
            override fun close() { closed.incrementAndGet(); super.close() }
        }
    }
    private suspend fun withService(configure: (Connection) -> Unit = {}, block: suspend (HttpBookAnswerService, Connection, BookQuestionRequest) -> Unit) {
        val request = F.request(); val executor = answerExecutor()
        val connection = Connection(F.connection().endpoint.toURL(), F.response(request.sources.requestId).toByteArray()).also(configure)
        try { block(HttpBookAnswerService({ connection }, executor), connection, request) }
        finally { executor.shutdownNow(); assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS)) }
    }
    @Test fun `explicit POST sends only bounded question sources to exact backend and disables redirects cache`() = runBlocking {
        withService { service, connection, request ->
            val result = service.answer(request, F.connection()); assertEquals(request.sources.requestId, result.requestId)
            assertEquals("POST", connection.requestMethod); assertFalse(connection.instanceFollowRedirects); assertFalse(connection.useCaches)
            assertEquals("Bearer ${F.connection().accessToken}", connection.getRequestProperty("Authorization"))
            assertEquals("no-store", connection.getRequestProperty("Cache-Control")); assertNull(connection.getRequestProperty("Cookie"))
            assertArrayEquals(bookQuestionBytes(request), connection.sent.toByteArray()); assertEquals(1, connection.closed.get())
        }
    }
    @Test fun selectedTextUsesSameBoundedPostAndTypedSnapshot() = runBlocking {
        val request = com.yaprak.reader.fixtures.SelectedAssistantFixtures.question()
        val executor = answerExecutor()
        val connection = Connection(F.connection().endpoint.toURL(), F.response(request.sources.requestId).toByteArray())
        try {
            val service = HttpBookAnswerService({ connection }, executor)
            val answer = service.answerSelection(request, F.connection())
            validateSelectedTextAnswer(request, answer)
            assertSame(request.sources.references.single(), answer.statements.single().citations.single().source)
            assertArrayEquals(selectedTextQuestionBytes(request), connection.sent.toByteArray())
            assertEquals("POST", connection.requestMethod); assertFalse(connection.useCaches); assertFalse(connection.instanceFollowRedirects)
            assertEquals("Bearer ${F.connection().accessToken}", connection.getRequestProperty("Authorization"))
            assertEquals(1, connection.closed.get()); assertTrue(connection.disconnected.get() >= 1)
        } finally { executor.shutdownNow(); assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS)) }
    }
    @Test fun selectedTextCancellationDisconnectsActiveRead() = runBlocking {
        val request = com.yaprak.reader.fixtures.SelectedAssistantFixtures.question()
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val executor = answerExecutor()
        val connection = Connection(F.connection().endpoint.toURL(), byteArrayOf()).apply { stream = object : InputStream() {
            override fun read(): Int { entered.countDown(); release.await(2, TimeUnit.SECONDS); return -1 }
        } }
        try {
            val service = HttpBookAnswerService({ connection }, executor)
            val job = async { service.answerSelection(request, F.connection()) }
            withContext(Dispatchers.IO) { assertTrue(entered.await(2, TimeUnit.SECONDS)) }
            job.cancel(); release.countDown()
            assertTrue(runCatching { job.await() }.exceptionOrNull() is CancellationException)
            assertTrue(connection.disconnected.get() >= 1)
        } finally { release.countDown(); executor.shutdownNow(); assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS)) }
    }
    @Test fun successCannotResumeBeforeOwnedDisconnectCompletes() = runBlocking {
        val request = F.request(); val executor = answerExecutor()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val connection = Connection(F.connection().endpoint.toURL(), F.response(request.sources.requestId).toByteArray()).apply {
            onDisconnect = { entered.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)) }
        }
        try {
            val pending = async { HttpBookAnswerService({ connection }, executor).answer(request, F.connection()) }
            withContext(Dispatchers.IO) { assertTrue(entered.await(5, TimeUnit.SECONDS)) }
            assertFalse(pending.isCompleted); assertEquals(1, connection.closed.get())
            release.countDown(); assertEquals(request.sources.requestId, pending.await().requestId)
            assertEquals(1, connection.disconnected.get())
        } finally { release.countDown(); executor.shutdownNow(); assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS)) }
    }
    @Test fun typedFailureCannotResumeBeforeOwnedDisconnectCompletes() = runBlocking {
        val request = F.request(); val executor = answerExecutor()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val connection = Connection(F.connection().endpoint.toURL(), byteArrayOf()).apply {
            status = 503; onDisconnect = { entered.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)) }
        }
        try {
            val pending = async { runCatching { HttpBookAnswerService({ connection }, executor).answer(request, F.connection()) }.exceptionOrNull() }
            withContext(Dispatchers.IO) { assertTrue(entered.await(5, TimeUnit.SECONDS)) }
            assertFalse(pending.isCompleted); release.countDown()
            assertEquals(BookAnswerFailure.UNAVAILABLE, (pending.await() as BookAnswerException).reason)
            assertEquals(1, connection.disconnected.get()); assertEquals(0, connection.closed.get())
        } finally { release.countDown(); executor.shutdownNow(); assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS)) }
    }
    @Test fun `status failures are typed with no error body parsing or automatic retry`() = runBlocking {
        for ((status, reason) in listOf(401 to BookAnswerFailure.UNAUTHORIZED, 403 to BookAnswerFailure.UNAUTHORIZED,
            409 to BookAnswerFailure.INCOMPLETE, 413 to BookAnswerFailure.LIMIT, 422 to BookAnswerFailure.REFUSED,
            429 to BookAnswerFailure.BUSY, 503 to BookAnswerFailure.UNAVAILABLE, 302 to BookAnswerFailure.INVALID_RESPONSE)) {
            withService({ it.status = status }) { service, connection, request ->
                assertEquals(reason, (runCatching { service.answer(request, F.connection()) }.exceptionOrNull() as BookAnswerException).reason)
                assertEquals(0, connection.closed.get()); assertArrayEquals(bookQuestionBytes(request), connection.sent.toByteArray())
            }
        }
    }
    @Test fun `oversized actual declared compressed or wrong charset response is never a partial answer`() = runBlocking {
        for ((change, reason) in listOf<Pair<(Connection) -> Unit, BookAnswerFailure>>(
            { connection: Connection -> connection.size = MAX_BOOK_ANSWER_BYTES + 1L } to BookAnswerFailure.LIMIT,
            { connection: Connection -> connection.stream = ByteArrayInputStream(ByteArray(MAX_BOOK_ANSWER_BYTES + 1)) } to BookAnswerFailure.LIMIT,
            { connection: Connection -> connection.mime = "text/html" } to BookAnswerFailure.INVALID_RESPONSE,
            { connection: Connection -> connection.mime = "application/json; charset=iso-8859-1" } to BookAnswerFailure.INVALID_RESPONSE,
            { connection: Connection -> connection.encoding = "gzip" } to BookAnswerFailure.INVALID_RESPONSE)) {
            withService(change) { service, _, request -> assertEquals(reason,
                (runCatching { service.answer(request, F.connection()) }.exceptionOrNull() as BookAnswerException).reason) }
        }
    }
    @Test fun `network and timeout do not leak provider error details`() = runBlocking {
        for ((error, reason) in listOf(IOException("secret-word-body") to BookAnswerFailure.NETWORK,
            SocketTimeoutException("secret-word-body") to BookAnswerFailure.TIMEOUT)) {
            withService({ it.error = error }) { service, _, request ->
                val failure = runCatching { service.answer(request, F.connection()) }.exceptionOrNull() as BookAnswerException
                assertEquals(reason, failure.reason); assertFalse(failure.toString().contains("secret-word-body")); assertNull(failure.cause)
            }
        }
    }
    @Test fun `caller cancellation interrupts and disconnects active read and rejects late success`() = runBlocking {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        withService({ connection -> connection.stream = object : InputStream() {
            override fun read(): Int { entered.countDown(); release.await(2, TimeUnit.SECONDS); return -1 }
        } }) { service, connection, request ->
            val job = async { service.answer(request, F.connection()) }
            withContext(Dispatchers.IO) { assertTrue(entered.await(2, TimeUnit.SECONDS)) }
            job.cancel(); release.countDown(); assertTrue(runCatching { job.await() }.exceptionOrNull() is CancellationException)
            assertTrue(connection.disconnected.get() >= 1)
        }
    }
}
