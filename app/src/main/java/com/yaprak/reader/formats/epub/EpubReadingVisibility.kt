package com.yaprak.reader.formats.epub

import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import java.net.URI
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.json.JSONTokener

internal const val EPUB_VISIBILITY_TIMEOUT_MILLIS = 750L
private const val MAX_VISIBILITY_VIEWS = 256
private val visualRequest = AtomicLong()

/** The cached Readium 3.1.2 server uses this base only when Publication.baseUrl is not absolute. */
internal fun readingResourceUrl(baseUrl: String?, href: String): String? = runCatching {
    val relative = URI(href)
    require(!relative.isAbsolute && relative.rawAuthority == null && href.isNotBlank())
    val supplied = baseUrl?.let(::URI)
    val base = supplied?.takeIf { it.isAbsolute } ?: URI("https://readium/publication/")
    base.resolve(relative).toString().substringBefore('#')
}.getOrNull()

/** No private pager APIs. A preloaded, clipped-out or ambiguous WebView is never proof. */
internal fun currentVisibleWebView(root: View?, expectedUrl: String): WebView? {
    return try { findCurrentVisibleWebView(root, expectedUrl) } catch (_: Exception) { null }
}

private fun findCurrentVisibleWebView(root: View?, expectedUrl: String): WebView? {
    if (root == null) return null
    val queue = ArrayDeque<View>(); queue += root
    var visited = 0
    var result: WebView? = null
    while (queue.isNotEmpty()) {
        if (++visited > MAX_VISIBILITY_VIEWS) return null
        val view = queue.removeFirst()
        if (view is WebView && view.url?.substringBefore('#') == expectedUrl && nativeDocumentVisible(view)) {
            if (result != null) return null
            result = view
        }
        if (view is ViewGroup) {
            if (view.childCount > MAX_VISIBILITY_VIEWS - visited - queue.size) return null
            for (index in 0 until view.childCount) queue += view.getChildAt(index)
        }
    }
    return result
}

internal fun nativeDocumentVisible(view: WebView): Boolean {
    return try { hasVisibleDocumentBounds(view) } catch (_: Exception) { false }
}

private fun hasVisibleDocumentBounds(view: WebView): Boolean {
    if (!view.isAttachedToWindow || !view.isShown || view.windowVisibility != View.VISIBLE ||
        view.width <= 0 || view.height <= 0) return false
    var parent: View? = view
    var depth = 0
    while (parent != null) {
        if (++depth > 64 || parent.alpha <= 0f) return false
        parent = parent.parent as? View
    }
    val bounds = Rect()
    return view.getGlobalVisibleRect(bounds) && bounds.width() > 0 && bounds.height() > 0
}

/** Small readiness/geometry result. Text, locators and highlight coverage are deliberately absent. */
internal fun decodeVisibleDocument(raw: String?, expectedUrl: String): Boolean = try {
    if (raw == null || raw.length > 8192) false else {
        val envelope = JSONTokener(raw)
        val value = envelope.nextValue() as? String
        if (value == null || envelope.nextClean() != '\u0000') false else {
            val content = JSONTokener(value)
            val json = content.nextValue() as? JSONObject
            json != null && content.nextClean() == '\u0000' && json.get("ready") == true && json.get("visible") == true &&
                (json.get("href") as? String)?.substringBefore('#') == expectedUrl
        }
    }
} catch (_: Exception) { false }

internal suspend fun verifyVisibleDocument(expectedUrl: String, current: () -> Boolean,
    evaluate: suspend () -> String?): Boolean {
    if (!current()) return false
    val raw = try { withTimeoutOrNull(EPUB_VISIBILITY_TIMEOUT_MILLIS) { evaluate() } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
    currentCoroutineContext().ensureActive()
    return current() && decodeVisibleDocument(raw, expectedUrl)
}

internal suspend fun renderedDocumentProbe(view: WebView): String? {
    // API 26 is the app minimum. This callback confirms pending WebView content is drawable.
    suspendCancellableCoroutine<Unit> { continuation ->
        view.postVisualStateCallback(visualRequest.incrementAndGet(), object : WebView.VisualStateCallback() {
            override fun onComplete(requestId: Long) { if (continuation.isActive) continuation.resume(Unit) }
        })
    }
    return suspendCancellableCoroutine { continuation ->
        view.evaluateJavascript(DOCUMENT_VISIBILITY_SCRIPT) { raw ->
            if (continuation.isActive) continuation.resume(raw)
        }
    }
}

internal val DOCUMENT_VISIBILITY_SCRIPT = """
    (function () {
      const element = document.body || document.documentElement;
      const width = window.innerWidth, height = window.innerHeight;
      let visible = false;
      if (element && document.visibilityState === 'visible' && width > 0 && height > 0) {
        let ancestorsVisible = true, ancestor = element, depth = 0;
        while (ancestor) {
          if (++depth > 64) { ancestorsVisible = false; break; }
          const style = getComputedStyle(ancestor);
          if (style.display === 'none' || style.visibility !== 'visible' || !(Number(style.opacity) > 0)) {
            ancestorsVisible = false; break;
          }
          ancestor = ancestor.parentElement;
        }
        const rect = element.getBoundingClientRect();
        visible = ancestorsVisible &&
          rect.width > 0 && rect.height > 0 && rect.right > 0 && rect.bottom > 0 &&
          rect.left < width && rect.top < height;
      }
      return JSON.stringify({href: location.href, ready: document.readyState === 'complete', visible: visible});
    })();
""".trimIndent()
