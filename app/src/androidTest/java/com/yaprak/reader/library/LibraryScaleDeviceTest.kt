package com.yaprak.reader.library

import android.graphics.Bitmap
import android.os.Debug
import android.os.SystemClock
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yaprak.reader.MainActivity
import com.yaprak.reader.R
import com.yaprak.reader.YaprakApplication
import com.yaprak.reader.domain.library.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Real Room + MainActivity. TXT fixtures use real files and placeholder covers, not decoded artwork. */
@RunWith(AndroidJUnit4::class)
class LibraryScaleDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val container get() = (context.applicationContext as YaprakApplication).container

    @Test fun metadataQueryAndFirstUsableFrameAt1005002000Books() = runBlocking {
        withTimeout(30_000) { container.startup.awaitReady() }
        val initialCount = container.library.observeBooks().first().size
        val batch = "Scale-${UUID.randomUUID()}"
        val owned = mutableListOf<Book>()
        val results = JSONArray()
        val folder = File(context.getExternalFilesDir(null), "yaprak-foundation-scale").apply { mkdirs() }
        var succeeded = false
        try {
            for (count in listOf(100, 500, 2000)) {
                withContext(Dispatchers.IO) {
                    for (i in owned.size + 1..count) {
                        val id = UUID.randomUUID().toString()
                        val file = File(context.filesDir, "books/$id.txt").apply {
                            parentFile!!.mkdirs(); writeText("Library scale fixture $batch item $i.\n", Charsets.UTF_8)
                        }
                        val hash = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
                        val book = Book(id, "$batch-$i", "Test Author", BookFormat.TXT, "scale.txt", file.name,
                            file.length(), hash, System.currentTimeMillis() + i)
                        container.library.add(book); owned.add(book)
                    }
                }
                val targetCount = initialCount + count
                val beforeLaunch = SystemClock.elapsedRealtimeNanos()
                ActivityScenario.launch<MainActivity>(android.content.Intent(context, MainActivity::class.java)).use {
                    compose.waitUntil(30_000) {
                        compose.onAllNodesWithText(context.getString(R.string.library_count, targetCount)).fetchSemanticsNodes().isNotEmpty()
                    }
                    compose.waitForIdle()
                    val firstUsableMs = (SystemClock.elapsedRealtimeNanos() - beforeLaunch) / 1_000_000.0
                    val input = compose.onNodeWithText(context.getString(R.string.search_books))
                    val latencies = mutableListOf<Double>()
                    repeat(20) { iteration ->
                        val matches = if (iteration % 2 == 0) 0 else 1
                        val query = if (matches == 0) "$batch-MISSING" else "$batch-$count"
                        val started = SystemClock.elapsedRealtimeNanos()
                        input.performTextReplacement(query)
                        compose.waitUntil(15_000) {
                            compose.onAllNodesWithText(context.getString(R.string.search_count, matches, targetCount)).fetchSemanticsNodes().isNotEmpty()
                        }
                        latencies += (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0
                    }
                    val info = Debug.MemoryInfo().also(Debug::getMemoryInfo)
                    val sorted = latencies.sorted()
                    results.put(JSONObject().put("fixtureBooks", count).put("totalBooks", targetCount)
                        .put("firstUsableMs", firstUsableMs).put("queryP50Ms", sorted[9]).put("queryP95Ms", sorted[18])
                        .put("querySamplesMs", JSONArray(latencies)).put("processPssKiB", info.totalPss))
                    val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
                    try { File(folder, "scale-$count.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it)) } }
                    finally { bitmap.recycle() }
                }
            }
            succeeded = true
        } finally {
            File(folder, "metadata-scale.json").writeText(JSONObject().put("passed",succeeded)
                .put("captureUtc",java.time.Instant.now().toString()).put("api",android.os.Build.VERSION.SDK_INT)
                .put("device",android.os.Build.MODEL).put("input","Compose semantics SetText; real Room + MainActivity")
                .put("covers","TXT placeholder covers; real artwork cold/warm and scroll frame benchmarks remain pending")
                .put("results",results).toString(2))
            withContext(NonCancellable + Dispatchers.IO) {
                for (book in owned) {
                    val result = container.remover.remove(book.id,book.sha256)
                    check(result is BookRemovalResult.Removed) { "Owned scale fixture cleanup failed: $result" }
                }
            }
        }
    }
}
