package com.yaprak.reader.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yaprak.reader.YaprakApplication
import com.yaprak.reader.domain.translation.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Native SDK cases. Model download requires an explicit instrumentation argument, never a silent test side effect. */
@RunWith(AndroidJUnit4::class)
class EnglishTurkishTranslationDeviceTest {
    private val service get() = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as YaprakApplication)
        .container.englishTurkishTranslation
    @Test fun modelAvailabilityInspectionDoesNotStartADownload() = runBlocking {
        val first = withTimeout(30000) { service.models() }
        assertTrue(first == TranslationModels.MISSING || first == TranslationModels.READY)
        assertEquals(first, withTimeout(30000) { service.models() })
        if (first == TranslationModels.MISSING) {
            try { service.translate("Hello world."); fail("Missing model must not silently download or invent output") }
            catch (error: TranslationException) { assertEquals(TranslationFailure.MODEL_MISSING, error.reason) }
            assertEquals(TranslationModels.MISSING, service.models())
        }
    }
    @Test fun preinstalledModelCanTranslateWithoutAnImplicitDownload() = runBlocking {
        assumeTrue("Install the model explicitly first; run also with network disabled.", service.models() == TranslationModels.READY)
        val output = withTimeout(60000) { service.translate("Hello world.") }
        validateTranslationText(output); assertNotEquals("Hello world.", output)
        assertTrue(output.lowercase(java.util.Locale.ROOT).contains("merhaba") || output.lowercase(java.util.Locale.ROOT).contains("selam"))
    }
    @Test fun explicitlyEnabledWifiModelDownloadProducesAUsableLocalModel() = runBlocking {
        assumeTrue("Opt in using -e translationDownloadOverWifi true; this may download roughly 30 MB.",
            InstrumentationRegistry.getArguments().getString("translationDownloadOverWifi") == "true")
        withTimeout(300000) { service.downloadOverWifi() }
        assertEquals(TranslationModels.READY, service.models())
        validateTranslationText(withTimeout(60000) { service.translate("This book has a green cover.") })
    }
}
