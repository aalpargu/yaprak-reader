package com.yaprak.reader.feature

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeTestRule

/** Existing advanced-control checks open the real UI; they never write widget state. */
internal fun ComposeTestRule.readerToolNodeWithText(text: String, substring: Boolean = false,
    ignoreCase: Boolean = false, useUnmergedTree: Boolean = false): SemanticsNodeInteraction {
    if (onAllNodesWithText("Diğer araçlar").fetchSemanticsNodes().isNotEmpty()) {
        onNodeWithText("Diğer araçlar").performScrollTo().performClick()
    }
    return onNodeWithText(text, substring = substring, ignoreCase = ignoreCase, useUnmergedTree = useUnmergedTree)
}
