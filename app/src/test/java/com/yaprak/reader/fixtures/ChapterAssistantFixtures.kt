package com.yaprak.reader.fixtures

import com.yaprak.reader.domain.search.*
import java.util.UUID

object ChapterAssistantFixtures {
    val raw = "BEFORE 🌿Güneş doğdu.🌿 AFTER"
    val from = raw.indexOf("Güneş")
    val until = raw.indexOf("🌿 AFTER")
    val generation: String = UUID.randomUUID().toString()
    val hit = AssistantFixtures.hit.copy(generation = generation, text = raw)
    val boundary = BookChapterBoundary(0, hit.target.resourceHref, hit.target.resourceSha256, from)
    val chapter = BookSourceChapter(AssistantFixtures.scope, generation, "c".repeat(64), 0, "Gerçek bölüm başlığı", 0,
        boundary, boundary.copy(textOffset = until), 1)
    fun sources(selected: BookSourceChapter = chapter, original: LibraryTextHit = hit,
        slice: BookSourceExcerpt = BookSourceExcerpt(0, from, until)) =
        PreparedBookSources(UUID.randomUUID().toString(), AssistantFixtures.scope, "", 1,
            listOf(BookSourceReference("S1", original, slice)), chapter = selected)
}
