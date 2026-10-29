package com.yaprak.reader.fixtures

import com.yaprak.reader.domain.search.*
import java.util.UUID

object PrefixQueryAssistantFixtures {
    fun sources(query: String = "Güneş", selected: BookSourceChapter = PrefixAssistantFixtures.chapter,
        slice: BookSourceExcerpt = BookSourceExcerpt(0,0,PrefixAssistantFixtures.until),
        references: List<BookSourceReference> = listOf(PrefixAssistantFixtures.reference.copy(excerpt=slice)),
        total: Int = references.size, candidates: Int = 65) =
        PreparedBookSources(UUID.randomUUID().toString(),AssistantFixtures.scope,query,total,references,
            prefixQuery=BookSourcePrefixQuery(selected,PrefixAssistantFixtures.beginning,candidates))
}
