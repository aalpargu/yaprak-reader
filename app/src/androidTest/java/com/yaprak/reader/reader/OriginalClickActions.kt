package com.yaprak.reader.reader

import com.yaprak.reader.readerMethod
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction

/** Freeze the app's callback, rather than Material's mutable semantics-to-node dispatcher.
 * This deliberately checks the pinned Compose1.8.3 implementation; an incompatible upgrade
 * must fail the harness instead of silently testing the latest callback as if it were retired.
 */
internal fun SemanticsNodeInteraction.captureApplicationClick(): () -> Unit {
    val wrapper = checkNotNull(fetchSemanticsNode().config[SemanticsActions.OnClick].action)
    val owner = checkNotNull(wrapper.javaClass.getDeclaredField("this\$0").apply { isAccessible = true }.get(wrapper))
    val clickable = generateSequence(owner.javaClass as Class<*>?) { it.superclass }
        .firstOrNull { it.name == "androidx.compose.foundation.AbstractClickableNode" }
        ?: error("Pinned Compose click dispatcher changed")
    val callback = clickable.readerMethod("getOnClick").apply { isAccessible = true }.invoke(owner)
    @Suppress("UNCHECKED_CAST")
    return callback as? (() -> Unit) ?: error("Pinned Compose app callback changed")
}
