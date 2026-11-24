package com.yaprak.reader.feature

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeTestRule

/**
 * Reader tools live in the "Diğer" panel, opened from the bottom bar. Open it through the real UI when
 * the requested control is not already on screen; never write widget state.
 */
/** Scrolls when the node lives in a scroll container (tools panel); bottom-bar controls need no scroll. */
internal fun SemanticsNodeInteraction.scrollToIfNeeded(): SemanticsNodeInteraction =
    try { performScrollTo() } catch (_: AssertionError) { this }

internal fun ComposeTestRule.readerToolNodeWithText(text: String, substring: Boolean = false,
    ignoreCase: Boolean = false, useUnmergedTree: Boolean = false): SemanticsNodeInteraction {
    val present = onAllNodesWithText(text, substring = substring, ignoreCase = ignoreCase, useUnmergedTree = useUnmergedTree)
        .fetchSemanticsNodes().isNotEmpty() ||
        onAllNodesWithContentDescription(text, substring = substring, ignoreCase = ignoreCase, useUnmergedTree = useUnmergedTree)
            .fetchSemanticsNodes().isNotEmpty()
    // In ReaderChrome the focus-return tag ("reader-focus-tools") wins over "reader-more".
    val more = listOf("reader-more", "reader-focus-tools").firstOrNull { onAllNodesWithTag(it).fetchSemanticsNodes().isNotEmpty() }
    if (!present && more != null && onAllNodesWithText("Okuma araçları").fetchSemanticsNodes().isEmpty()) {
        onNodeWithTag(more).performClick()
        waitForIdle()
    } else if (!present && more == null &&
        onAllNodesWithText("Ayrıntılar ve yeniden dene").fetchSemanticsNodes().isNotEmpty()) {
        // Hidden controls: a persistent notice is the way into the full recovery panel.
        onNodeWithText("Ayrıntılar ve yeniden dene").performClick()
        waitForIdle()
    }
    // Icon-only bar actions (Önceki, Sonraki, …) carry their label as a content description.
    if (onAllNodesWithText(text, substring = substring, ignoreCase = ignoreCase, useUnmergedTree = useUnmergedTree)
            .fetchSemanticsNodes().isEmpty() &&
        onAllNodesWithContentDescription(text, substring = substring, ignoreCase = ignoreCase, useUnmergedTree = useUnmergedTree)
            .fetchSemanticsNodes().isNotEmpty())
        return onNodeWithContentDescription(text, substring = substring, ignoreCase = ignoreCase, useUnmergedTree = useUnmergedTree)
    return onNodeWithText(text, substring = substring, ignoreCase = ignoreCase, useUnmergedTree = useUnmergedTree)
}
