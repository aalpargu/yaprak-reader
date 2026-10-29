@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)
package com.yaprak.reader.formats.epub

import com.yaprak.reader.reader.ReaderTap
import com.yaprak.reader.reader.ReaderTapController
import org.readium.r2.navigator.input.InputListener
import org.readium.r2.navigator.input.TapEvent

/** Registered before the SDK directional adapter, after native link/selection handling. */
internal class EpubTapInput(private val viewport: () -> Pair<Int, Int>) : InputListener, ReaderTapController {
    private var handler: ((ReaderTap) -> Boolean)? = null
    override fun setHandler(handler: ((ReaderTap) -> Boolean)?) { this.handler = handler }
    override fun onTap(event: TapEvent): Boolean {
        val current = handler ?: return false
        val (width, height) = viewport()
        return current(ReaderTap.fromPixels(event.point.x, event.point.y, width, height))
    }
}