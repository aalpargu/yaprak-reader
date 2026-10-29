package com.yaprak.reader.feature

import androidx.lifecycle.ViewModelStore
import com.yaprak.reader.domain.translation.*
import com.yaprak.reader.feature.translation.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TranslationViewModelTest {
    private class Service : EnglishTurkishTranslation {
        var checks = 0; var downloads = 0; var deletes = 0
        val texts = mutableListOf<String>()
        var status: suspend () -> TranslationModels = { TranslationModels.READY }
        var download: suspend () -> Unit = {}
        var run: suspend (String) -> String = { "Türkçe 🌿" }
        var remove: suspend () -> Unit = {}
        override suspend fun models(): TranslationModels { checks++; return status() }
        override suspend fun downloadOverWifi() { downloads++; download() }
        override suspend fun translate(text: String): String { texts += text; return run(text) }
        override suspend fun deleteTurkishModel() { deletes++; remove() }
    }
    private suspend fun TestScope.withModel(service: Service = Service(), block: suspend TestScope.(TranslationViewModel, Service, ViewModelStore) -> Unit) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        val model = TranslationViewModel(service).also { store.put("translation", it) }
        try { block(model, service, store) }
        finally { store.clear(); runCurrent(); Dispatchers.resetMain() }
    }
    @Test fun openOnlyChecksAvailabilityAndTranslationIsASeparateExplicitAction() = runTest {
        withModel { model, service, _ ->
            runCurrent(); assertNull(model.state.value); assertEquals(0, service.checks)
            assertTrue(model.open("selection-one", "  English 🌿\r\n") { true })
            assertTrue(model.state.value!!.working); runCurrent()
            val ready = model.state.value!!
            assertEquals(TranslationPhase.READY, ready.phase); assertTrue(ready.modelsReady)
            assertEquals(1, service.checks); assertEquals(0, service.downloads); assertTrue(service.texts.isEmpty())
            assertFalse(model.open("selection-one", ready.sourceText) { true })
            assertTrue(model.translate(ready)); assertFalse(model.translate(ready)); runCurrent()
            assertEquals(listOf("  English 🌿\r\n"), service.texts)
            assertEquals("Türkçe 🌿", model.state.value!!.translatedText); assertEquals(0, service.downloads)
        }
    }
    @Test fun missingModelCannotTranslateAndDownloadDoesNotTranslateOrAutoCopy() = runTest {
        withModel(Service().apply { status = { TranslationModels.MISSING } }) { model, service, _ ->
            model.open("selection", "English") { true }; runCurrent()
            val missing = model.state.value!!
            assertEquals(TranslationPhase.MISSING, missing.phase); assertFalse(model.translate(missing))
            assertTrue(model.download(missing)); assertFalse(model.download(missing)); runCurrent()
            val ready = model.state.value!!
            assertTrue(ready.modelsReady); assertEquals(1, service.downloads)
            assertTrue(service.texts.isEmpty()); assertNull(ready.translatedText)
            assertFalse(model.download(ready)); assertEquals(TranslationCopyStatus.NONE, ready.copyStatus)
        }
    }
    @Test fun invalidSourceAndInvalidOwnerNeverCallTheServiceOrInventAResult() = runTest {
        withModel { model, service, _ ->
            for (text in listOf("", " ", "x\u0000", "\uD800", "a".repeat(5001))) {
                assertFalse(model.open("selection", text) { true })
            }
            assertFalse(model.open("", "English") { true }); assertFalse(model.open("selection", "English") { false })
            runCurrent(); assertEquals(0, service.checks); assertTrue(service.texts.isEmpty()); assertNull(model.state.value)
        }
    }
    @Test fun stateAndFailuresDoNotPrintBookInputOrTranslatedOutput() = runTest {
        withModel { model, _, _ ->
            model.open("selection", "private English paragraph") { true }; runCurrent()
            model.translate(model.state.value!!); runCurrent()
            val state = model.state.value!!
            assertFalse(state.toString().contains(state.sourceText)); assertFalse(state.toString().contains(state.translatedText!!))
            assertEquals("TRANSLATE", TranslationException(TranslationFailure.TRANSLATE).message)
        }
    }
    @Test fun closeReplacementAndCancellationIgnoringLateSuccessCannotPublishIntoNewSelection() = runTest {
        val old = CompletableDeferred<String>()
        withModel(Service().apply { run = { withContext(NonCancellable) { old.await() } } }) { model, service, _ ->
            model.open("same-selection", "old") { true }; runCurrent(); model.translate(model.state.value!!); runCurrent()
            model.close(); assertNull(model.state.value)
            model.open("same-selection", "new") { true }; runCurrent()
            val current = model.state.value!!
            old.complete("late old result"); runCurrent()
            assertSame(current, model.state.value); assertEquals("new", current.sourceText)
            assertEquals(listOf("old"), service.texts); assertNull(current.translatedText)
        }
    }
    @Test fun lateFailureAfterCloseCannotReopenThePanel() = runTest {
        val pending = CompletableDeferred<Unit>()
        withModel(Service().apply { download = { withContext(NonCancellable) { pending.await(); throw TranslationException(TranslationFailure.DOWNLOAD) } }
            status = { TranslationModels.MISSING } }) { model, _, _ ->
            model.open("selection", "English") { true }; runCurrent(); model.download(model.state.value!!); runCurrent()
            model.close(); pending.complete(Unit); runCurrent(); assertNull(model.state.value)
        }
    }
    @Test fun clearingStoreRevokesTheGuardAndDropsPendingWork() = runTest {
        val pending = CompletableDeferred<String>()
        withModel(Service().apply { run = { withContext(NonCancellable) { pending.await() } } }) { model, _, store ->
            model.open("selection", "English") { true }; runCurrent(); model.translate(model.state.value!!); runCurrent()
            store.clear(); assertNull(model.state.value)
            pending.complete("late result"); runCurrent(); assertNull(model.state.value)
        }
    }
    @Test fun sourceRevisionLossWhileWaitingDropsResultAndAllOldActions() = runTest {
        val pending = CompletableDeferred<String>(); var owner = true
        withModel(Service().apply { run = { pending.await() } }) { model, _, _ ->
            model.open("selection", "English") { owner }; runCurrent(); val old = model.state.value!!
            model.translate(old); runCurrent(); owner = false; pending.complete("Türkçe"); runCurrent()
            assertNull(model.state.value); assertFalse(model.translate(old)); assertFalse(model.checkModels(old))
            assertFalse(model.copy(old) { fail("obsolete copy") }); assertFalse(model.deleteModel(old))
        }
    }
    @Test fun forgedEqualStateAndPreviousAttemptCannotTriggerServiceOrClipboard() = runTest {
        withModel { model, service, _ ->
            model.open("selection", "English") { true }; runCurrent(); val ready = model.state.value!!
            assertFalse(model.translate(ready.copy())); assertFalse(model.download(ready.copy()))
            assertFalse(model.checkModels(ready.copy())); assertFalse(model.deleteModel(ready.copy()))
            model.translate(ready); runCurrent(); val translated = model.state.value!!
            assertFalse(model.copy(translated.copy()) { fail("forged copy") })
            assertFalse(model.copy(ready) { fail("old attempt") }); assertEquals(1, service.texts.size)
        }
    }
    @Test fun exactCopyHasExplicitSuccessFailureAndCancellationWithoutChangingSource() = runTest {
        val exact = "  İ/ı e\u0301 🌿 <b>literal</b>\r\n"
        withModel(Service().apply { run = { exact } }) { model, _, _ ->
            model.open("selection", "English") { true }; runCurrent()
            assertFalse(model.copy(model.state.value!!) { fail("not translated") })
            model.translate(model.state.value!!); runCurrent()
            val before = model.state.value!!; val copies = mutableListOf<String>()
            assertTrue(model.copy(before, copies::add)); assertEquals(listOf(exact), copies)
            assertEquals(TranslationCopyStatus.COPIED, model.state.value!!.copyStatus)
            assertTrue(model.copy(model.state.value!!) { throw IllegalStateException("clipboard unavailable") })
            assertEquals(TranslationCopyStatus.FAILED, model.state.value!!.copyStatus)
            val failed = model.state.value!!
            try { model.copy(failed) { throw CancellationException("stop") }; fail("cancellation must propagate") }
            catch (_: CancellationException) {}
            assertSame(failed, model.state.value); assertEquals("English", failed.sourceText)
        }
    }
    @Test fun malformedOrOversizedTranslationIsNeverTruncatedAndExplicitRetryGetsFreshAttempt() = runTest {
        for ((output, failure) in listOf("" to TranslationFailure.TRANSLATE, "x\u0000" to TranslationFailure.TRANSLATE,
            "\uD800" to TranslationFailure.TRANSLATE, "t".repeat(5001) to TranslationFailure.LIMIT)) {
            withModel(Service().apply { run = { output } }) { model, service, _ ->
                model.open("selection", "English") { true }; runCurrent(); model.translate(model.state.value!!); runCurrent()
                val failed = model.state.value!!
                assertEquals(failure, failed.failure); assertNull(failed.translatedText)
                service.run = { "t".repeat(5000) }; assertTrue(model.translate(failed)); runCurrent()
                assertNotEquals(failed.attemptId, model.state.value!!.attemptId)
                assertEquals(5000, model.state.value!!.translatedText!!.length)
            }
        }
    }
    @Test fun modelRemovedBetweenCheckAndTranslationRequiresDownloadRatherThanInventingText() = runTest {
        withModel(Service().apply { run = { throw TranslationException(TranslationFailure.MODEL_MISSING) } }) { model, service, _ ->
            model.open("selection", "English") { true }; runCurrent(); model.translate(model.state.value!!); runCurrent()
            val missing = model.state.value!!
            assertEquals(TranslationPhase.MISSING, missing.phase); assertFalse(missing.modelsReady)
            assertFalse(model.translate(missing)); assertNull(missing.translatedText); assertEquals(0, service.downloads)
        }
    }
    @Test fun ongoingNativeDownloadHasExplicitStatusRecheckAndDeletionClearsTextAndCopy() = runTest {
        withModel(Service().apply { status = { TranslationModels.DOWNLOADING } }) { model, service, _ ->
            model.open("selection", "English") { true }; runCurrent(); val pending = model.state.value!!
            assertEquals(TranslationPhase.DOWNLOADING, pending.phase); assertFalse(pending.working)
            assertFalse(model.download(pending)); assertFalse(model.translate(pending))
            service.status = { TranslationModels.READY }; model.checkModels(pending); runCurrent()
            model.translate(model.state.value!!); runCurrent(); model.copy(model.state.value!!) {}
            val output = model.state.value!!; assertTrue(model.deleteModel(output)); runCurrent()
            val missing = model.state.value!!
            assertEquals(TranslationPhase.MISSING, missing.phase); assertNull(missing.translatedText)
            assertEquals(TranslationCopyStatus.NONE, missing.copyStatus); assertEquals(1, service.deletes)
            assertFalse(model.copy(output) { fail("deleted output") })
        }
    }
    @Test fun unexpectedFailuresKeepTheActualOperationAndRequireAnExplicitStatusRecheck() = runTest {
        withModel(Service().apply { status = { throw IllegalStateException("check") } }) { model, service, _ ->
            model.open("selection", "English") { true }; runCurrent()
            assertEquals(TranslationFailure.MODELS, model.state.value!!.failure); assertFalse(model.state.value!!.modelsReady)
            service.status = { TranslationModels.MISSING }; model.checkModels(model.state.value!!); runCurrent()
            service.download = { throw IllegalStateException("download") }; model.download(model.state.value!!); runCurrent()
            assertEquals(TranslationFailure.DOWNLOAD, model.state.value!!.failure)
            service.status = { TranslationModels.READY }; model.checkModels(model.state.value!!); runCurrent()
            service.remove = { throw IllegalStateException("delete") }; model.deleteModel(model.state.value!!); runCurrent()
            assertEquals(TranslationFailure.DELETE, model.state.value!!.failure); assertFalse(model.state.value!!.modelsReady)
        }
    }
}
