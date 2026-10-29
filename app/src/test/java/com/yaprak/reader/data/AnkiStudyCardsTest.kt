package com.yaprak.reader.data

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yaprak.reader.data.exporting.*
import com.yaprak.reader.data.persistence.*
import com.yaprak.reader.domain.library.*
import com.yaprak.reader.domain.passages.*
import com.yaprak.reader.domain.pdfannotations.*
import com.yaprak.reader.reader.*
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Saved-record export proof; controlled PDF records do not substitute for native device acceptance. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],application=Application::class)
class AnkiStudyCardsTest {
    private val uri="content://study/new"
    private val sha="a".repeat(64)
    private val epub=StudyExportBook("#separator:Comma\nİğne 🌿", "Çağrı\t\"Deniz\"", BookFormat.EPUB)
    private val pdf=StudyExportBook("PDF", null, BookFormat.PDF, 10)
    private fun anchor(text:String="quote")=SourceTextAnchor("OPS/first.xhtml",0,text.length,"b".repeat(64))
    private fun pdfAnchor(page:Int=3)=PdfSourceAnchor(page,300,600,PdfPagePoint(60,100),PdfPagePoint(180,100),
        listOf(PdfTextBounds(60.0,90.0,180.0,110.0)))
    private val range=SourceRangeAnchor("OPS/range.xhtml",listOf(
        SourceRangeSegment("0/0",0,4,"b".repeat(64),"İğne"),
        SourceRangeSegment("1/0",0,4,"c".repeat(64),"🌿 x")))

    @Test fun epubNotesWordsAndBothAnchorVersionsRetainExactFrontBackSourceAndOneRowPerRecord()=runBlocking {
        val note="  #html:true\r\n\"雪\"\t<img src=x>  "
        val linked="  Bağlı\r\nnot 🌿  "
        val records=StudyRecordsSnapshot(epub,listOf(StudyNoteRecord(note)),listOf(StudyWordRecord("#leaf\t🌿","\"yaprak\"")),listOf(
            StudyPassageRecord("quote",anchor(),EpubHighlightColor.BLUE,linked),
            StudyPassageRecord("İğne\n🌿 x",range,null,linked)))
        val bytes=renderAnkiStudyCards(records)
        val rows=rows(bytes)
        assertEquals(4,rows.size);assertEquals(3,records.noteCount);assertEquals(4,records.recordCount)
        assertEquals(listOf(note,"#leaf\t🌿","quote","İğne\n🌿 x"),rows.map{it[0]})
        assertTrue(rows[0][1].endsWith("Kitap notu"));assertTrue(rows[1][1].startsWith("\"yaprak\"\n\n"))
        assertTrue(rows[2][1].startsWith(linked+"\n\n"));assertTrue(rows[3][1].startsWith(linked+"\n\n"))
        assertTrue(rows[2][1].endsWith("Kaynak EPUB dosyası: OPS/first.xhtml\nVurgu rengi: Mavi"))
        assertTrue(rows[3][1].endsWith("Kaynak EPUB dosyası: OPS/range.xhtml"))
        rows.forEach{assertTrue(it[1].contains("Kitap: ${epub.title}\nYazar: ${epub.authors}\nBiçim: EPUB"))}
        assertFalse(bytes.toString(Charsets.UTF_8).contains("#guid column"))
    }

    @Test fun pdfColorsNoteOnlyAndStandalonePageNotesUseActualOneBasedSourcePages()=runBlocking {
        val annotations=PdfHighlightColor.entries.mapIndexed { page,color ->
            StudyPdfAnnotationRecord("  same\n🌿  ",pdfAnchor(page),color,if(page==0) "own note" else null)
        }+StudyPdfAnnotationRecord("note-only",pdfAnchor(4),null,"note")
        val records=StudyRecordsSnapshot(pdf,listOf(StudyNoteRecord("page note",9),StudyNoteRecord("book note")),
            pdfAnnotations=annotations)
        val rows=rows(renderAnkiStudyCards(records))
        assertEquals(7,rows.size);assertEquals(4,records.noteCount);assertEquals(5,records.passageCount)
        assertTrue(rows[0][1].endsWith("PDF sayfası: 10"));assertTrue(rows[1][1].endsWith("Kitap notu"))
        for((index,color) in listOf("Sarı","Yeşil","Mavi","Pembe").withIndex()) {
            assertEquals("  same\n🌿  ",rows[index+2][0])
            assertTrue(rows[index+2][1].endsWith("PDF sayfası: ${index+1}\nVurgu rengi: $color"))
        }
        assertTrue(rows[6][1].startsWith("note\n\n"));assertTrue(rows[6][1].endsWith("PDF sayfası: 5"))
        assertFalse(rows[6][1].contains("Vurgu rengi"));assertTrue(rows.all{it[1].contains("Yazar: Bilinmiyor")})
    }

    @Test fun quotedMultilineLiteralFieldsCannotBecomeHeadersExtraColumnsHtmlOrAdditionalCards()=runBlocking {
        val values=listOf("#columns:Bad\tInject", "\"q\"\r\nline\n", "  <script>🌿</script>\t雪 & x  ","A\u2028B")
        val records=StudyRecordsSnapshot(epub,values.map{StudyNoteRecord(it)})
        assertEquals(values,rows(renderAnkiStudyCards(records)).map{it[0]})
        assertTrue(rows(renderAnkiStudyCards(records)).all{it.size==2})
    }

    @Test fun identicalFrontTextInDifferentSourcesRemainsSeparateExportRows()=runBlocking {
        val records=StudyRecordsSnapshot(pdf,pdfAnnotations=listOf(0,1).map{
            StudyPdfAnnotationRecord("same",pdfAnchor(it),PdfHighlightColor.GREEN,"page $it")})
        val rows=rows(renderAnkiStudyCards(records))
        assertEquals(listOf("same","same"),rows.map{it[0]});assertNotEquals(rows[0][1],rows[1][1])
        assertTrue(rows[0][1].contains("PDF sayfası: 1"));assertTrue(rows[1][1].contains("PDF sayfası: 2"))
    }

    @Test fun malformedDetachedSourcesCannotProducePartialAnkiBytes()=runBlocking {
        val cases=listOf(
            StudyRecordsSnapshot(epub,listOf(StudyNoteRecord("bad\uD800"))),
            StudyRecordsSnapshot(epub,listOf(StudyNoteRecord("bad\u0000"))),
            StudyRecordsSnapshot(epub.copy(title="bad\u0000"),listOf(StudyNoteRecord("note"))),
            StudyRecordsSnapshot(epub,passages=listOf(StudyPassageRecord("quote",anchor(),null,"bad\u0000"))),
            StudyRecordsSnapshot(epub,words=listOf(StudyWordRecord("leaf\nmore","meaning"))),
            StudyRecordsSnapshot(epub,passages=listOf(StudyPassageRecord("quote",anchor().copy(resourceHref="https://example.test/x")))),
            StudyRecordsSnapshot(epub,pdfAnnotations=listOf(StudyPdfAnnotationRecord("quote",pdfAnchor(),PdfHighlightColor.YELLOW,null))),
            StudyRecordsSnapshot(pdf,passages=listOf(StudyPassageRecord("quote",anchor()))),
            StudyRecordsSnapshot(pdf,pdfAnnotations=listOf(StudyPdfAnnotationRecord("quote",pdfAnchor(10),PdfHighlightColor.YELLOW,null))),
        )
        for(records in cases) assertFailure(StudyExportFailure.IO){renderAnkiStudyCards(records)}
        assertFailure(StudyExportFailure.NO_RECORDS){renderAnkiStudyCards(StudyRecordsSnapshot(epub))}
    }

    @Test fun sourceUtf8AndRepeatedMetadataOutputHaveIndependentBounds()=runBlocking {
        val source=StudyRecordsSnapshot(epub,List(200){StudyNoteRecord("雪".repeat(2000))})
        assertFailure(StudyExportFailure.TOO_LARGE){renderAnkiStudyCards(source)}
        val output=StudyRecordsSnapshot(StudyExportBook("雪".repeat(1000),"雪".repeat(2000),BookFormat.EPUB),
            List(1000){StudyNoteRecord("n")})
        validateStudyRecordsSnapshot(output) // Source is small; per-card metadata exceeds the output budget.
        assertFailure(StudyExportFailure.TOO_LARGE){renderAnkiStudyCards(output)}
    }

    @Test fun exactlyOneThousandSavedRecordsExportWhileTheNextRecordIsRejected()=runBlocking {
        assertEquals(1000,rows(renderAnkiStudyCards(StudyRecordsSnapshot(epub,List(1000){StudyNoteRecord("note $it")}))).size)
        assertFailure(StudyExportFailure.TOO_LARGE){StudyRecordsSnapshot(epub,List(1001){StudyNoteRecord("n")})}
    }

    @Test fun actualRoomEpubSnapshotIncludesLegacyRangeAndOwnNotesButExcludesOtherContent()=runBlocking {
        database(BookFormat.EPUB){db,book ->
            db.bookNotes().add(BookNoteEntity("note",book.id,sha,"  own\n🌿  ",1))
            db.savedWords().add(SavedWordEntity("word",book.id,sha,"leaf","yaprak",2))
            db.bookNotes().add(BookNoteEntity("stale",book.id,"c".repeat(64),"NOT CURRENT",3))
            db.epubPassages().add(EpubPassageEntity("passage",book.id,sha,1,"OPS/first.xhtml","b".repeat(64),0,5,"quote",4,"PINK","linked",2))
            db.openHelper.writableDatabase.execSQL("INSERT INTO epub_range_passages VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                arrayOf<Any?>("range",book.id,sha,2,range.resourceHref,EpubRangeAnchorCodec.encode(range),EpubRangeAnchorCodec.key(range),
                    "İğne\n🌿 x",5,null,"range note",3))
            val docs=Documents()
            val result=exporter(RoomBookStudySource(db),docs).export(book.id,sha,uri,StudyExportFormat.ANKI_STUDY_TSV)
            assertEquals(StudyExportResult.Exported(3,1,2,4),result)
            val rows=rows(docs.bytes.toByteArray());assertEquals(4,rows.size)
            assertEquals(setOf("  own\n🌿  ","leaf","quote","İğne\n🌿 x"),rows.map{it[0]}.toSet())
            assertFalse(docs.bytes.toString("UTF-8").contains("NOT CURRENT"));assertEquals(1,docs.opens);assertEquals(1,docs.closes)
        }
    }

    @Test fun actualRoomPdfNoteOnlyAndHighlightProduceThreeRowsRatherThanDoubleCountingAttachedNotes()=runBlocking {
        database(BookFormat.PDF){db,book ->
            db.bookNotes().add(BookNoteEntity("note",book.id,sha,"page note",1,9))
            val repo=RoomPdfAnnotationRepository(db,PdfAnnotationOriginalSource{_,_,_->},Mutex())
            repo.create(book.id,sha,"quote",pdfAnchor(),PdfHighlightColor.BLUE,"attached")
            repo.create(book.id,sha,"only note",pdfAnchor(4),null,"other note")
            val docs=Documents()
            assertEquals(StudyExportResult.Exported(3,0,2,3),
                exporter(RoomBookStudySource(db),docs).export(book.id,sha,uri,StudyExportFormat.ANKI_STUDY_TSV))
            assertEquals(3,rows(docs.bytes.toByteArray()).size)
            // The separate old format is still word-only, including its no-records behavior.
            val old=Documents()
            assertEquals(StudyExportResult.Failed(StudyExportFailure.NO_RECORDS),
                exporter(RoomBookStudySource(db),old).export(book.id,sha,uri,StudyExportFormat.ANKI_TSV))
            assertEquals(0,old.opens)
        }
    }

    @Test fun corruptCurrentPdfAnchorFailsBeforeProviderOpenAndDoesNotOmitTheBadRow()=runBlocking {
        database(BookFormat.PDF){db,book ->
            db.bookNotes().add(BookNoteEntity("good",book.id,sha,"valid",1))
            RoomPdfAnnotationRepository(db,PdfAnnotationOriginalSource{_,_,_->},Mutex())
                .create(book.id,sha,"quote",pdfAnchor(),PdfHighlightColor.BLUE,"note")
            db.openHelper.writableDatabase.execSQL("UPDATE pdf_annotations SET anchorKey = ?",arrayOf("c".repeat(64)))
            val docs=Documents()
            assertEquals(StudyExportResult.Failed(StudyExportFailure.IO),
                exporter(RoomBookStudySource(db),docs).export(book.id,sha,uri,StudyExportFormat.ANKI_STUDY_TSV))
            assertEquals(0,docs.opens);assertEquals(1,docs.deletes)
        }
    }

    @Test fun actualRoomChangedHashRecoveryGuardAndClosedDatabaseNeverExportOldSavedRecords()=runBlocking {
        database(BookFormat.EPUB){db,book ->
            db.bookNotes().add(BookNoteEntity("note",book.id,sha,"note",1))
            val guarded=Documents()
            assertEquals(StudyExportResult.Failed(StudyExportFailure.IO),
                exporter(RoomBookStudySource(db,{throw IOException("Recovery required")}),guarded)
                    .export(book.id,sha,uri,StudyExportFormat.ANKI_STUDY_TSV));assertEquals(0,guarded.opens)
            db.openHelper.writableDatabase.execSQL("UPDATE books SET sha256 = ?",arrayOf("d".repeat(64)))
            val changed=Documents()
            assertEquals(StudyExportResult.Failed(StudyExportFailure.CHANGED_BOOK),exporter(RoomBookStudySource(db),changed)
                .export(book.id,sha,uri,StudyExportFormat.ANKI_STUDY_TSV));assertEquals(0,changed.opens)
            db.close()
            val closed=Documents()
            assertEquals(StudyExportResult.Failed(StudyExportFailure.IO),exporter(RoomBookStudySource(db),closed)
                .export(book.id,sha,uri,StudyExportFormat.ANKI_STUDY_TSV));assertEquals(0,closed.opens)
        }
    }

    @Test fun exporterReleasesSnapshotMutexBeforeProviderAndReportsActualRowCount()=runBlocking {
        val mutex=Mutex()
        val records=StudyRecordsSnapshot(epub,passages=listOf(StudyPassageRecord("quote",anchor(),EpubHighlightColor.YELLOW,"note")))
        val docs=Documents(onOpen={assertTrue(mutex.tryLock());mutex.unlock()})
        val source=source{assertTrue(mutex.isLocked);records}
        assertEquals(StudyExportResult.Exported(1,0,1,1),exporter(source,docs,mutex)
            .export("book",sha,uri,StudyExportFormat.ANKI_STUDY_TSV))
        assertArrayEquals(renderAnkiStudyCards(records),docs.bytes.toByteArray());assertEquals(0,docs.deletes)
    }

    @Test fun legacyTextSourceCannotBeParsedIntoAnkiStudyCards()=runBlocking {
        var textReads=0
        val legacy=BookStudySource{_,_->textReads++;BookStudySnapshot("note".toByteArray(),1,0,0)}
        val docs=Documents()
        assertEquals(StudyExportResult.Failed(StudyExportFailure.UNSUPPORTED_FORMAT),exporter(legacy,docs)
            .export("book",sha,uri,StudyExportFormat.ANKI_STUDY_TSV))
        assertEquals(0,textReads);assertEquals(0,docs.opens);assertEquals(1,docs.deletes)
    }

    @Test fun invalidEmptyOrOversizedSourcesAreRejectedBeforeAnyTargetWrite()=runBlocking {
        val cases=listOf(
            source{StudyRecordsSnapshot(epub)} to StudyExportFailure.NO_RECORDS,
            source{StudyRecordsSnapshot(epub,listOf(StudyNoteRecord("bad\u0000")))} to StudyExportFailure.IO,
            source{StudyRecordsSnapshot(epub,List(1001){StudyNoteRecord("n")})} to StudyExportFailure.TOO_LARGE,
            source{StudyRecordsSnapshot(epub,List(200){StudyNoteRecord("雪".repeat(2000))})} to StudyExportFailure.TOO_LARGE,
        )
        for((source,failure) in cases){val docs=Documents();assertEquals(StudyExportResult.Failed(failure),exporter(source,docs)
            .export("book",sha,uri,StudyExportFormat.ANKI_STUDY_TSV));assertEquals(0,docs.opens);assertEquals(1,docs.deletes)}
    }

    @Test fun cancelledSourceReturningLateCannotCreateAFileOrReportSuccess()=runBlocking {
        val started=CompletableDeferred<Unit>();val finish=CompletableDeferred<Unit>();val docs=Documents()
        val export=launch{exporter(source{started.complete(Unit);withContext(NonCancellable){finish.await()};
            StudyRecordsSnapshot(epub,listOf(StudyNoteRecord("late")))},docs)
            .export("book",sha,uri,StudyExportFormat.ANKI_STUDY_TSV);fail("Late source cannot succeed")}
        started.await();export.cancel();finish.complete(Unit);export.join()
        assertEquals(0,docs.opens);assertEquals(1,docs.deletes)
    }

    @Test fun cancellationWaitingForSharedMutexCleansAcceptedTargetWithoutReadingRecords()=runBlocking {
        val mutex=Mutex(locked=true);var reads=0;val docs=Documents()
        val export=launch(start=CoroutineStart.UNDISPATCHED){exporter(source{reads++;error("Must not read")},docs,mutex)
            .export("book",sha,uri,StudyExportFormat.ANKI_STUDY_TSV)}
        export.cancelAndJoin();mutex.unlock();assertEquals(0,reads);assertEquals(0,docs.opens);assertEquals(1,docs.deletes)
    }

    @Test fun providerWriteFailureClosesTargetAndDisclosesUnconfirmedPartialFileCleanup()=runBlocking {
        val docs=Documents(deleteConfirmed=false,failWrite=true)
        assertEquals(StudyExportResult.Failed(StudyExportFailure.IO,partialFileMayRemain=true),
            exporter(source{StudyRecordsSnapshot(epub,listOf(StudyNoteRecord("note")))},docs)
                .export("book",sha,uri,StudyExportFormat.ANKI_STUDY_TSV))
        assertEquals(1,docs.opens);assertEquals(1,docs.closes);assertEquals(1,docs.deletes)
    }

    @Test fun unacceptedUriCannotReadRecordsOpenOrDeleteAnything()=runBlocking {
        val docs=Documents()
        assertEquals(StudyExportResult.Failed(StudyExportFailure.ACCESS),exporter(source{error("No source access")},docs)
            .export("book",sha,"file:///unaccepted",StudyExportFormat.ANKI_STUDY_TSV))
        assertEquals(0,docs.opens);assertEquals(0,docs.deletes)
    }

    private fun source(read:suspend()->StudyRecordsSnapshot)=object:BookStudySource {
        override suspend fun snapshot(bookId:String,contentSha256:String):BookStudySnapshot=error("Never parse TXT")
        override suspend fun snapshotRecords(bookId:String,contentSha256:String)=read()
    }
    private fun exporter(source:BookStudySource,docs:Documents,mutex:Mutex=Mutex())=
        DefaultBookStudyExporter(source,docs,Dispatchers.Unconfined,mutex)
    private suspend fun database(format:BookFormat,block:suspend(LibraryDatabase,BookEntity)->Unit) {
        val db=Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Application>(),LibraryDatabase::class.java).build()
        val book=BookEntity("book","Book","Author",format.name,"book.${format.extension}","book.${format.extension}",100,sha,1,
            if(format==BookFormat.PDF)10 else null)
        try{db.books().insert(book);block(db,book)}finally{db.close()}
    }
    private suspend fun assertFailure(expected:StudyExportFailure,block:suspend()->Any){
        val error=runCatching{block()}.exceptionOrNull();assertTrue(error.toString(),error is StudyExportException)
        assertEquals(expected,(error as StudyExportException).reason)
    }
    private inner class Documents(val deleteConfirmed:Boolean=true,val failWrite:Boolean=false,val onOpen:()->Unit={}):ExportDocumentProvider {
        val bytes=ByteArrayOutputStream();var opens=0;var closes=0;var deletes=0
        override fun accepts(uri:String)=uri==this@AnkiStudyCardsTest.uri
        override fun open(uri:String):OutputStream{opens++;onOpen();return object:OutputStream(){
            override fun write(value:Int){if(failWrite)throw IOException("Write failed");bytes.write(value)}
            override fun write(buffer:ByteArray,offset:Int,length:Int){if(failWrite)throw IOException("Write failed");bytes.write(buffer,offset,length)}
            override fun close(){closes++}
        }}
        override fun delete(uri:String):Boolean{deletes++;return deleteConfirmed}
    }
    /** Independent CSV parser: supports TAB delimiters, CR/LF inside quotes and doubled quotes. */
    private fun rows(bytes:ByteArray):List<List<String>> {
        val text=bytes.toString(Charsets.UTF_8)
        val header="#separator:Tab\n#html:false\n#columns:Front\tBack\n"
        assertTrue(text.startsWith(header));var offset=header.length;val rows=mutableListOf<List<String>>()
        while(offset<text.length){val fields=mutableListOf<String>()
            while(true){assertEquals('"',text[offset++]);val field=StringBuilder();var closed=false
                while(offset<text.length){val c=text[offset++];if(c!='"')field.append(c)
                    else if(offset<text.length && text[offset]=='"'){field.append('"');offset++}
                    else{closed=true;break}}
                assertTrue("Field must close",closed);fields.add(field.toString())
                when(text[offset++]){'\t'->continue;'\n'->{rows.add(fields);break};else->fail("Unexpected delimiter")}}
        };assertTrue(rows.all{it.size==2});return rows
    }
}
