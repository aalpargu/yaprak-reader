package com.yaprak.reader.feature

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import org.junit.Assert.assertEquals

/** Verify the rendered text, since a Dialog creates a separate composition/window. */
internal fun SemanticsNodeInteraction.assertFontScale(expected: Float): SemanticsNodeInteraction {
    val layouts = mutableListOf<TextLayoutResult>()
    performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
    assertEquals(expected, layouts.single().layoutInput.density.fontScale, 0.01f)
    return this
}
