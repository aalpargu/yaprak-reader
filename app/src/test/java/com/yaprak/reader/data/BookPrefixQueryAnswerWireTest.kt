package com.yaprak.reader.data

import android.app.Application
import com.yaprak.reader.data.assistant.*
import com.yaprak.reader.domain.assistant.*
import com.yaprak.reader.domain.search.*
import com.yaprak.reader.fixtures.AssistantFixtures as F
import com.yaprak.reader.fixtures.PrefixAssistantFixtures as P
import com.yaprak.reader.fixtures.PrefixQueryAssistantFixtures as Q
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
class BookPrefixQueryAnswerWireTest {
    @Test fun onlyExactClippedMatchingTextCrossesWireAndFutureQuotesRemainInvalid() {
        val request=BookQuestionRequest.create(Q.sources(),"Soru")
        val raw=bookQuestionBytes(request).toString(Charsets.UTF_8);val root=JSONObject(raw)
        assertEquals(setOf("request_id","question","sources"),root.keys().asSequence().toSet())
        val source=root.getJSONArray("sources").getJSONObject(0)
        assertEquals(setOf("id","text"),source.keys().asSequence().toSet())
        assertEquals(P.reference.text,source.getString("text"));assertFalse(raw.contains("LATER_SECRET"))
        assertFalse(raw.contains(P.chapter.title));assertFalse(raw.contains(F.book.id));assertFalse(raw.contains(P.beginning.resourceHref))
        assertFalse(raw.contains(P.chapter.tocSha256));assertFalse(raw.contains(P.generation))
        parseBookAnswer(request,F.response(request.sources.requestId).toByteArray())
        val invalid=F.response(request.sources.requestId).replace("Güneş doğdu.","LATER_SECRET")
        try {parseBookAnswer(request,invalid.toByteArray());fail("Future quote admitted")}
        catch(failure: BookAnswerException){assertEquals(BookAnswerFailure.INVALID_RESPONSE,failure.reason)}
    }
    @Test fun aLargePrefixQueryRetainsEightSourceWireContractInsteadOfCompleteSixtyFourAllowance() {
        fun sources(count: Int): PreparedBookSources {
            val refs=(0 until count).map { index->
                val text="字".repeat(1980)+" needle"
                BookSourceReference("S"+(index+1),P.hit.copy(id=index+1L,chunkIndex=index,text=text),BookSourceExcerpt(index*2000,0,text.length))
            }
            val chapter=P.chapter.copy(end=P.beginning.copy(textOffset=130_000))
            return PreparedBookSources(UUID.randomUUID().toString(),F.scope,"needle",65,refs,
                prefixQuery=BookSourcePrefixQuery(chapter,P.beginning,65))
        }
        val request=BookQuestionRequest.create(sources(8),"Soru")
        val bytes=bookQuestionBytes(request)
        assertTrue(request.sources.truncated);assertTrue(bytes.size<=MAX_BOOK_REQUEST_BYTES)
        assertEquals(8,JSONObject(bytes.toString(Charsets.UTF_8)).getJSONArray("sources").length())
        for(count in listOf(9,64)) {
            try {BookQuestionRequest.create(sources(count),"Soru");fail("Complete allowance leaked into prefix query")}
            catch(failure: BookAnswerException){assertEquals(BookAnswerFailure.SOURCE,failure.reason)}
        }
    }
}
