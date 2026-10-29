package com.yaprak.reader.data

import android.app.Application
import com.yaprak.reader.data.assistant.*
import com.yaprak.reader.domain.assistant.*
import com.yaprak.reader.domain.search.*
import com.yaprak.reader.fixtures.AssistantFixtures as F
import com.yaprak.reader.fixtures.PrefixAssistantFixtures as P
import java.util.UUID
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],application=Application::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class BookPrefixAnswerWireTest {
    @Test fun wireContainsOnlyAdmittedPrefixSlicesAndExcludesLaterOriginalTextAndBoundaryMetadata() {
        val request=BookQuestionRequest.create(P.sources(),"Soru")
        val raw=bookQuestionBytes(request).toString(Charsets.UTF_8);val root=JSONObject(raw)
        assertEquals(setOf("request_id","question","sources"),root.keys().asSequence().toSet())
        val source=root.getJSONArray("sources").getJSONObject(0)
        assertEquals(setOf("id","text"),source.keys().asSequence().toSet())
        assertEquals(P.reference.text,source.getString("text"));assertFalse(raw.contains("LATER_SECRET"))
        assertFalse(raw.contains(P.chapter.title));assertFalse(raw.contains(F.book.id));assertFalse(raw.contains(P.beginning.resourceHref))
        assertFalse(raw.contains(P.chapter.tocSha256));assertFalse(raw.contains(P.generation))
    }
    @Test fun sixtyFourCompleteUnicodePiecesUseCompleteAllowanceAndSixtyFiveFailsWithoutTruncation() {
        fun sources(count: Int): PreparedBookSources {
            val refs=(0 until count).map { index->
                val text="字".repeat(1990)+" $index"
                BookSourceReference("S"+(index+1),P.hit.copy(id=index+1L,chunkIndex=index,text=text),BookSourceExcerpt(index*2000,0,text.length))
            }
            val chapter=P.chapter.copy(end=P.beginning.copy(textOffset=count*2000),sourceCount=count)
            return PreparedBookSources(UUID.randomUUID().toString(),F.scope,"",count,refs,prefix=BookSourcePrefix(chapter,P.beginning,count))
        }
        val bytes=bookQuestionBytes(BookQuestionRequest.create(sources(64),"Özetle"))
        assertTrue(bytes.size>MAX_BOOK_REQUEST_BYTES);assertTrue(bytes.size<=MAX_COMPLETE_BOOK_REQUEST_BYTES)
        assertEquals(64,JSONObject(bytes.toString(Charsets.UTF_8)).getJSONArray("sources").length())
        try { BookQuestionRequest.create(sources(65),"Soru");fail("Incomplete prefix allowed") }
        catch(failure: BookAnswerException) { assertEquals(BookAnswerFailure.SOURCE,failure.reason) }
    }
}
