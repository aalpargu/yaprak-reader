package com.yaprak.reader.data

import com.yaprak.reader.data.translation.*
import com.yaprak.reader.domain.translation.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LocalEnglishTurkishTranslationTest {
    private class Backend : TranslationBackend {
        var ready = true; var checks = 0; var downloads = 0; var deletes = 0
        val texts = mutableListOf<String>()
        var check: ((Result<Boolean>) -> Unit) -> Unit = { it(Result.success(ready)) }
        var download: ((Result<Unit>) -> Unit) -> Unit = { ready = true; it(Result.success(Unit)) }
        var run: (String, (Result<String>) -> Unit) -> Unit = { _, done -> done(Result.success("  Türkçe 🌿\nexact  ")) }
        var remove: ((Result<Unit>) -> Unit) -> Unit = { ready = false; it(Result.success(Unit)) }
        override fun models(done: (Result<Boolean>) -> Unit) { checks++; check(done) }
        override fun downloadOverWifi(done: (Result<Unit>) -> Unit) { downloads++; download(done) }
        override fun translate(text: String, done: (Result<String>) -> Unit) { texts += text; run(text, done) }
        override fun deleteTurkishModel(done: (Result<Unit>) -> Unit) { deletes++; remove(done) }
    }
    private fun failure(error: Throwable?, reason: TranslationFailure) {
        assertTrue(error is TranslationException); assertEquals(reason, (error as TranslationException).reason)
    }
    @Test fun modelInspectionNeverDownloadsOrTranslatesAndConstructionIsIdle() = runTest {
        val b = Backend(); val s = LocalEnglishTurkishTranslation(b, StandardTestDispatcher(testScheduler))
        assertEquals(0, b.checks); assertEquals(TranslationModels.READY, s.models())
        b.ready = false; assertEquals(TranslationModels.MISSING, s.models())
        assertEquals(2, b.checks); assertEquals(0, b.downloads); assertEquals(0, b.deletes); assertTrue(b.texts.isEmpty())
    }
    @Test fun missingModelCannotReceiveTheSelectedTextOrSilentlyDownload() = runTest {
        val b = Backend().apply { ready = false }; val s = LocalEnglishTurkishTranslation(b, StandardTestDispatcher(testScheduler))
        failure(runCatching { s.translate("private selected sentence") }.exceptionOrNull(), TranslationFailure.MODEL_MISSING)
        assertTrue(b.texts.isEmpty()); assertEquals(0, b.downloads)
    }
    @Test fun validInputAndOutputPreserveLiteralWhitespaceControlsUnicodeAndHtml() = runTest {
        val b = Backend(); val s = LocalEnglishTurkishTranslation(b, StandardTestDispatcher(testScheduler))
        val input = "\t  <literal> English 🌿\r\nquoted \"text\"  "
        assertEquals("  Türkçe 🌿\nexact  ", s.translate(input)); assertEquals(listOf(input), b.texts)
        assertEquals(0, b.downloads); assertEquals(0, b.deletes)
    }
    @Test fun invalidInputHasNoModelCallAndExactFiveThousandUnitsAreAccepted() = runTest {
        val b = Backend(); val s = LocalEnglishTurkishTranslation(b, StandardTestDispatcher(testScheduler))
        for (input in listOf("", " \t\r\n", "a\u0000", "a\uD800", "\uDC00")) {
            failure(runCatching { s.translate(input) }.exceptionOrNull(), TranslationFailure.INVALID_TEXT)
        }
        failure(runCatching { s.translate("a".repeat(5001)) }.exceptionOrNull(), TranslationFailure.LIMIT)
        assertEquals(0, b.checks); assertTrue(b.texts.isEmpty())
        s.translate("a".repeat(5000)); assertEquals(5000, b.texts.single().length)
    }
    @Test fun malformedEmptyOrOverBudgetNativeResultCannotBecomeSuccessfulTruncatedText() = runTest {
        val b = Backend(); val s = LocalEnglishTurkishTranslation(b, StandardTestDispatcher(testScheduler))
        for (output in listOf("", " \t", "x\u0000", "\uD800", "\uDC00")) {
            b.run = { _, done -> done(Result.success(output)) }
            failure(runCatching { s.translate("English") }.exceptionOrNull(), TranslationFailure.TRANSLATE)
        }
        b.run = { _, done -> done(Result.success("a".repeat(5001))) }
        failure(runCatching { s.translate("English") }.exceptionOrNull(), TranslationFailure.LIMIT)
        b.run = { _, done -> done(Result.success("a".repeat(5000))) }; assertEquals(5000, s.translate("English").length)
    }
    @Test fun explicitDownloadDoesNotTranslateAndVerifiesThatModelsActuallyBecameReady() = runTest {
        val b = Backend().apply { ready = false }; val s = LocalEnglishTurkishTranslation(b, StandardTestDispatcher(testScheduler))
        b.download = { it(Result.success(Unit)) }
        failure(runCatching { s.downloadOverWifi() }.exceptionOrNull(), TranslationFailure.DOWNLOAD)
        assertEquals(1, b.downloads); assertTrue(b.texts.isEmpty())
        b.download = { b.ready = true; it(Result.success(Unit)) }; s.downloadOverWifi()
        assertEquals(TranslationModels.READY, s.models()); assertEquals(2, b.downloads); assertTrue(b.texts.isEmpty())
    }
    @Test fun cancelledDownloadWaiterLeavesOneNativeOperationAndReopenReportsDownloadingUntilCompletion() = runTest {
        val b = Backend().apply { ready = false }; val s = LocalEnglishTurkishTranslation(b, StandardTestDispatcher(testScheduler))
        lateinit var complete: (Result<Unit>) -> Unit
        b.download = { complete = it }
        val old = async { s.downloadOverWifi() }; runCurrent(); old.cancel(); runCurrent()
        assertTrue(runCatching { old.await() }.exceptionOrNull() is CancellationException)
        assertEquals(TranslationModels.DOWNLOADING, s.models()); assertEquals(0, b.checks)
        failure(runCatching { s.downloadOverWifi() }.exceptionOrNull(), TranslationFailure.BUSY)
        failure(runCatching { s.translate("English") }.exceptionOrNull(), TranslationFailure.BUSY)
        failure(runCatching { s.deleteTurkishModel() }.exceptionOrNull(), TranslationFailure.BUSY)
        assertEquals(1, b.downloads); b.ready = true; complete(Result.success(Unit)); runCurrent()
        assertEquals(TranslationModels.READY, s.models()); assertNotNull(s.translate("new English"))
    }
    @Test fun cancelledTranslationCannotReleaseANativeClientLeaseBeforeItsCompletion() = runTest {
        val b = Backend(); val s = LocalEnglishTurkishTranslation(b, StandardTestDispatcher(testScheduler))
        lateinit var complete: (Result<String>) -> Unit; b.run = { _, done -> complete = done }
        val old = async { s.translate("old") }; runCurrent(); old.cancel(); runCurrent()
        assertTrue(runCatching { old.await() }.exceptionOrNull() is CancellationException)
        failure(runCatching { s.translate("new") }.exceptionOrNull(), TranslationFailure.BUSY)
        failure(runCatching { s.models() }.exceptionOrNull(), TranslationFailure.BUSY)
        complete(Result.success("late")); runCurrent()
        assertTrue(runCatching { old.await() }.exceptionOrNull() is CancellationException)
        b.run = { _, done -> done(Result.success("new Turkish")) }; assertEquals("new Turkish", s.translate("new"))
    }
    @Test fun staleDuplicateNativeCallbackCannotReleaseOrCompleteAnotherOperation() = runTest {
        val b = Backend(); val s = LocalEnglishTurkishTranslation(b, StandardTestDispatcher(testScheduler))
        val callbacks = mutableListOf<(Result<String>) -> Unit>(); b.run = { _, done -> callbacks += done }
        val first = async { s.translate("one") }; runCurrent(); callbacks[0](Result.success("bir")); runCurrent()
        assertEquals("bir", first.await())
        val next = async { s.translate("two") }; runCurrent(); callbacks[0](Result.success("stale")); runCurrent()
        assertFalse(next.isCompleted); failure(runCatching { s.models() }.exceptionOrNull(), TranslationFailure.BUSY)
        callbacks[1](Result.success("iki")); runCurrent(); assertEquals("iki", next.await())
    }
    @Test fun duplicateModelStatusCallbackCannotStartTwoNativeTranslations() = runTest {
        val b = Backend(); val s = LocalEnglishTurkishTranslation(b, StandardTestDispatcher(testScheduler))
        b.check = { done -> done(Result.success(true)); done(Result.success(true)) }
        assertNotNull(s.translate("English")); assertEquals(listOf("English"), b.texts)
    }
    @Test fun synchronousAndAsynchronousNativeFailuresReleaseTheLeaseForAnExplicitRetry() = runTest {
        val b = Backend(); val s = LocalEnglishTurkishTranslation(b, StandardTestDispatcher(testScheduler))
        b.check = { throw IllegalStateException("status") }
        failure(runCatching { s.models() }.exceptionOrNull(), TranslationFailure.MODELS)
        b.check = { it(Result.failure(IllegalStateException("status"))) }
        failure(runCatching { s.translate("English") }.exceptionOrNull(), TranslationFailure.MODELS)
        b.check = { it(Result.success(true)) }; b.run = { _, _ -> throw IllegalStateException("process") }
        failure(runCatching { s.translate("English") }.exceptionOrNull(), TranslationFailure.TRANSLATE)
        b.run = { _, done -> done(Result.failure(IllegalStateException("task"))) }
        failure(runCatching { s.translate("English") }.exceptionOrNull(), TranslationFailure.TRANSLATE)
        b.run = { _, done -> done(Result.success("retry")) }; assertEquals("retry", s.translate("English"))
    }
    @Test fun modelDeletionIsExplicitAndItsActualAbsenceMustBeVerified() = runTest {
        val b = Backend(); val s = LocalEnglishTurkishTranslation(b, StandardTestDispatcher(testScheduler))
        b.remove = { it(Result.success(Unit)) }
        failure(runCatching { s.deleteTurkishModel() }.exceptionOrNull(), TranslationFailure.DELETE)
        b.remove = { b.ready = false; it(Result.success(Unit)) }; s.deleteTurkishModel()
        assertEquals(2, b.deletes); assertEquals(TranslationModels.MISSING, s.models())
        failure(runCatching { s.translate("English") }.exceptionOrNull(), TranslationFailure.MODEL_MISSING)
        assertTrue(b.texts.isEmpty()); assertEquals(0, b.downloads)
    }
    @Test fun cancellationBeforeQueuedRegistrationDoesNotStartOrPermanentlyLockTheBackend() = runTest {
        val b = Backend(); val s = LocalEnglishTurkishTranslation(b, StandardTestDispatcher(testScheduler))
        val request = async { s.translate("English") }; request.cancel(); runCurrent()
        assertTrue(runCatching { request.await() }.exceptionOrNull() is CancellationException)
        assertEquals(0, b.checks); assertTrue(b.texts.isEmpty())
        assertNotNull(s.translate("retry"))
    }
    @Test fun cancellationAfterLeaseAdmissionButBeforeIoRegistrationReleasesWithoutStartingNativeWork() = runTest {
        val queued = java.util.ArrayDeque<Runnable>()
        val io = object : CoroutineDispatcher() {
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) { queued.add(block) }
        }
        val b = Backend(); val service = LocalEnglishTurkishTranslation(b, io)
        val old = async { service.translate("private English") }; runCurrent()
        assertEquals(1, queued.size); assertEquals(0, b.checks)
        old.cancel(); queued.removeFirst().run(); runCurrent()
        assertTrue(runCatching { old.await() }.exceptionOrNull() is CancellationException)
        assertEquals(0, b.checks); assertTrue(b.texts.isEmpty())
        val fresh = async { service.models() }; runCurrent(); queued.removeFirst().run(); runCurrent()
        assertEquals(TranslationModels.READY, fresh.await()); assertEquals(1, b.checks)
    }
}
