package com.yaprak.reader.formats

import android.app.Activity
import android.app.Application
import android.graphics.Rect
import android.view.View
import android.webkit.WebView
import android.widget.FrameLayout
import com.yaprak.reader.formats.epub.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowViewRootImpl
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class EpubReadingVisibilityTest {
    private val url = "https://readium/publication/OPS/chapter.xhtml"
    private fun response(href: String = url, ready: Any = true, visible: Any = true): String = JSONObject.quote(
        JSONObject().put("href", href).put("ready", ready).put("visible", visible).toString())

    @Test fun `raw document URL is resolved through current publication base including encoded source names`() {
        assertEquals(url, readingResourceUrl(null, "OPS/chapter.xhtml#p"))
        assertEquals(url, readingResourceUrl("relative/", "OPS/chapter.xhtml"))
        assertEquals("https://local.example/books/OPS/a%20b.xhtml", readingResourceUrl("https://local.example/books/", "OPS/a%20b.xhtml"))
        assertNull(readingResourceUrl(null, "https://external.example/book"))
        assertNull(readingResourceUrl(null, "//external.example/book"))
        assertNull(readingResourceUrl(null, "bad href"))
    }

    @Test fun `document readiness is source bound and accepts image or SVG documents without text evidence`() {
        assertTrue(decodeVisibleDocument(response(), url))
        assertTrue(decodeVisibleDocument(response(href = "$url#figure"), url))
        assertFalse(decodeVisibleDocument(response(ready = false), url))
        assertFalse(decodeVisibleDocument(response(visible = false), url))
        assertFalse(decodeVisibleDocument(response(href = "https://readium/publication/other.xhtml"), url))
        assertFalse(decodeVisibleDocument(response(href = "https://external.example/OPS/chapter.xhtml"), url))
        assertFalse(decodeVisibleDocument(response(ready = "true"), url))
        assertFalse(decodeVisibleDocument(response(visible = 1), url))
    }

    @Test fun `malformed oversized direct or trailing script results never authorize visibility`() {
        for (raw in listOf(null, "null", "true", "{}", "{\"visible\":true}", "x".repeat(8193),
            response() + "false", JSONObject.quote("{} false"), JSONObject.quote("{\"ready\":true}")))
            assertFalse(decodeVisibleDocument(raw, url))
    }

    @Test fun `closed background or replaced source skips query and rejects its suspended late delivery`() = runTest {
        var calls = 0
        assertFalse(verifyVisibleDocument(url, { false }) { calls++; response() })
        assertEquals(0, calls)
        for (changedGate in listOf("source", "locator", "view", "resume", "closed", "error", "serial")) {
            var current = true; val late = CompletableDeferred<String?>()
            val result = async { verifyVisibleDocument(url, { current }) { late.await() } }
            runCurrent(); assertFalse(result.isCompleted)
            current = false; late.complete(response())
            assertFalse("Late $changedGate must reject visibility", result.await())
        }
    }

    @Test fun `timed out throwing and cancelled native probes never turn into successful visibility`() = runTest {
        val timeout = async { verifyVisibleDocument(url, { true }) { awaitCancellation() } }
        advanceTimeBy(EPUB_VISIBILITY_TIMEOUT_MILLIS); runCurrent(); assertFalse(timeout.await())
        assertFalse(verifyVisibleDocument(url, { true }) { throw java.io.IOException("Native failure") })
        val late = CompletableDeferred<String?>()
        val cancelled = async { verifyVisibleDocument(url, { true }) { late.await() } }
        runCurrent(); cancelled.cancel(); late.complete(response())
        assertTrue(runCatching { cancelled.await() }.exceptionOrNull() is CancellationException)
    }

    @Test fun `a valid current native callback permits visibility while the same callback cannot prove another resource`() = runTest {
        assertTrue(verifyVisibleDocument(url, { true }) { response() })
        assertFalse(verifyVisibleDocument(url, { true }) { response(href = "${url}?other") })
        assertFalse(verifyVisibleDocument(url, { true }) { null })
    }

    /** Real attached Android view hierarchy and clipped native rectangles; this is not a WebView paint claim. */
    @Test fun `unique attached current WebView is selected and hidden offscreen or duplicate preload is rejected`() {
        val host = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = host.get()
        val root = FrameLayout(activity)
        val current = WebView(activity); val preload = WebView(activity)
        try {
            activity.setContentView(root)
            root.addView(current, FrameLayout.LayoutParams(160, 240))
            root.addView(preload, FrameLayout.LayoutParams(160, 240))
            activity.window.setLayout(320, 640)
            current.loadUrl(url); preload.loadUrl(url)
            host.visible()
            dispatchWindowVisibility(activity, true)
            layoutWindow(activity, root)
            setWebViewFrame(current, 0, 0, 160, 240); setWebViewFrame(preload, 400, 0, 560, 240)
            assertTrue("Document must attach to the real test Window", current.isAttachedToWindow)
            assertTrue("Document and native ancestors must be shown", current.isShown)
            assertEquals(View.VISIBLE, current.windowVisibility)
            val clipped = Rect()
            assertTrue("Window ancestry must provide a visible clipped document rectangle:\n${ancestry(current)}", current.getGlobalVisibleRect(clipped))
            assertTrue(clipped.width() > 0 && clipped.height() > 0)
            assertTrue(nativeDocumentVisible(current)); assertFalse(nativeDocumentVisible(preload))
            assertSame(current, currentVisibleWebView(root, url))
            dispatchWindowVisibility(activity, false)
            assertEquals(View.GONE, current.windowVisibility)
            assertFalse(nativeDocumentVisible(current))
            assertNull(currentVisibleWebView(root, url))
            dispatchWindowVisibility(activity, true)
            layoutWindow(activity, root)
            setWebViewFrame(current, 0, 0, 160, 240); setWebViewFrame(preload, 400, 0, 560, 240)
            assertSame(current, currentVisibleWebView(root, url))
            setWebViewFrame(preload, 160, 0, 320, 240)
            assertNull(currentVisibleWebView(root, url))
            preload.visibility = View.GONE
            assertSame(current, currentVisibleWebView(root, url))
            current.alpha = 0f; assertNull(currentVisibleWebView(root, url))
            current.alpha = 1f; root.alpha = 0f; assertNull(currentVisibleWebView(root, url)); root.alpha = 1f
            current.alpha = 1f; current.visibility = View.INVISIBLE; assertNull(currentVisibleWebView(root, url))
            current.visibility = View.VISIBLE; current.loadUrl("https://readium/publication/other.xhtml")
            assertNull(currentVisibleWebView(root, url))
            root.removeView(current); assertFalse(nativeDocumentVisible(current))
        } finally { current.destroy(); preload.destroy(); host.pause().stop().destroy() }
    }

    @Test fun `view traversal stays bounded and a detached zero-size document never authorizes visibility`() {
        val host = Robolectric.buildActivity(Activity::class.java).setup()
        val root = FrameLayout(host.get()); val current = WebView(host.get())
        try {
            assertFalse(nativeDocumentVisible(current))
            host.get().setContentView(root)
            root.addView(current); current.loadUrl(url)
            host.get().window.setLayout(320, 640)
            host.visible()
            dispatchWindowVisibility(host.get(), true)
            layoutWindow(host.get(), root); setWebViewFrame(current, 0, 0, 0, 0)
            assertFalse(nativeDocumentVisible(current))
            setWebViewFrame(current, 0, 0, 160, 240)
            assertTrue("A valid visible document must precede the traversal bound check:\n${ancestry(current)}", nativeDocumentVisible(current))
            repeat(257) { root.addView(View(host.get())) }
            assertNull(currentVisibleWebView(root, url))
        } finally { current.destroy(); host.pause().stop().destroy() }
    }

    /** Send the real WindowManager event, rather than changing View.AttachInfo or mocking a getter. */
    private fun dispatchWindowVisibility(activity: Activity, visible: Boolean) {
        val decor = activity.window.decorView
        val viewRoot = View::class.java.getMethod("getViewRootImpl").invoke(decor)
        requireNotNull(viewRoot) { "Activity.visible must install its native ViewRoot" }
        viewRoot.javaClass.getMethod("dispatchAppVisibility", java.lang.Boolean.TYPE).invoke(viewRoot, visible)
        val expected = if (visible) View.VISIBLE else View.GONE
        // The SDK queues a handler event and then a Choreographer traversal. Await a bounded
        // number of actual frames, so Window visibility is established before testing geometry.
        repeat(8) {
            ShadowLooper.idleMainLooper(16, TimeUnit.MILLISECONDS)
            if (decor.windowVisibility == expected) return
        }
        assertEquals("SDK Window visibility event must reach the attached view hierarchy", expected, decor.windowVisibility)
    }

    private fun layoutWindow(activity: Activity, root: FrameLayout) {
        val decor = activity.window.decorView
        val viewRoot = requireNotNull(View::class.java.getMethod("getViewRootImpl").invoke(decor))
        // ViewRoot clips every global rectangle to the native Window frame, independently
        // of child/decor.layout. Deliver the SDK resize event before measuring the hierarchy.
        Shadow.extract<ShadowViewRootImpl>(viewRoot).callDispatchResized()
        repeat(8) { ShadowLooper.idleMainLooper(16, TimeUnit.MILLISECONDS) }
        var parent: View? = root
        while (parent != null) {
            parent.forceLayout()
            parent = parent.parent as? View
        }
        decor.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY))
        decor.layout(0, 0, 320, 640)
        root.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 320, 480)
    }

    private fun setWebViewFrame(view: WebView, left: Int, top: Int, right: Int, bottom: Int) {
        view.layout(left, top, right, bottom)
        // SDK WebView.setFrame delegates to its provider. Robolectric's deep-proxy provider
        // ignores that call; use the SDK bridge supplied to real providers to execute the
        // superclass View frame operation. This preserves real bounds and ancestor clipping,
        // and provides no evidence that a host WebView has painted or executed document JS.
        val accessClass = WebView::class.java.declaredClasses.single { it.simpleName == "PrivateAccess" }
        val access = accessClass.getConstructor(WebView::class.java).newInstance(view)
        accessClass.getMethod("super_setFrame", Integer.TYPE, Integer.TYPE, Integer.TYPE, Integer.TYPE)
            .invoke(access, left, top, right, bottom)
        assertEquals(left, view.left)
        assertEquals(top, view.top)
        assertEquals(right, view.right)
        assertEquals(bottom, view.bottom)
    }

    private fun ancestry(document: View): String = buildString {
        var parent: Any? = document
        var depth = 0
        while (parent is View && depth++ < 16) {
            val view = parent
            val rect = Rect()
            val globallyVisible = view.getGlobalVisibleRect(rect)
            appendLine("${view.javaClass.simpleName} id=${view.id} measured=${view.measuredWidth}x${view.measuredHeight} " +
                "bounds=[${view.left},${view.top},${view.right},${view.bottom}] scroll=${view.scrollX},${view.scrollY} " +
                "global=$globallyVisible:$rect visibility=${view.visibility}/${view.windowVisibility} " +
                "shown=${view.isShown} attached=${view.isAttachedToWindow} alpha=${view.alpha}")
            parent = view.parent
        }
        val terminal = parent
        if (terminal != null) {
            val dimensions = runCatching {
                fun frame(name: String): Int = terminal.javaClass.getDeclaredField(name).apply { isAccessible = true }.getInt(terminal)
                "${frame("mWidth")}x${frame("mHeight")}"
            }.getOrDefault("unavailable")
            appendLine("${terminal.javaClass.simpleName} nativeWindowFrame=$dimensions")
        }
    }
}
