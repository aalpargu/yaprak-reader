package com.yaprak.reader.feature.reader

import android.view.Window
import android.view.WindowManager
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner

/** A temporary override for this reader window; system settings and preferences are untouched. */
internal class ReaderBrightnessController(private val window: Window, lifecycle: Lifecycle) : DefaultLifecycleObserver {
    private val originalBrightness = window.attributes.screenBrightness
    private var percent: Int? = null
    private var readerReady = false
    private var resumed = false
    private var focused = false
    private var destroyed = false

    init { lifecycle.addObserver(this) }

    fun update(percent: Int?, readerReady: Boolean) {
        if (destroyed) return
        // Readiness must still revoke the override even if a stale caller supplies an invalid value.
        if (percent == null || percent in 10..100) this.percent = percent
        this.readerReady = readerReady
        apply()
    }

    fun updateWindowFocus(focused: Boolean) {
        if (destroyed) return
        this.focused = focused
        apply()
    }

    override fun onResume(owner: LifecycleOwner) {
        if (destroyed) return
        resumed = true
        apply()
    }

    override fun onPause(owner: LifecycleOwner) { suspendOverride() }
    override fun onStop(owner: LifecycleOwner) { suspendOverride() }

    override fun onDestroy(owner: LifecycleOwner) {
        if (destroyed) return
        destroyed = true
        resumed = false
        focused = false
        apply()
        owner.lifecycle.removeObserver(this)
    }

    private fun suspendOverride() {
        if (destroyed) return
        resumed = false
        focused = false
        apply()
    }

    private fun apply() {
        val brightness = if (!destroyed && resumed && focused && readerReady) {
            percent?.let { it / 100f } ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        } else originalBrightness
        // Read current attributes so screen-awake and other independent window changes survive.
        val attributes = window.attributes
        if (attributes.screenBrightness == brightness) return
        attributes.screenBrightness = brightness
        window.attributes = attributes
    }
}
