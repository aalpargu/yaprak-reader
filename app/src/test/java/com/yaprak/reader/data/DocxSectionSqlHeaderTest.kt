package com.yaprak.reader.data

import android.app.Application
import android.database.MatrixCursor
import androidx.sqlite.db.SupportSQLiteDatabase
import com.yaprak.reader.data.search.*
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.domain.search.*
import com.yaprak.reader.fixtures.AssistantFixtures as F
import com.yaprak.reader.formats.docx.DOCX_INDEX_HREF
import com.yaprak.reader.formats.docx.DOCX_INDEX_TITLE
import java.lang.reflect.Proxy
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Actual production header decoder with an owned MatrixCursor; real SQLite is tested on Android. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],application=Application::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class DocxSectionSqlHeaderTest {
    private val book=F.book.copy(format=BookFormat.DOCX)
    private val generation=UUID.randomUUID().toString()
    private val columns=arrayOf("bookId","contentSha256","generation","readingOrderIndex","chunkIndex","resourceHref","resourceSha256","chapterTitle","sourceFormat","pdfPageIndex")
    private fun row(index: Int)=arrayOf<Any?>(book.id,book.sha256,generation,0,index,DOCX_INDEX_HREF,"b".repeat(64),DOCX_INDEX_TITLE,BookFormat.DOCX.name,null)
    private fun headers(rows: List<Array<Any?>>,declared: Int=rows.size,format: BookFormat=BookFormat.DOCX): List<BookSourceSection> {
        val database=Proxy.newProxyInstance(SupportSQLiteDatabase::class.java.classLoader,arrayOf(SupportSQLiteDatabase::class.java)) { _,method,_->
            if(method.name!="query") error("Only bounded owned cursor reads are allowed")
            MatrixCursor(columns).apply { rows.forEach { addRow(it) } }
        } as SupportSQLiteDatabase
        val current=book.copy(format=format)
        val ledger=TextIndexBookEntity(book.id,book.sha256,1,generation,LibraryTextBookState.READY.name,declared,10_000,format.name)
        return TextIndexSql(database) {}.sections(current,ledger)
    }
    private fun refused(rows: List<Array<Any?>>,declared: Int=rows.size) {
        try { headers(rows,declared);fail("Corrupt or incomplete header accepted") }
        catch(failure: LibraryTextIndexException) { assertEquals(LibraryTextIndexFailure.SOURCE,failure.reason) }
    }
    @Test fun multiChunkDocxIsOneCompleteDocumentStartingAtZeroRatherThanASequenceOfInvalidDocumentStarts() {
        val sections=headers((0 until 3).map(::row))
        val section=sections.single()
        assertEquals(0,section.firstChunkIndex);assertEquals(3,section.chunkCount)
        assertEquals(BookFormat.DOCX,section.sourceFormat);assertEquals(DOCX_INDEX_HREF,section.resourceHref)
        assertEquals(DOCX_INDEX_TITLE,section.chapterTitle);assertTrue(section.fitsSingleRequest)
        assertTrue(validBookSourceSections(F.scope,sections))
    }
    @Test fun aSixtyFiveChunkDocumentRemainsCompleteAndExplicitlyTooLargeWithoutCatalogTruncation() {
        val section=headers((0 until 65).map(::row)).single()
        assertEquals(0,section.firstChunkIndex);assertEquals(65,section.chunkCount)
        assertTrue(section.isValid);assertFalse(section.fitsSingleRequest)
    }
    @Test fun laterDocxRowsStillProveEveryIndexScopeGenerationCanonicalResourceTitleFormatAndPage() {
        for((column,value) in listOf(0 to "foreign",1 to "d".repeat(64),2 to UUID.randomUUID().toString(),
            3 to 1,4 to 2,5 to "EPUB/invented.xhtml",6 to "c".repeat(64),7 to "Fake Word chapter",8 to BookFormat.EPUB.name,9 to 0)) {
            val later=row(1).apply { this[column]=value };refused(listOf(row(0),later))
        }
        refused(listOf(row(1)),1);refused(listOf(row(0)),2)
    }
    @Test fun ordinaryEpubResourcesRetainTheirActualGlobalFirstChunkAndContiguousCounts() {
        val rows=(0 until 3).map { index->row(index).apply {
            this[3]=if(index<2) 0 else 1
            this[5]=if(index<2) "EPUB/a.xhtml" else "EPUB/b.xhtml"
            this[6]=if(index<2) "b".repeat(64) else "c".repeat(64)
            this[7]=if(index<2) "A" else "B";this[8]=BookFormat.EPUB.name
        } }
        val sections=headers(rows,format=BookFormat.EPUB)
        assertEquals(listOf(0,2),sections.map { it.firstChunkIndex });assertEquals(listOf(2,1),sections.map { it.chunkCount })
        assertTrue(validBookSourceSections(F.scope,sections))
    }
}
