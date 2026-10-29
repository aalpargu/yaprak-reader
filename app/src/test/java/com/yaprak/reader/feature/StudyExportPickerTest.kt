package com.yaprak.reader.feature

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Parcel
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.domain.library.BookStudyExporter
import com.yaprak.reader.domain.library.StudyExportFormat
import com.yaprak.reader.domain.library.StudyExportResult
import com.yaprak.reader.feature.library.StudyExportController
import com.yaprak.reader.feature.library.StudyExportPicker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Real registry state/mappings and Compose saveable launcher keys, without a physical provider. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StudyExportPickerTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val book = Book("book", "İstanbul", "Deniz", BookFormat.EPUB, "İstanbul.epub", "book.epub", 1, "hash", 1)
    private class Exporter : BookStudyExporter {
        data class Call(val id: String, val hash: String, val uri: String, val format: StudyExportFormat)
        val calls = mutableListOf<Call>()
        override suspend fun export(bookId: String, contentSha256: String, destinationUri: String) =
            export(bookId, contentSha256, destinationUri, StudyExportFormat.TEXT)
        override suspend fun export(bookId: String, contentSha256: String, destinationUri: String,
            format: StudyExportFormat): StudyExportResult {
            calls += Call(bookId, contentSha256, destinationUri, format)
            return StudyExportResult.Exported(0, 1, 0)
        }
    }
    private class Registry(private val context: Context, val failLaunch: Boolean = false) : ActivityResultRegistry() {
        data class Launch(val requestCode: Int, val intent: Intent)
        val launches = mutableListOf<Launch>()
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I,
            options: ActivityOptionsCompat?) {
            launches += Launch(requestCode, contract.createIntent(context, input))
            if (failLaunch) throw IllegalStateException("No document provider")
        }
    }

    @Test fun `each format uses its SAF MIME and suffix and only one launch through recomposition`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val registry = Registry(context)
        val owner = object : ActivityResultRegistryOwner { override val activityResultRegistry = registry }
        val exporter = Exporter()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val controller = StudyExportController(exporter, SavedStateHandle(), scope)
        try {
            compose.setContent { CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                val request by controller.request.collectAsState()
                request?.let { key(it.requestId) {
                    StudyExportPicker(it, controller::claimLaunch, controller::onDocumentResult, controller::onLaunchFailed)
                } }
            } }
            StudyExportFormat.entries.forEachIndexed { index, format ->
                val request = compose.runOnIdle { requireNotNull(controller.prepare(book, format)) }
                compose.waitForIdle()
                assertEquals(index + 1, registry.launches.size)
                val launched = registry.launches.last()
                assertEquals(Intent.ACTION_CREATE_DOCUMENT, launched.intent.action)
                assertEquals(format.mimeType, launched.intent.type)
                assertEquals(request.suggestedFileName, launched.intent.getStringExtra(Intent.EXTRA_TITLE))
                assertTrue(request.suggestedFileName.endsWith(".${format.extension}"))
                assertTrue(launched.intent.getBooleanExtra(Intent.EXTRA_LOCAL_ONLY, false))
                assertTrue(launched.intent.categories.contains(Intent.CATEGORY_OPENABLE))
                compose.waitForIdle()
                assertEquals(index + 1, registry.launches.size)
                compose.runOnIdle { registry.dispatchResult(launched.requestCode, Activity.RESULT_CANCELED, null) }
                compose.waitForIdle()
                assertNull(controller.request.value)
            }
            assertTrue(exporter.calls.isEmpty())
        } finally { scope.cancel() }
    }

    @Test fun `registry Bundle and Compose launcher key restore original result without second native launch`() =
        assertRestoredReservation(StudyExportFormat.MARKDOWN, copySavedState = false)

    @Test fun `PDF registry and canonical reservation survive actual Bundle parcel with original UUID and no second launch`() =
        assertRestoredReservation(StudyExportFormat.PDF, copySavedState = true)

    @Test fun obsidianZipRegistryAndCanonicalReservationRestoreOriginalBookIdentityWithoutASecondLaunch() =
        assertRestoredReservation(StudyExportFormat.OBSIDIAN_ZIP, copySavedState = true)

    private fun assertRestoredReservation(format: StudyExportFormat, copySavedState: Boolean) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        var registry by mutableStateOf(Registry(context))
        val owner = object : ActivityResultRegistryOwner { override val activityResultRegistry get() = registry }
        var saved = SavedStateHandle()
        val exporter = Exporter()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        var controller by mutableStateOf(StudyExportController(exporter, saved, scope))
        val restoration = StateRestorationTester(compose)
        try {
            val request = compose.runOnIdle { requireNotNull(controller.prepare(book, format)) }
            restoration.setContent { CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                val pending by controller.request.collectAsState()
                pending?.let { key(it.requestId) {
                    StudyExportPicker(it, controller::claimLaunch, controller::onDocumentResult, controller::onLaunchFailed)
                } }
            } }
            compose.waitForIdle()
            val originalRegistry = registry
            val original = originalRegistry.launches.single()
            assertEquals(format.mimeType, original.intent.type)
            assertTrue(request.suggestedFileName.endsWith(".${format.extension}"))
            val bundle = Bundle()
            compose.runOnIdle {
                originalRegistry.onSaveInstanceState(bundle)
                val restored = if (copySavedState) {
                    bundle.putStringArrayList("pendingStudyExportReservation", saved["pendingStudyExportReservation"])
                    bundle.putString("pendingStudyExportBookId", saved["pendingStudyExportBookId"])
                    bundle.putString("pendingStudyExportContentSha256", saved["pendingStudyExportContentSha256"])
                    val parcel = Parcel.obtain()
                    try {
                        bundle.writeToParcel(parcel, 0); parcel.setDataPosition(0)
                        Bundle.CREATOR.createFromParcel(parcel).also { decoded ->
                            saved = SavedStateHandle(mapOf(
                                "pendingStudyExportReservation" to decoded.getStringArrayList("pendingStudyExportReservation"),
                                "pendingStudyExportBookId" to decoded.getString("pendingStudyExportBookId"),
                                "pendingStudyExportContentSha256" to decoded.getString("pendingStudyExportContentSha256")))
                        }
                    } finally { parcel.recycle() }
                } else bundle
                registry = Registry(context).also { it.onRestoreInstanceState(restored) }
                controller = StudyExportController(exporter, saved, scope)
            }
            restoration.emulateSavedInstanceStateRestore()
            compose.waitForIdle()
            assertEquals(request, controller.request.value)
            assertEquals(1, originalRegistry.launches.size)
            assertTrue(registry.launches.isEmpty())
            // This request code was allocated before both saved states were restored. A fresh
            // launcher key would not receive it, so the actual callback delivery proves key retention.
            val destination = "content://restored-${format.extension}"
            val accepted = compose.runOnIdle { registry.dispatchResult(original.requestCode, Activity.RESULT_OK,
                Intent().setData(Uri.parse(destination))) }
            assertTrue(accepted)
            compose.waitForIdle()
            assertEquals(listOf(Exporter.Call(book.id, book.sha256, destination, request.format)), exporter.calls)
            assertNull(controller.request.value)
            assertTrue(registry.launches.isEmpty())
        } finally { scope.cancel() }
    }

    @Test fun `delayed same format registry result keeps newer reservation and its own target separate`() =
        assertDelayedReservation(StudyExportFormat.TEXT)

    @Test fun `delayed old PDF request code cannot consume newer PDF book and content reservation`() =
        assertDelayedReservation(StudyExportFormat.PDF)

    private fun assertDelayedReservation(format: StudyExportFormat) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val registry = Registry(context)
        val owner = object : ActivityResultRegistryOwner { override val activityResultRegistry = registry }
        val exporter = Exporter()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val controller = StudyExportController(exporter, SavedStateHandle(), scope)
        try {
            compose.setContent { CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                val request by controller.request.collectAsState()
                request?.let { key(it.requestId) {
                    StudyExportPicker(it, controller::claimLaunch, controller::onDocumentResult, controller::onLaunchFailed)
                } }
            } }
            val first = compose.runOnIdle { requireNotNull(controller.prepare(book, format)) }
            compose.waitForIdle()
            val oldCode = registry.launches.single().requestCode
            compose.runOnIdle { controller.onDocumentResult(first.requestId, first.format, null) }
            compose.waitForIdle()
            val second = compose.runOnIdle { requireNotNull(controller.prepare(book.copy(id = "new-book", sha256 = "new-hash"), format)) }
            compose.waitForIdle()
            val newCode = registry.launches.last().requestCode
            assertEquals(2, registry.launches.size)
            assertNotEquals(oldCode, newCode)
            compose.runOnIdle { registry.dispatchResult(oldCode, Activity.RESULT_OK,
                Intent().setData(Uri.parse("content://late-old-target"))) }
            compose.waitForIdle()
            assertEquals(second, controller.request.value)
            assertTrue(exporter.calls.isEmpty())
            compose.runOnIdle { registry.dispatchResult(newCode, Activity.RESULT_OK,
                Intent().setData(Uri.parse("content://new-target"))) }
            compose.waitForIdle()
            assertEquals(listOf(Exporter.Call("new-book", "new-hash", "content://new-target", format)), exporter.calls)
        } finally { scope.cancel() }
    }

    @Test fun `native launch failure releases only claimed request with retryable access feedback`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val registry = Registry(context, failLaunch = true)
        val owner = object : ActivityResultRegistryOwner { override val activityResultRegistry = registry }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val controller = StudyExportController(Exporter(), SavedStateHandle(), scope)
        try {
            compose.runOnIdle { controller.prepare(book, StudyExportFormat.ANKI_TSV) }
            compose.setContent { CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                val request by controller.request.collectAsState()
                request?.let { key(it.requestId) {
                    StudyExportPicker(it, controller::claimLaunch, controller::onDocumentResult, controller::onLaunchFailed)
                } }
            } }
            compose.waitForIdle()
            assertEquals(1, registry.launches.size)
            assertNull(controller.request.value)
            assertFalse(controller.busy)
            assertEquals(com.yaprak.reader.domain.library.StudyExportFailure.ACCESS,
                (controller.notice.value!!.result as StudyExportResult.Failed).reason)
        } finally { scope.cancel() }
    }
}
