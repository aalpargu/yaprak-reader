package com.yaprak.reader.data

import com.yaprak.reader.data.translation.*
import org.junit.Assert.*
import org.junit.Test

/** Controlled native callbacks test client ownership; no real model or network runs on the host. */
class MlKitEnglishTurkishBackendTest {
    private class Client : TranslationNativeClient {
        var closes = 0; var downloads = 0
        val texts = mutableListOf<String>()
        var download: ((Result<Unit>) -> Unit) -> Unit = { }
        var run: (String, (Result<String>) -> Unit) -> Unit = { _, _ -> }
        var onClose: () -> Unit = {}
        override fun downloadOverWifi(done: (Result<Unit>) -> Unit) { downloads++; download(done) }
        override fun translate(text: String, done: (Result<String>) -> Unit) { texts += text; run(text, done) }
        override fun close() { closes++; onClose() }
    }
    @Test fun constructorAndStatusOrDeleteNeverCreateATranslatorOrReceiveSelectedText() {
        var clients = 0; var stores = 0
        val backend = MlKitEnglishTurkishBackend({ stores++; object : TranslationNativeModels {
            override fun ready(done: (Result<Boolean>) -> Unit) = done(Result.success(true))
            override fun deleteTurkish(done: (Result<Unit>) -> Unit) = done(Result.success(Unit))
        } }, { clients++; Client() })
        assertEquals(0, clients); assertEquals(0, stores)
        backend.models { assertEquals(true, it.getOrThrow()) }
        backend.deleteTurkishModel { assertTrue(it.isSuccess) }
        assertEquals(2, stores); assertEquals(0, clients)
    }
    @Test fun nativeDownloadOwnsTheClientUntilCompletionAndClosePrecedesDelivery() {
        val client = Client(); lateinit var complete: (Result<Unit>) -> Unit
        client.download = { complete = it }
        val backend = MlKitEnglishTurkishBackend(clientFactory = { client }); var calls = 0
        backend.downloadOverWifi { assertEquals(1, client.closes); assertTrue(it.isSuccess); calls++ }
        assertEquals(1, client.downloads); assertEquals(0, client.closes); assertEquals(0, calls); assertTrue(client.texts.isEmpty())
        complete(Result.success(Unit)); assertEquals(1, calls); assertEquals(1, client.closes)
        complete(Result.failure(IllegalStateException("late"))); assertEquals(1, calls); assertEquals(1, client.closes)
    }
    @Test fun exactInputAndOutputArePreservedAndNativeTranslationClosesOnlyOnCompletion() {
        val client = Client(); lateinit var complete: (Result<String>) -> Unit
        client.run = { _, done -> complete = done }
        val backend = MlKitEnglishTurkishBackend(clientFactory = { client }); var output: String? = null
        backend.translate("  English 🌿\r\n") { assertEquals(1, client.closes); output = it.getOrThrow() }
        assertEquals(listOf("  English 🌿\r\n"), client.texts); assertEquals(0, client.closes); assertNull(output)
        complete(Result.success("  Türkçe 🌿\r\n")); assertEquals("  Türkçe 🌿\r\n", output); assertEquals(1, client.closes)
        complete(Result.success("stale")); assertEquals("  Türkçe 🌿\r\n", output); assertEquals(1, client.closes)
    }
    @Test fun factoryAndModelStoreFailureAreDeliveredWithoutLeakingOrInventingSuccess() {
        val backend = MlKitEnglishTurkishBackend({ throw IllegalStateException("store") }, { throw IllegalStateException("factory") })
        var failures = 0
        backend.models { assertTrue(it.isFailure); failures++ }
        backend.deleteTurkishModel { assertTrue(it.isFailure); failures++ }
        backend.downloadOverWifi { assertTrue(it.isFailure); failures++ }
        backend.translate("English") { assertTrue(it.isFailure); failures++ }
        assertEquals(4, failures)
    }
    @Test fun synchronousNativeStartFailureClosesTheClientOnce() {
        val client = Client().apply {
            download = { throw IllegalStateException("download start") }
            run = { _, _ -> throw IllegalStateException("translate start") }
        }
        val backend = MlKitEnglishTurkishBackend(clientFactory = { client }); var failures = 0
        backend.downloadOverWifi { assertTrue(it.isFailure); failures++ }
        backend.translate("English") { assertTrue(it.isFailure); failures++ }
        assertEquals(2, client.closes); assertEquals(2, failures)
    }
    @Test fun asynchronousNativeFailureAndCloseFailureCannotBecomeSuccessfulText() {
        val client = Client(); var failures = 0
        client.run = { _, done -> done(Result.failure(IllegalStateException("native task"))) }
        val backend = MlKitEnglishTurkishBackend(clientFactory = { client })
        backend.translate("English") { assertTrue(it.isFailure); failures++ }; assertEquals(1, client.closes)
        client.run = { _, done -> done(Result.success("text")) }; client.onClose = { throw IllegalStateException("close") }
        backend.translate("English") { assertTrue(it.isFailure); failures++ }; assertEquals(2, client.closes); assertEquals(2, failures)
    }
    @Test fun synchronousCallbackFollowedByThrowCannotDoubleCloseOrOverwriteTheResult() {
        val client = Client().apply { run = { _, done -> done(Result.success("Türkçe")); throw IllegalStateException("after callback") } }
        val backend = MlKitEnglishTurkishBackend(clientFactory = { client }); var calls = 0
        backend.translate("English") { assertEquals("Türkçe", it.getOrThrow()); calls++ }
        assertEquals(1, calls); assertEquals(1, client.closes)
    }
}
