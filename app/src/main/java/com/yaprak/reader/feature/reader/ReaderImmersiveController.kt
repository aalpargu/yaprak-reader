package com.yaprak.reader.feature.reader

import android.view.Window
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner

/** Transient system-bar visibility for this reader window; no preference or reading state is saved. */
internal class ReaderImmersiveController(window: Window, lifecycle: Lifecycle) : DefaultLifecycleObserver {
    private val controller = WindowCompat.getInsetsController(window, window.decorView)
    private val originalBehavior = controller.systemBarsBehavior
    private var resumed = false
    private var focused = false
    private var readerReady = false
    private var chromeVisible = true
    private var blocked = false
    private var imeVisible = false
    private var requestedHidden: Boolean? = null

    init {
        lifecycle.addObserver(this)
        apply()
    }

    fun update(readerReady: Boolean, chromeVisible: Boolean, blocked: Boolean) {
        this.readerReady = readerReady
        this.chromeVisible = chromeVisible
        this.blocked = blocked
        apply()
    }

    fun updateImeVisible(visible: Boolean) { imeVisible = visible; apply() }

    fun updateWindowFocus(focused: Boolean) { this.focused = focused; apply() }

    override fun onResume(owner: LifecycleOwner) { resumed = true; apply() }
    override fun onPause(owner: LifecycleOwner) { resumed = false; apply() }
    override fun onStop(owner: LifecycleOwner) { resumed = false; apply() }
    override fun onDestroy(owner: LifecycleOwner) { resumed = false; focused = false; apply() }

    private fun apply() {
        val wantsHidden = resumed && readerReady && !chromeVisible && !blocked && !imeVisible
        // Android's first-use immersive confirmation temporarily owns focus. Showing bars on
        // that focus loss dismisses it; focus return hides bars and opens it again indefinitely.
        // Admit a new hide only with focus, while retaining an already hidden resumed window.
        // Pause, a modal/IME, visible tools or revoked reader readiness still restore bars.
        val hide = wantsHidden && (focused || requestedHidden == true)
        // A user's edge swipe may temporarily reveal bars. Do not counter it on an inset update.
        if (requestedHidden == hide) return
        if (hide) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = originalBehavior
        }
        requestedHidden = hide
    }
}
