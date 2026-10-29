package com.yaprak.reader.feature

import com.yaprak.reader.reader.PdfScrollController
import kotlinx.coroutines.flow.MutableStateFlow

/** Controlled native acceptance boundary for Activity/binding fixtures, never a production reader. */
internal class ReaderTestPdfScrollController(initial: Boolean = false) : PdfScrollController {
    override val enabled = MutableStateFlow(initial)
    val calls = mutableListOf<Boolean>()
    var accepts = true
    var acknowledges = true
    var failure: Exception? = null
    var afterApply: () -> Unit = {}
    override fun apply(enabled: Boolean): Boolean {
        calls += enabled
        failure?.let { throw it }
        if (!accepts) return false
        if (acknowledges) this.enabled.value = enabled
        afterApply()
        return true
    }
}
