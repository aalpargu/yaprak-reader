package com.yaprak.reader.reader

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert

/** Compare every input character, independently of labels and transient IME styling. */
internal fun SemanticsNodeInteraction.assertEditableTextExactly(expected: String): SemanticsNodeInteraction =
    assert(SemanticsMatcher("EditableText.text exactly equals '$expected'") { node ->
        node.config.getOrNull(SemanticsProperties.EditableText)?.text == expected
    })
