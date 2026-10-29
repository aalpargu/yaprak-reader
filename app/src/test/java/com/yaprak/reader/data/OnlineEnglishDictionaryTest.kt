package com.yaprak.reader.data

import android.app.Application
import com.yaprak.reader.data.dictionary.*
import com.yaprak.reader.domain.dictionary.*
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class OnlineEnglishDictionaryTest {
    private val body = """[{"word":"leaf","meanings":[{"partOfSpeech":"noun","definitions":[{"definition":"A plant part."}]}]}]""".toByteArray()
    private class Connection(url: URL, private val body: ByteArray) : HttpURLConnection(url) {
        var status = 200; var mime: String? = "application/json; charset=utf-8"; var encoding: String? = null
        var declaredSize = -1L; var failure: Exception? = null; var stream: InputStream? = null
        val opened = AtomicInteger(); val disconnected = AtomicInteger(); val closed = AtomicInteger()
        var afterDisconnect: () -> Unit = {}
        override fun connect() = Unit
        override fun usingProxy() = false
        override fun disconnect() { disconnected.incrementAndGet(); afterDisconnect() }
        override fun getResponseCode(): Int { failure?.let { throw it }; return status }
        override fun getContentType() = mime
        override fun getContentEncoding() = encoding
        override fun getContentLengthLong() = declaredSize
        override fun getInputStream(): InputStream {
            opened.incrementAndGet()
            return stream ?: object : ByteArrayInputStream(body) { override fun close() { closed.incrementAndGet(); super.close() } }
        }
    }
    private suspend fun <T> withConnection(configure: (Connection) -> Unit = {}, block: suspend (OnlineEnglishDictionary, Connection) -> T): T {
        val executor = dictionaryExecutor(); val connection = Connection(dictionaryUrl("leaf"), body).also(configure)
        try { return block(OnlineEnglishDictionary({ connection }, executor), connection) }
        finally { executor.shutdownNow(); assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS)) }
    }
    @Test fun `fixed encoded HTTPS endpoint sends one word GET only and closes its response`() = runBlocking {
        assertEquals("https://api.dictionaryapi.dev/api/v2/entries/en/isn%E2%80%99t", dictionaryUrl("isn’t").toExternalForm())
        withConnection { repository, connection ->
            val found = repository.lookup("  LEAF  ")
            assertEquals("leaf", found.query)
            assertEquals("GET", connection.requestMethod); assertFalse(connection.instanceFollowRedirects); assertFalse(connection.useCaches)
            assertFalse(connection.doOutput); assertEquals("application/json", connection.getRequestProperty("Accept"))
            assertEquals("identity", connection.getRequestProperty("Accept-Encoding"))
            assertNull(connection.getRequestProperty("Authorization")); assertNull(connection.getRequestProperty("Cookie"))
            assertEquals(1, connection.opened.get()); assertEquals(1, connection.closed.get())
            // Completion can be delivered just before finally disconnects; executor drain below verifies closure.
        }
    }
    @Test fun `invalid paragraph never creates a connection`() = runBlocking {
        val executor = dictionaryExecutor(); var connections = 0
        try {
            val repository = OnlineEnglishDictionary({ connections++; Connection(it, body) }, executor)
            assertEquals(DictionaryFailure.INVALID_WORD, (runCatching { repository.lookup("a whole paragraph") }
                .exceptionOrNull() as DictionaryException).reason)
            assertEquals(0, connections)
        } finally { executor.shutdownNow() }
    }
    @Test fun `not found busy redirects and failed provider statuses never parse or follow response`() = runBlocking {
        listOf(404 to DictionaryFailure.NOT_FOUND, 429 to DictionaryFailure.BUSY, 503 to DictionaryFailure.BUSY,
            302 to DictionaryFailure.PROVIDER, 401 to DictionaryFailure.PROVIDER, 500 to DictionaryFailure.PROVIDER).forEach { (status, reason) ->
            withConnection({ it.status = status }) { repository, connection ->
                assertEquals(reason, (runCatching { repository.lookup("leaf") }.exceptionOrNull() as DictionaryException).reason)
                assertEquals(0, connection.opened.get()); assertFalse(connection.instanceFollowRedirects)
            }
        }
    }
    @Test fun `MIME encoding announced and actual body limits fail without truncated definition success`() = runBlocking {
        listOf<(Connection) -> Unit>({ it.mime = "text/html" }, { it.encoding = "gzip" },
            { it.declaredSize = MAX_DICTIONARY_RESPONSE_BYTES + 1L },
            { it.stream = ByteArrayInputStream(ByteArray(MAX_DICTIONARY_RESPONSE_BYTES + 1)) }).forEachIndexed { index, config ->
            withConnection(config) { repository, _ ->
                assertEquals(if (index < 2) DictionaryFailure.PROVIDER else DictionaryFailure.TOO_LARGE,
                    (runCatching { repository.lookup("leaf") }.exceptionOrNull() as DictionaryException).reason)
            }
        }
    }
    @Test fun `network and socket timeout remain distinct without provider messages`() = runBlocking {
        listOf(IOException("controlled sensitive detail") to DictionaryFailure.NETWORK,
            SocketTimeoutException("controlled timeout detail") to DictionaryFailure.TIMEOUT).forEach { (failure, reason) ->
            withConnection({ it.failure = failure }) { repository, _ ->
                val error = runCatching { repository.lookup("leaf") }.exceptionOrNull() as DictionaryException
                assertEquals(reason, error.reason); assertEquals(reason.name, error.message); assertNull(error.cause)
            }
        }
    }
    @Test fun `caller cancellation disconnects a blocked read and returns cancellation without a result`() = runBlocking {
        val started = CountDownLatch(1); val released = CountDownLatch(1)
        withConnection({ connection ->
            connection.afterDisconnect = { released.countDown() }
            connection.stream = object : InputStream() {
                override fun read(): Int { started.countDown(); released.await(2, TimeUnit.SECONDS); throw IOException("cancelled read") }
            }
        }) { repository, connection ->
            val request = async(Dispatchers.Default) { repository.lookup("leaf") }
            assertTrue(started.await(2, TimeUnit.SECONDS)); request.cancelAndJoin()
            assertTrue(request.isCancelled); assertTrue(released.await(2, TimeUnit.SECONDS)); assertEquals(1, connection.disconnected.get())
        }
    }
    @Test fun `one active one queued capacity reports busy and cancelled queued request frees the next lookup`() = runBlocking {
        val executor = dictionaryExecutor(); val entered = CountDownLatch(1); val released = CountDownLatch(1)
        val connections = AtomicInteger()
        val first = Connection(dictionaryUrl("leaf"), body).apply {
            afterDisconnect = { released.countDown() }
            stream = object : InputStream() {
                override fun read(): Int { entered.countDown(); released.await(2, TimeUnit.SECONDS); throw IOException("controlled wait") }
            }
        }
        try {
            val repository = OnlineEnglishDictionary({ if (connections.incrementAndGet() == 1) first else Connection(it, body) }, executor)
            val active = async(Dispatchers.Default) { repository.lookup("leaf") }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val queued = async(Dispatchers.Default) { repository.lookup("leaf") }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (executor.queue.isEmpty()) { assertTrue(System.nanoTime() < deadline); Thread.yield() }
            assertEquals(DictionaryFailure.BUSY, (runCatching { repository.lookup("leaf") }.exceptionOrNull() as DictionaryException).reason)
            queued.cancelAndJoin(); assertTrue(executor.queue.isEmpty())
            val next = async(Dispatchers.Default) { repository.lookup("leaf") }
            active.cancelAndJoin(); assertEquals("leaf", next.await().query); assertEquals(2, connections.get())
        } finally { released.countDown(); executor.shutdownNow(); assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS)) }
    }

    @Test fun `total deadline rejects slow response even when individual reads return`() = runBlocking {
        val executor = dictionaryExecutor(); val clock = java.util.concurrent.atomic.AtomicLong(0)
        val connection = Connection(dictionaryUrl("leaf"), body)
        try {
            val repository = OnlineEnglishDictionary({ connection }, executor, { clock.getAndAdd(TimeUnit.SECONDS.toNanos(8)) })
            assertEquals(DictionaryFailure.TIMEOUT, (runCatching { repository.lookup("leaf") }.exceptionOrNull() as DictionaryException).reason)
            assertEquals(0, connection.opened.get())
        } finally { executor.shutdownNow(); assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS)) }
    }
}
