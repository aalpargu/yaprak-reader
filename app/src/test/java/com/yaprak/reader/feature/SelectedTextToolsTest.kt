package com.yaprak.reader.feature

import android.app.Activity
import android.app.Application
import android.app.SearchManager
import android.content.*
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import com.yaprak.reader.feature.reader.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SelectedTextToolsTest {
    private val text = "  İ/ı 😀\nleaf & e\u0301 + ? #  "
    private val tool = SelectedTextTool("dictionary.fixture", "dictionary.fixture.Process", "Özgün araç 🌿")
    private fun query() = Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain")
    private fun info(packageName: String = tool.packageName, name: String = tool.activityName, label: String = tool.label) = ActivityInfo().apply {
        this.packageName = packageName; this.name = name; enabled = true; exported = true
        applicationInfo = ApplicationInfo().apply { this.packageName = packageName; enabled = true }
        nonLocalizedLabel = label
    }
    private fun register(activity: Activity, value: ActivityInfo) {
        val packages = shadowOf(activity.packageManager)
        packages.addOrUpdateActivity(value)
        packages.addIntentFilterForActivity(ComponentName(value.packageName, value.name), IntentFilter(Intent.ACTION_PROCESS_TEXT).apply { addDataType("text/plain") })
    }

    @Test fun `web and readonly explicit process intents preserve only exact selected Unicode text`() {
        val web = selectedTextWebIntent(text)
        assertEquals(Intent.ACTION_WEB_SEARCH, web.action)
        assertEquals(text, web.getStringExtra(SearchManager.QUERY))
        assertEquals(setOf(SearchManager.QUERY), web.extras!!.keySet())
        assertNull(web.data); assertNull(web.clipData); assertNull(web.component); assertEquals(0, web.flags)
        val process = selectedTextProcessIntent(tool, text)
        assertEquals(Intent.ACTION_PROCESS_TEXT, process.action); assertEquals("text/plain", process.type)
        assertEquals(ComponentName(tool.packageName, tool.activityName), process.component)
        assertEquals(text, process.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT).toString())
        assertTrue(process.getBooleanExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, false))
        assertEquals(setOf(Intent.EXTRA_PROCESS_TEXT, Intent.EXTRA_PROCESS_TEXT_READONLY), process.extras!!.keySet())
        assertNull(process.data); assertNull(process.clipData); assertEquals(0, process.flags)
    }

    @Test fun `blank NUL malformed surrogate and over limit selections cannot form external payloads`() {
        for (invalid in listOf("", " \n\t", "x\u0000y", "\ud800", "\udc00", "x".repeat(5001))) {
            assertEquals(SelectedTextToolFailure.INVALID_TEXT,
                (runCatching { selectedTextWebIntent(invalid) }.exceptionOrNull() as SelectedTextToolException).reason)
            assertEquals(SelectedTextToolFailure.INVALID_TEXT,
                (runCatching { selectedTextProcessIntent(tool, invalid) }.exceptionOrNull() as SelectedTextToolException).reason)
        }
        assertEquals("x".repeat(5000), selectedTextWebIntent("x".repeat(5000)).getStringExtra(SearchManager.QUERY))
    }

    @Test fun `actual PackageManager discovery excludes inaccessible disabled own and protected activities without launch`() = runBlocking {
        val host = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity = host.get()
            register(activity, info())
            register(activity, info("hidden.fixture", "hidden.fixture.Process").apply { exported = false })
            register(activity, info("disabled.fixture", "disabled.fixture.Process").apply { enabled = false })
            register(activity, info("appdisabled.fixture", "appdisabled.fixture.Process").apply { applicationInfo.enabled = false })
            register(activity, info(activity.packageName, "${activity.packageName}.OwnProcess"))
            register(activity, info("protected.fixture", "protected.fixture.Process").apply { permission = "fixture.private.permission" })
            val found = AndroidSelectedTextTools(activity).discover()
            assertEquals(listOf(tool), found)
            assertNull(shadowOf(activity).nextStartedActivity)
        } finally { host.pause().stop().destroy() }
    }

    @Test fun `discovery deduplicates and returns immutable original labels with explicit bounded failure`() = runBlocking {
        val host = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity = host.get(); register(activity, info())
            val packages = shadowOf(activity.packageManager)
            val duplicate = ResolveInfo().apply { activityInfo = info() }
            packages.addResolveInfoForIntentNoDefaults(query(), duplicate)
            packages.addResolveInfoForIntentNoDefaults(query(), duplicate)
            val found = AndroidSelectedTextTools(activity).discover()
            assertEquals(listOf(tool), found)
            assertTrue(runCatching { (found as MutableList).clear() }.exceptionOrNull() is UnsupportedOperationException)
            packages.addResolveInfoForIntent(query(), (0..MAX_TEXT_PROCESSORS).map { index ->
                ResolveInfo().apply { activityInfo = info("tool$index.fixture", "tool$index.fixture.Process", "Tool $index") }
            })
            assertEquals(SelectedTextToolFailure.LIMIT,
                (runCatching { AndroidSelectedTextTools(activity).discover() }.exceptionOrNull() as SelectedTextToolException).reason)
        } finally { host.pause().stop().destroy() }
    }

    @Test fun `launch revalidates current component permissions and removed intent support`() = runBlocking {
        val host = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity = host.get(); val packages = shadowOf(activity.packageManager)
            register(activity, info())
            val external = AndroidSelectedTextTools(activity)
            val found = external.discover().single()
            external.process(found, text)
            val launched = shadowOf(activity).nextStartedActivity
            assertEquals(ComponentName(tool.packageName, tool.activityName), launched.component)
            assertEquals(text, launched.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT).toString())
            packages.addOrUpdateActivity(info().apply { exported = false })
            assertEquals(SelectedTextToolFailure.DENIED,
                (runCatching { external.process(found, text) }.exceptionOrNull() as SelectedTextToolException).reason)
            packages.addOrUpdateActivity(info())
            packages.clearIntentFilterForActivity(ComponentName(tool.packageName, tool.activityName))
            assertEquals(SelectedTextToolFailure.NO_HANDLER,
                (runCatching { external.process(found, text) }.exceptionOrNull() as SelectedTextToolException).reason)
            packages.removeActivity(ComponentName(tool.packageName, tool.activityName))
            assertEquals(SelectedTextToolFailure.NO_HANDLER,
                (runCatching { external.process(found, text) }.exceptionOrNull() as SelectedTextToolException).reason)
            assertNull(shadowOf(activity).nextStartedActivity)
        } finally { host.pause().stop().destroy() }
    }

    @Test fun `missing search handler denied and unexpected launch errors are visible typed failures`() {
        val host = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            for ((failure, reason) in listOf(ActivityNotFoundException() to SelectedTextToolFailure.NO_HANDLER,
                    SecurityException() to SelectedTextToolFailure.DENIED, IllegalStateException() to SelectedTextToolFailure.SOURCE)) {
                val context = object : ContextWrapper(host.get()) { override fun startActivity(intent: Intent) { throw failure } }
                assertEquals(reason, (runCatching { AndroidSelectedTextTools(context).searchWeb(text) }
                    .exceptionOrNull() as SelectedTextToolException).reason)
            }
            val cancellation = kotlinx.coroutines.CancellationException("controlled cancellation")
            val cancelled = object : ContextWrapper(host.get()) {
                override fun startActivity(intent: Intent) { throw cancellation }
            }
            assertSame(cancellation, runCatching { AndroidSelectedTextTools(cancelled).searchWeb(text) }.exceptionOrNull())
            assertNull(shadowOf(host.get()).nextStartedActivity)
        } finally { host.pause().stop().destroy() }
    }
}
