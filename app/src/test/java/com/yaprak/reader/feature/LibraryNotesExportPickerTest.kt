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
import androidx.compose.runtime.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.yaprak.reader.domain.library.*
import com.yaprak.reader.domain.notes.*
import com.yaprak.reader.feature.librarynotes.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LibraryNotesExportPickerTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private class Registry(val context: Context) : ActivityResultRegistry() {
        data class Launch(val code: Int, val intent: Intent)
        val launches = mutableListOf<Launch>(); var fail = false
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
            launches += Launch(requestCode, contract.createIntent(context, input))
            if (fail) throw IllegalStateException("No provider")
        }
    }
    private class Exporter : LibraryNotesExporter {
        val calls = mutableListOf<Triple<String, String, LibraryNotesExportFormat>>()
        override suspend fun export(query: String, destinationUri: String, format: LibraryNotesExportFormat): StudyExportResult {
            calls += Triple(query, destinationUri, format); return StudyExportResult.Exported(230, 0, 0)
        }
    }

    @Test fun eachExplicitFormatDefersLaunchUntilResumedKeepsRegistrationDuringPauseAndUsesOneLocalSafResult() {
        val registry = Registry(ApplicationProvider.getApplicationContext())
        val owner = object : ActivityResultRegistryOwner { override val activityResultRegistry = registry }
        val exporter = Exporter(); val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val controller = LibraryNotesExportController(exporter, SavedStateHandle(), scope)
        var mayLaunch by mutableStateOf(false)
        try {
            compose.setContent { CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                val request by controller.request.collectAsState()
                request?.let { key(it.requestId) { LibraryNotesExportPicker(it, mayLaunch, controller::claimLaunch,
                    controller::onResult, controller::onLaunchFailed) } }
            } }
            LibraryNotesExportFormat.entries.forEachIndexed { index, format ->
                compose.runOnIdle { mayLaunch = false; controller.prepare("İstanbul $index", format) }
                compose.waitForIdle(); assertEquals(index, registry.launches.size)
                compose.runOnIdle { mayLaunch = true }; compose.waitForIdle()
                val launch = registry.launches.last()
                assertEquals(index + 1, registry.launches.size); assertEquals(Intent.ACTION_CREATE_DOCUMENT, launch.intent.action)
                assertEquals(format.mimeType, launch.intent.type)
                assertEquals(if (format == LibraryNotesExportFormat.OBSIDIAN_ZIP) "Yaprak-obsidian-notlari.zip"
                    else "Yaprak-kutuphane-notlari.${format.extension}", launch.intent.getStringExtra(Intent.EXTRA_TITLE))
                assertTrue(launch.intent.getBooleanExtra(Intent.EXTRA_LOCAL_ONLY, false)); assertTrue(launch.intent.categories.contains(Intent.CATEGORY_OPENABLE))
                compose.runOnIdle { mayLaunch = false }; compose.waitForIdle()
                compose.runOnIdle { registry.dispatchResult(launch.code, Activity.RESULT_OK, Intent().setData(Uri.parse("content://notes/$index"))) }
                compose.waitForIdle(); assertNull(controller.request.value)
                assertEquals(Triple("İstanbul $index", "content://notes/$index", format), exporter.calls.last())
            }
            registry.fail = true
            compose.runOnIdle { mayLaunch = true; controller.prepare("", LibraryNotesExportFormat.TEXT) }
            compose.waitForIdle(); assertNull(controller.request.value)
            assertEquals(StudyExportResult.Failed(StudyExportFailure.ACCESS), controller.notice.value?.result)
            assertEquals(LibraryNotesExportFormat.entries.size, exporter.calls.size)
        } finally { scope.cancel() }
    }

    @Test fun actualRegistryAndCanonicalReservationSurviveBundleParcelWithSameQueryFormatAndNoSecondLaunch() =
        restoreReservation(LibraryNotesExportFormat.MARKDOWN)

    @Test fun notionCsvRegistryAndNamedReservationRestoreTheExactQueryOnceWithoutASecondLaunch() =
        restoreReservation(LibraryNotesExportFormat.NOTION_CSV)

    private fun restoreReservation(format: LibraryNotesExportFormat) {
        var registry by mutableStateOf(Registry(ApplicationProvider.getApplicationContext()))
        val owner = object : ActivityResultRegistryOwner { override val activityResultRegistry get() = registry }
        val exporter = Exporter(); val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        var saved = SavedStateHandle()
        var controller by mutableStateOf(LibraryNotesExportController(exporter, saved, scope))
        val restoration = StateRestorationTester(compose)
        try {
            val request = compose.runOnIdle { requireNotNull(controller.prepare("İstanbul 🌿", format)) }
            restoration.setContent { CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                val pending by controller.request.collectAsState()
                pending?.let { key(it.requestId) { LibraryNotesExportPicker(it, true, controller::claimLaunch,
                    controller::onResult, controller::onLaunchFailed) } }
            } }
            compose.waitForIdle(); val originalRegistry = registry; val launch = originalRegistry.launches.single()
            compose.runOnIdle {
                val bundle = Bundle(); originalRegistry.onSaveInstanceState(bundle)
                bundle.putStringArrayList("reservation", saved["libraryNotesExportReservationV1"])
                val parcel = Parcel.obtain()
                val decoded = try { bundle.writeToParcel(parcel, 0); parcel.setDataPosition(0); Bundle.CREATOR.createFromParcel(parcel) }
                    finally { parcel.recycle() }
                saved = SavedStateHandle(mapOf("libraryNotesExportReservationV1" to decoded.getStringArrayList("reservation")))
                registry = Registry(ApplicationProvider.getApplicationContext()).also { it.onRestoreInstanceState(decoded) }
                controller = LibraryNotesExportController(exporter, saved, scope)
            }
            restoration.emulateSavedInstanceStateRestore(); compose.waitForIdle()
            assertEquals(request, controller.request.value); assertTrue(registry.launches.isEmpty())
            assertEquals(1, originalRegistry.launches.size)
            compose.runOnIdle { registry.dispatchResult(launch.code, Activity.RESULT_OK, Intent().setData(Uri.parse("content://restored-notes"))) }
            compose.waitForIdle()
            assertEquals(listOf(Triple(request.query, "content://restored-notes", request.format)), exporter.calls)
            assertNull(controller.request.value)
            compose.runOnIdle { registry.dispatchResult(launch.code, Activity.RESULT_OK, Intent().setData(Uri.parse("content://duplicate"))) }
            compose.waitForIdle(); assertEquals(1, exporter.calls.size)
        } finally { scope.cancel() }
    }
}
