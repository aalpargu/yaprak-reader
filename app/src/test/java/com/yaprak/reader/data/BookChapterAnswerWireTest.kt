package com.yaprak.reader.data

import android.app.Application
import com.yaprak.reader.data.assistant.*
import com.yaprak.reader.domain.assistant.*
import com.yaprak.reader.fixtures.AssistantFixtures as F
import com.yaprak.reader.fixtures.ChapterAssistantFixtures as C
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class BookChapterAnswerWireTest {
    @Test fun chapterWireContainsOnlyPreviewedExcerptAndNoScopeMetadataOrExcludedText() {
        val request = BookQuestionRequest.create(C.sources(), "Özetle")
        val bytes = bookQuestionBytes(request)
        val root = JSONObject(bytes.toString(Charsets.UTF_8))
        assertEquals(setOf("request_id", "question", "sources"), root.keys().asSequence().toSet())
        val source = root.getJSONArray("sources").getJSONObject(0)
        assertEquals(setOf("id", "text"), source.keys().asSequence().toSet())
        assertEquals("Güneş doğdu.", source.getString("text"))
        val wire = bytes.toString(Charsets.UTF_8)
        for (excluded in listOf("BEFORE", "AFTER", C.chapter.title, C.chapter.tocSha256, C.hit.target.resourceHref,
            C.hit.target.contentSha256, C.hit.book.title, "textOffset", "excerpt")) assertFalse(excluded, wire.contains(excluded))
    }

    @Test fun exactExcerptQuoteParsesToTheOriginalRequestReferenceAndOriginalNavigationHit() {
        val request = BookQuestionRequest.create(C.sources(), "Özetle")
        val result = parseBookAnswer(request, F.response(request.sources.requestId).toByteArray())
        validateBookAnswer(request, result)
        val citation = result.statements.single().citations.single()
        assertSame(request.sources.references.single(), citation.source)
        assertEquals(C.hit, citation.source.hit); assertNotEquals(citation.source.text, citation.source.hit.text)
    }

    @Test fun quoteOutsideTheExcerptRefusesEvenWhenPresentInTheOriginalIndexedHit() {
        val request = BookQuestionRequest.create(C.sources(), "Özetle")
        val root = JSONObject(F.response(request.sources.requestId))
        root.getJSONArray("statements").getJSONObject(0).getJSONArray("citations").getJSONObject(0).put("quote", "BEFORE")
        try { parseBookAnswer(request, root.toString().toByteArray()); fail("Excluded quote was accepted") }
        catch (failure: BookAnswerException) { assertEquals(BookAnswerFailure.INVALID_RESPONSE, failure.reason) }
    }
}
