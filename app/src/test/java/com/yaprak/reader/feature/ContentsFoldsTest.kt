package com.yaprak.reader.feature

import com.yaprak.reader.domain.reader.ContentsEntry
import com.yaprak.reader.feature.reader.contentsBranchIds
import com.yaprak.reader.feature.reader.visibleContentsForFolds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentsFoldsTest {
    private val contents = listOf(
        ContentsEntry("part", "Birinci kısım", 0, false),
        ContentsEntry("chapter", "Bölüm", 1, true),
        ContentsEntry("section", "Alt bölüm", 2, true),
        ContentsEntry("deep", "Ayrıntı", 3, true),
        ContentsEntry("other-section", "Başka alt bölüm", 2, true),
        ContentsEntry("other-chapter", "Başka bölüm", 1, true),
        ContentsEntry("second-part", "İkinci kısım", 0, true),
        ContentsEntry("last-chapter", "Son bölüm", 1, true),
        ContentsEntry("end", "Sonsöz", 0, true),
    )

    @Test fun `only headings with a following deeper entry expose a branch`() {
        assertEquals(setOf("part", "chapter", "section", "second-part"), contentsBranchIds(contents))
        assertTrue(contentsBranchIds(emptyList()).isEmpty())
        assertTrue(contentsBranchIds(listOf(contents.last())).isEmpty())
        assertSame(contents, visibleContentsForFolds(contents, emptySet()))
    }

    @Test fun `folding a parent hides every descendant and ends at its sibling`() {
        val visible = visibleContentsForFolds(contents, setOf("part"))
        assertEquals(listOf("part", "second-part", "last-chapter", "end"), visible.map { it.id })
        assertSame(contents[0], visible[0])
        assertSame(contents[6], visible[1])
        assertFalse(visible[0].navigable)
        assertEquals("Birinci kısım", visible[0].title)
        assertEquals(0, visible[0].depth)
    }

    @Test fun `nested fold keeps sibling chapters and parts visible`() {
        assertEquals(listOf("part", "chapter", "other-chapter", "second-part", "last-chapter", "end"),
            visibleContentsForFolds(contents, setOf("chapter")).map { it.id })
        assertEquals(listOf("part", "chapter", "section", "other-section", "other-chapter",
            "second-part", "last-chapter", "end"),
            visibleContentsForFolds(contents, setOf("section")).map { it.id })
    }

    @Test fun `opening an ancestor restores the child fold chosen before it closed`() {
        val selected = setOf("part", "chapter")
        assertEquals(listOf("part", "second-part", "last-chapter", "end"),
            visibleContentsForFolds(contents, selected).map { it.id })
        assertEquals(listOf("part", "chapter", "other-chapter", "second-part", "last-chapter", "end"),
            visibleContentsForFolds(contents, selected - "part").map { it.id })
        assertEquals(setOf("part", "chapter"), selected)
        assertSame(contents, visibleContentsForFolds(contents, selected - "part" - "chapter"))
    }

    @Test fun `multiple folds preserve each original identity and never mutate source entries`() {
        val original = contents.toList()
        val visible = visibleContentsForFolds(contents, setOf("section", "second-part"))
        assertEquals(listOf("part", "chapter", "section", "other-section", "other-chapter", "second-part", "end"),
            visible.map { it.id })
        visible.forEach { entry -> assertSame(contents.first { it.id == entry.id }, entry) }
        assertEquals(original, contents)
    }

    @Test fun `unknown and leaf ids are ignored even beside a deeper unrelated branch`() {
        val leaf = ContentsEntry("leaf", "Önsöz", 0, true)
        val branch = ContentsEntry("branch", "Kısım", 0, false)
        val child = ContentsEntry("child", "Bölüm", 1, true)
        val supplied = listOf(leaf, branch, child)
        assertSame(supplied, visibleContentsForFolds(supplied, setOf("leaf", "child", "another-book")))
        assertEquals(listOf(leaf, branch), visibleContentsForFolds(supplied, setOf("branch", "another-book")))
    }

    @Test fun `a truncated final entry has no inferred descendants outside the supplied list`() {
        val supplied = listOf(
            ContentsEntry("parent", "Kısım", 0, false),
            ContentsEntry("last", "Son görünen başlık", 1, false),
        )
        assertEquals(setOf("parent"), contentsBranchIds(supplied))
        assertSame(supplied, visibleContentsForFolds(supplied, setOf("last")))
        assertEquals(listOf(supplied[0]), visibleContentsForFolds(supplied, setOf("parent")))
    }

    @Test fun `five thousand nested headings fold without recursion and retain their full depths`() {
        val deep = (0 until 5_000).map { index ->
            ContentsEntry("node-$index", "Başlık $index", index, index % 2 == 0)
        }
        assertEquals(4_999, contentsBranchIds(deep).size)
        assertEquals(listOf(deep.first()), visibleContentsForFolds(deep, setOf("node-0")))
        val middle = visibleContentsForFolds(deep, setOf("node-2500"))
        assertEquals(2_501, middle.size)
        assertSame(deep[2500], middle.last())
        assertEquals(2500, middle.last().depth)
        assertSame(deep, visibleContentsForFolds(deep, emptySet()))
    }
}
