package com.yaprak.reader.feature

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeTestRule

/**
 * Library cards open the book on tap; details live behind each card's ⋮ ("<title> ayrıntıları") button.
 * Opens them through the real control, like a reader would.
 */
internal fun ComposeTestRule.openBookDetails(title: String? = null, card: String? = null) {
    var matcher = if (title != null) hasContentDescription("$title ayrıntıları")
        else hasContentDescription(" ayrıntıları", substring = true)
    // Same-titled books: pick the ⋮ inside the card with this accessible name.
    if (card != null) matcher = matcher and hasAnyAncestor(hasContentDescription(card))
    val nodes = onAllNodes(matcher, useUnmergedTree = true)
    if (nodes.fetchSemanticsNodes().isEmpty()) onNode(hasScrollToIndexAction()).performScrollToNode(matcher)
    nodes.onFirst().performClick()
    waitForIdle()
}

/** The search field opens from the toolbar's search icon; its label disappears once text is typed. */
internal fun ComposeTestRule.librarySearchField(): SemanticsNodeInteraction {
    if (onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isEmpty())
        onNodeWithContentDescription("Kitaplıkta ara").performClick()
    return onNode(hasSetTextAction())
}

/** Backup, folder discovery and other library-wide tools sit in the "Kitaplık araçları" dialog. */
internal fun ComposeTestRule.libraryToolNodeWithText(text: String): SemanticsNodeInteraction {
    openLibraryToolsIfMissing { onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    return onNodeWithText(text)
}

internal fun ComposeTestRule.libraryToolNodeWithTag(tag: String): SemanticsNodeInteraction {
    openLibraryToolsIfMissing { onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    return onNodeWithTag(tag)
}

private fun ComposeTestRule.openLibraryToolsIfMissing(present: () -> Boolean) {
    if (!present() && onAllNodesWithContentDescription("Kitaplık araçları").fetchSemanticsNodes().isNotEmpty()) {
        onNodeWithContentDescription("Kitaplık araçları").performClick()
        waitForIdle()
    }
}
