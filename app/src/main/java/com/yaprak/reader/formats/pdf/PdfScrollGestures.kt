package com.yaprak.reader.formats.pdf

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputScope
import kotlin.math.abs

/** At baseline, a single finger belongs to the parent list. Pinch or an already zoomed page owns pan. */
internal suspend fun PointerInputScope.detectPdfScrollTransforms(canPan: () -> Boolean,
    transform: (Offset, Float) -> Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        var accumulatedPan = Offset.Zero
        var accumulatedZoom = 1f
        var claimed = false
        do {
            val event = awaitPointerEvent()
            if (event.changes.any { it.isConsumed }) break
            val pinch = event.changes.count { it.pressed || it.previousPressed } > 1
            if (pinch || canPan()) {
                val pan = event.calculatePan()
                val zoom = event.calculateZoom()
                accumulatedPan += pan
                accumulatedZoom *= zoom
                if (!claimed && (accumulatedPan.getDistance() > viewConfiguration.touchSlop ||
                    abs(1 - accumulatedZoom) * minOf(size.width, size.height) > viewConfiguration.touchSlop)) claimed = true
                if (claimed && zoom.isFinite() && pan.x.isFinite() && pan.y.isFinite()) {
                    transform(pan, zoom)
                    event.changes.forEach { it.consume() }
                }
            }
        } while (event.changes.any { it.pressed })
    }
}
