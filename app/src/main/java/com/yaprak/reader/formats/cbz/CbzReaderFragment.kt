package com.yaprak.reader.formats.cbz

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.yaprak.reader.R
import com.yaprak.reader.reader.ReaderTap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** Recreated by the retained reader session; an initial/requested page is never a visible-page proof. */
internal class CbzReaderFragment(private val document: CbzDocument, initialPage: Int,
    private val currentSurface: () -> Boolean, private val onTap: () -> Unit,
    initialDoublePage: Boolean = false,
) : Fragment() {
    private var requested = initialPage
    private var doublePreferred = initialDoublePage
    private var wideViewport = false
    private var installedPages = emptyList<Int>()
    private var generation = 0L
    private var renderJob: Job? = null
    private var canvas: CbzPageView? = null
    private var loading: ProgressBar? = null
    private var error: LinearLayout? = null
    private var tapHandler: ((ReaderTap) -> Boolean)? = null
    val drawnPage = MutableStateFlow<Int?>(null)
    val drawnPages = MutableStateFlow<List<Int>>(emptyList())
    val renderFailed = MutableStateFlow(false)
    val stablePage get() = drawnPage.value?.takeIf { active() && it == requested }
    private fun active() = isResumed && document.isOpen && currentSurface()
    fun setTapHandler(handler: ((ReaderTap) -> Boolean)?) { tapHandler = handler }
    fun goTo(index: Int): Boolean {
        if (!active() || index !in document.archive.pages.indices) return false
        if (index == requested && stablePage == index) return true
        requested = index; startRender(); return true
    }
    fun turn(delta: Int): Boolean = if (stablePage != null)
        CbzSpreadLayout.turn(requested, document.archive.pages.size, doublePreferred && wideViewport, delta)?.let(::goTo) ?: false
        else false
    fun setDoublePage(enabled: Boolean): Boolean {
        if (!active() || stablePage == null) return false
        return restoreDoublePage(enabled)
    }
    fun restoreDoublePage(enabled: Boolean): Boolean {
        if (!active()) return false
        if (enabled == doublePreferred) return true
        doublePreferred = enabled; startRender(); return true
    }
    private fun requestedPages() = CbzSpreadLayout.pages(requested, document.archive.pages.size, doublePreferred && wideViewport)
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val context = requireContext()
        val root = FrameLayout(context)
        canvas = CbzPageView(context, ::active, onTap) { tap -> tapHandler?.invoke(tap) ?: false }.also { image ->
            image.frameDrawn = { index ->
                val visible = index?.takeIf { active() && it == requested && !renderFailed.value }
                drawnPages.value = if (visible != null) installedPages else emptyList()
                drawnPage.value = visible
            }
            image.viewportChanged = { width, height ->
                val wasWide = wideViewport
                wideViewport = CbzSpreadLayout.wide(width, height, resources.displayMetrics.density)
                if (wasWide != wideViewport && doublePreferred && active()) startRender()
            }
            root.addView(image, FrameLayout.LayoutParams(-1, -1))
        }
        loading = ProgressBar(context).also { progress ->
            progress.contentDescription = getString(R.string.cbz_loading)
            root.addView(progress, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        }
        error = LinearLayout(context).also { box ->
            box.orientation = LinearLayout.VERTICAL; box.gravity = Gravity.CENTER
            val padding = (24 * resources.displayMetrics.density).toInt(); box.setPadding(padding, padding, padding, padding)
            box.addView(TextView(context).apply {
                text = getString(R.string.cbz_page_failed); gravity = Gravity.CENTER
                setTextColor(android.graphics.Color.WHITE); textSize = 18f
            }, LinearLayout.LayoutParams(-1, -2))
            box.addView(Button(context).apply { text = getString(R.string.retry); setOnClickListener { if (active()) startRender() } }, LinearLayout.LayoutParams(-1, -2))
            box.visibility = View.GONE
            root.addView(box, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        }
        return root
    }
    override fun onResume() { super.onResume(); startRender() }
    private fun startRender() {
        val imageView = canvas ?: return
        stopRender(); imageView.clear(); renderFailed.value = false
        if (!active()) return
        loading?.visibility = View.VISIBLE; error?.visibility = View.GONE
        val owner = generation; val index = requested; val indices = requestedPages()
        renderJob = viewLifecycleOwner.lifecycleScope.launch {
            var owned: CbzFrame? = null
            try {
                val frame = document.renderFrame(index, indices).also { owned = it }
                currentCoroutineContext().ensureActive()
                if (owner != generation || canvas !== imageView || !active() || index != requested || indices != requestedPages()) return@launch
                val label = if (indices.size == 1) getString(R.string.cbz_page_description, index + 1, document.archive.pages.size)
                    else getString(R.string.cbz_spread_description, indices.first() + 1, indices.last() + 1,
                        document.archive.pages.size, index + 1)
                imageView.install(frame, label)
                installedPages = indices
                owned = null; loading?.visibility = View.GONE
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (owner == generation && canvas === imageView && active() && index == requested) {
                    drawnPage.value = null; renderFailed.value = true
                    loading?.visibility = View.GONE; error?.visibility = View.VISIBLE
                }
            } finally { owned?.close() }
        }
    }
    private fun stopRender() {
        generation++; renderJob?.cancel(); renderJob = null
        drawnPage.value = null; drawnPages.value = emptyList(); installedPages = emptyList()
    }
    override fun onPause() { stopRender(); super.onPause() }
    override fun onDestroyView() {
        stopRender(); canvas?.clear(); canvas?.frameDrawn = null; canvas?.viewportChanged = null
        canvas = null; loading = null; error = null
        super.onDestroyView()
    }
}
