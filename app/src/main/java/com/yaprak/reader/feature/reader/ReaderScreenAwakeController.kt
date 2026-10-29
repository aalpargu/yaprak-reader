package com.yaprak.reader.feature.reader

import android.view.Window
import android.view.WindowManager
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner

/** A window flag, scoped to a ready reader in the foreground; no wake lock is acquired. */
internal class ReaderScreenAwakeController(private val window: Window, lifecycle: Lifecycle) : DefaultLifecycleObserver {
    private var resumed = false
    private var enabled = false
    private var readerReady = false

    init { lifecycle.addObserver(this) }

    fun update(enabled: Boolean, readerReady: Boolean) {
        this.enabled = enabled
        this.readerReady = readerReady
        apply()
    }

    override fun onResume(owner: LifecycleOwner) { resumed = true; apply() }
    override fun onPause(owner: LifecycleOwner) { resumed = false; apply() }
    override fun onStop(owner: LifecycleOwner) { resumed = false; apply() }
    override fun onDestroy(owner: LifecycleOwner) { resumed = false; apply() }

    private fun apply() {
        val flag = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        val awake = resumed && enabled && readerReady
        if (awake == (window.attributes.flags and flag != 0)) return
        if (awake) window.addFlags(flag) else window.clearFlags(flag)
    }
}
