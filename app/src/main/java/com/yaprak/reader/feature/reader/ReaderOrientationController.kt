package com.yaprak.reader.feature.reader

import android.app.Activity
import android.content.pm.ActivityInfo
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.yaprak.reader.domain.settings.ReaderOrientation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Requests this Activity's orientation; a successful request does not prove physical rotation. */
internal class ReaderOrientationController(
    activity: Activity,
    private val lifecycle: Lifecycle,
    private val requestOrientation: (Int) -> Unit = { activity.requestedOrientation = it },
) : DefaultLifecycleObserver {
    private var resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
    private var destroyed = false
    private var readerReady = false
    private var preferenceReady = false
    private var choice = ReaderOrientation.AUTO
    private var successfulTarget: Int? = null
    private var failedTarget: Int? = null
    private val mutableFailure = MutableStateFlow(false)
    val applyFailed = mutableFailure.asStateFlow()

    init { lifecycle.addObserver(this) }

    fun update(saved: ReaderOrientation, preferenceReady: Boolean, readerReady: Boolean) {
        if (destroyed) return
        this.preferenceReady = preferenceReady
        this.readerReady = readerReady
        if (preferenceReady) {
            if (choice != saved) { failedTarget = null; mutableFailure.value = false }
            choice = saved
        }
        apply()
    }

    fun retry() {
        if (destroyed || !resumed || !readerReady || !preferenceReady) return
        failedTarget = null
        apply()
    }

    override fun onResume(owner: LifecycleOwner) { if (!destroyed) { resumed = true; apply() } }
    override fun onPause(owner: LifecycleOwner) { resumed = false }
    override fun onStop(owner: LifecycleOwner) { resumed = false }
    override fun onDestroy(owner: LifecycleOwner) {
        destroyed = true
        resumed = false
        lifecycle.removeObserver(this)
    }

    private fun apply() {
        if (destroyed || !resumed || !readerReady || !preferenceReady) return
        val target = when (choice) {
            ReaderOrientation.AUTO -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            ReaderOrientation.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            ReaderOrientation.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        if (target == successfulTarget || target == failedTarget) return
        try { requestOrientation(target) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { failedTarget = target; mutableFailure.value = true; return }
        successfulTarget = target
        failedTarget = null
        mutableFailure.value = false
    }
}
