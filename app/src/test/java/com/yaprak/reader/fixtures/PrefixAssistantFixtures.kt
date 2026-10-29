package com.yaprak.reader.fixtures

import com.yaprak.reader.domain.search.*
import java.util.UUID

object PrefixAssistantFixtures {
    const val raw="Giriş. Güneş doğdu. LATER_SECRET"
    val until=raw.indexOf(" LATER_SECRET")
    const val from=0
    val generation=UUID.randomUUID().toString()
    val hit=AssistantFixtures.hit.copy(generation=generation,text=raw)
    val beginning=BookChapterBoundary(0,hit.target.resourceHref,hit.target.resourceSha256,0)
    val chapter=BookSourceChapter(AssistantFixtures.scope,generation,"c".repeat(64),0,"Seçilen sınır",0,
        beginning.copy(textOffset=raw.indexOf("Güneş")),beginning.copy(textOffset=until),1)
    val prefix=BookSourcePrefix(chapter,beginning,1)
    val reference=BookSourceReference("S1",hit,BookSourceExcerpt(0,0,until))
    fun sources(selected: BookSourcePrefix=prefix,refs: List<BookSourceReference> = listOf(reference))=
        PreparedBookSources(UUID.randomUUID().toString(),AssistantFixtures.scope,"",refs.size,refs,prefix=selected)
    fun chapterSources(selected: BookSourceChapter=chapter,slice: BookSourceExcerpt=BookSourceExcerpt(0,0,until))=
        sources(prefix.copy(throughChapter=selected),listOf(reference.copy(excerpt=slice)))
}
