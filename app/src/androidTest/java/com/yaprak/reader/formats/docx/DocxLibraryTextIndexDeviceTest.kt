package com.yaprak.reader.formats.docx

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yaprak.reader.data.persistence.*
import com.yaprak.reader.data.search.*
import com.yaprak.reader.data.storage.VerifiedBookFile
import com.yaprak.reader.domain.assistant.*
import com.yaprak.reader.domain.library.*
import com.yaprak.reader.domain.search.*
import com.yaprak.reader.feature.search.LibraryTextSearchViewModel
import com.yaprak.reader.feature.search.LibraryTextBuildPhase
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real Room/FTS/decoding only on Android, isolated to one owned UUID cache directory. */
@RunWith(AndroidJUnit4::class)
class DocxLibraryTextIndexDeviceTest {
    private val word="http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private val rel="http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private fun zip(parts: Map<String,ByteArray>)=ByteArrayOutputStream().also { output -> ZipOutputStream(output).use { zip ->
        for((name,bytes) in parts) { zip.putNextEntry(ZipEntry(name));zip.write(bytes);zip.closeEntry() }
    } }.toByteArray()
    private fun text(body: String,notes: Boolean=false): ByteArray {
        val parts=linkedMapOf(
            "[Content_Types].xml" to "<Types xmlns='http://schemas.openxmlformats.org/package/2006/content-types'><Default Extension='rels' ContentType='application/vnd.openxmlformats-package.relationships+xml'/><Default Extension='xml' ContentType='application/xml'/><Override PartName='/word/document.xml' ContentType='application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml'/>"+(if(notes) "<Override PartName='/word/footnotes.xml' ContentType='application/vnd.openxmlformats-officedocument.wordprocessingml.footnotes+xml'/>" else "")+"</Types>",
            "_rels/.rels" to "<Relationships xmlns='http://schemas.openxmlformats.org/package/2006/relationships'><Relationship Id='main' Type='"+rel+"/officeDocument' Target='word/document.xml'/></Relationships>",
            "word/document.xml" to "<w:document xmlns:w='"+word+"'><w:body>"+body+"</w:body></w:document>")
        if(notes) {
            parts["word/_rels/document.xml.rels"]="<Relationships xmlns='http://schemas.openxmlformats.org/package/2006/relationships'><Relationship Id='notes' Type='"+rel+"/footnotes' Target='footnotes.xml'/></Relationships>"
            parts["word/footnotes.xml"]="<w:footnotes xmlns:w='"+word+"'><w:footnote w:id='2'><w:p><w:r><w:footnoteRef/><w:t>footnote needle</w:t></w:r></w:p></w:footnote></w:footnotes>"
        }
        return zip(parts.mapValues { it.value.toByteArray() })
    }
    private fun imageOnly(): ByteArray {
        val context=InstrumentationRegistry.getInstrumentation().context
        val entries=linkedMapOf<String,ByteArray>()
        context.assets.open("docx/python-docx-image.docx").use { input -> ZipInputStream(input).use { zip ->
            while(true) { val entry=zip.nextEntry ?: break;entries[entry.name]=zip.readBytes();zip.closeEntry() }
        } }
        val main=entries.getValue("word/document.xml").toString(Charsets.UTF_8)
        val drawing=requireNotNull(Regex("<w:drawing[\\s\\S]*?</w:drawing>").find(main)).value
        entries["word/document.xml"]=("<w:document xmlns:w='"+word+"' xmlns:r='"+rel+"' xmlns:wp='http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing' xmlns:a='http://schemas.openxmlformats.org/drawingml/2006/main' xmlns:pic='http://schemas.openxmlformats.org/drawingml/2006/picture'><w:body><w:p><w:r>"+drawing+"</w:r></w:p></w:body></w:document>").toByteArray()
        return zip(entries)
    }
    private inner class Fixture(val context: Context,val root: File,val main: LibraryDatabase,var cache: TextIndexDatabase) {
        val library=RoomLibraryRepository(main.books())
        val files=File(root,"books").apply { check(mkdirs()) }
        val extractor=DocxBookTextExtractor(VerifiedBookFile(files))
        var index=LocalLibraryTextIndex(cache,library,extractor)
        suspend fun add(bytes: ByteArray): Book {
            val id=UUID.randomUUID().toString();val file=File(files,id+".docx").apply { writeBytes(bytes) }
            val hash=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            return Book(id,"Owned "+id,null,BookFormat.DOCX,"owned.docx",file.name,file.length(),hash,1).also { library.add(it) }
        }
        fun reopen() {
            cache.close();cache=Room.databaseBuilder(context,TextIndexDatabase::class.java,File(root,"index.db").absolutePath).build()
            index=LocalLibraryTextIndex(cache,library,extractor)
        }
    }
    private suspend fun fixture(block: suspend Fixture.()->Unit) {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val root=File(context.cacheDir,"docx-index-device-"+UUID.randomUUID()).apply { check(mkdirs()) }
        val main=Room.databaseBuilder(context,LibraryDatabase::class.java,File(root,"main.db").absolutePath).build()
        val cache=Room.databaseBuilder(context,TextIndexDatabase::class.java,File(root,"index.db").absolutePath).build()
        val fixture=Fixture(context,root,main,cache)
        try { withTimeout(90_000) { fixture.block() } }
        finally {
            fixture.cache.close();main.close()
            check(root.canonicalFile.parentFile==context.cacheDir.canonicalFile && root.name.startsWith("docx-index-device-"))
            check(root.deleteRecursively())
        }
    }
    @Test fun originalDocxAndNotesSearchAcrossRealFtsBooksSurviveReopenAndKeepScopedAiEvidence()=runBlocking {
        fixture {
            val book=add(text("<w:p><w:r><w:t>depen</w:t></w:r><w:r><w:t>dency injection 🌿</w:t><w:footnoteReference w:id='2'/></w:r></w:p>",notes=true))
            val other=add(text("<w:p><w:r><w:t>dependency injection second</w:t></w:r></w:p>"))
            index.rebuild {}
            val result=index.observe("DEPENDENCY injection").first()
            assertEquals(setOf(book.id,other.id),result.hits.map { it.book.id }.toSet())
            val hit=result.hits.single { it.book.id==book.id }
            assertEquals(BookFormat.DOCX,hit.target.sourceFormat);assertEquals(DOCX_INDEX_HREF,hit.target.resourceHref)
            assertEquals(DOCX_INDEX_TITLE,hit.chapterTitle);assertNull(hit.target.pdfPageIndex)
            assertEquals(hit,index.resolve(hit))
            assertEquals(book.id,index.observeBook("footnote needle",BookTextScope(book.id,book.sha256)).first().hits.single().book.id)
            assertTrue(index.observe("Dipnot").first().hits.isEmpty())
            reopen();assertEquals(hit,index.observeBook("dependency injection",BookTextScope(book.id,book.sha256)).first().hits.single())
            assertEquals(2,cache.openHelper.readableDatabase.version)
            val artifact=File(root,"view.epub");DocxEpub.write(artifact,book.sha256,StrictDocx.read(File(files,book.storageName)))
            val proof=DocxIndexedSource(book,library,VerifiedBookFile(files),artifact,docxViewHash(artifact),Mutex(),{})
            assertTrue(proof.verify(hit.target));artifact.appendText("changed");assertFalse(proof.verify(hit.target))
            assertTrue(supportsBookSourceRetrieval(BookFormat.DOCX))
            assertEquals(book.id,index.prepare(BookTextScope(book.id,book.sha256),"dependency").references.single().hit.book.id)
            assertNull(main.reading().resume(book.id,book.sha256));assertTrue(main.reading().observed(book.id,book.sha256).isEmpty())
        }
    }
    @Test fun legacyUnsupportedNoTextCorruptSourcesCacheRowsAndCancellationNeverBecomeReadyEvidence()=runBlocking {
        fixture {
            val book=add(text("<w:p><w:r><w:t>upgrade needle</w:t></w:r></w:p>"))
            cache.index().insertBook(TextIndexBookEntity(book.id,book.sha256,1,UUID.randomUUID().toString(),LibraryTextBookState.UNSUPPORTED.name,0,0,BookFormat.DOCX.name))
            assertEquals(LibraryTextBookState.NOT_INDEXED,index.observe("").first().books.single().state)
            val changed=add(text("<w:p><w:r><w:t>changed needle</w:t></w:r></w:p>"));File(files,changed.storageName).appendText(" ")
            val image=add(imageOnly())
            index.rebuild {}
            val statuses=index.observe("").first().books.associate { it.book.id to it.state }
            assertEquals(LibraryTextBookState.READY,statuses[book.id]);assertEquals(LibraryTextBookState.FAILED,statuses[changed.id])
            assertEquals(LibraryTextBookState.NO_TEXT,statuses[image.id])
            val hit=index.observe("upgrade needle").first().hits.single()
            val entered=CompletableDeferred<Unit>()
            val blocked=object: BookTextExtractor {
                override fun supports(book: Book)=book.format==BookFormat.DOCX
                override suspend fun extract(book: Book): ExtractedBookText { entered.complete(Unit);awaitCancellation() }
            }
            val caller=launch { LocalLibraryTextIndex(cache,library,blocked).rebuild {} }
            entered.await();caller.cancelAndJoin();assertEquals(hit,index.observe("upgrade needle").first().hits.single())
            for(change in listOf("readingOrderIndex=1","chapterTitle='Fake chapter'","resourceHref='Text/document.xhtml#fn1'","pdfPageIndex=0")) {
                index.rebuild {}
                cache.openHelper.writableDatabase.execSQL("UPDATE text_index_chunks SET "+change+" WHERE bookId=?",arrayOf(book.id))
                assertEquals(LibraryTextIndexFailure.SOURCE,(runCatching { index.observe("upgrade needle").first() }.exceptionOrNull() as LibraryTextIndexException).reason)
            }
        }
    }

    @Test fun actualDocxKeywordAndCompleteDocumentSourcesRemainBookScopedAfterDiskReopen()=runBlocking {
        fixture {
            val mainText="actual original needle 🌿 "+"literal source ".repeat(1500)
            val book=add(text("<w:p><w:r><w:t>"+mainText+"</w:t><w:footnoteReference w:id='2'/></w:r></w:p>",notes=true))
            add(text("<w:p><w:r><w:t>other book needle</w:t></w:r></w:p>"))
            val scope=BookTextScope(book.id,book.sha256);index.rebuild {}
            val keyword=index.prepare(scope,"needle")
            assertTrue(keyword.references.isNotEmpty());assertTrue(keyword.references.all { it.hit.book.id==book.id })
            BookQuestionRequest.create(keyword,"Explain the original needle")
            val section=index.listSections(scope).single()
            assertEquals(DOCX_INDEX_TITLE,section.chapterTitle);assertEquals(0,section.readingOrderIndex);assertNull(section.pdfPageIndex)
            assertTrue(section.chunkCount>MAX_BOOK_SOURCE_REFERENCES);assertTrue(section.fitsSingleRequest)
            val preview=index.prepareSection(scope,section)
            assertEquals(section.chunkCount,preview.references.size);assertFalse(preview.truncated)
            assertTrue(preview.references.last().text.endsWith("footnote needle"))
            assertFalse(preview.references.any { it.text.contains("Dipnot 1") || it.text.contains("Metne dön") })
            val request=BookQuestionRequest.create(preview,BookStudyTask.SUMMARY.questionForSection(BookFormat.DOCX))
            val source=preview.references.last()
            validateBookAnswer(request,BookAnswer(preview.requestId,BookAnswerStatus.ANSWERED,
                listOf(BookAnswerStatement("Note explanation",listOf(BookAnswerCitation(source,"footnote needle")))),""))
            reopen();val fresh=index.prepareSection(scope,section)
            assertNotEquals(preview.requestId,fresh.requestId);assertEquals(preview.references,fresh.references)
            assertEquals(BookSourceFailure.UNSUPPORTED,(runCatching { index.listChapters(scope) }.exceptionOrNull() as BookSourceException).reason)
            assertNull(main.reading().resume(book.id,book.sha256));assertTrue(main.reading().observed(book.id,book.sha256).isEmpty())
        }
    }
    @Test fun changedDocxOriginalOldGenerationAndInventedChapterCannotAuthorizeFreshAssistantSources()=runBlocking {
        fixture {
            val book=add(text("<w:p><w:r><w:t>original needle</w:t></w:r></w:p>"))
            val scope=BookTextScope(book.id,book.sha256);index.rebuild {};val old=index.listSections(scope).single()
            index.rebuild {}
            assertEquals(BookSourceFailure.SOURCE,(runCatching { index.prepareSection(scope,old) }.exceptionOrNull() as BookSourceException).reason)
            val section=index.listSections(scope).single()
            for(bad in listOf(section.copy(chapterTitle="Word chapter"),section.copy(readingOrderIndex=1),section.copy(pdfPageIndex=0)))
                assertEquals(BookSourceFailure.SOURCE,(runCatching { index.prepareSection(scope,bad) }.exceptionOrNull() as BookSourceException).reason)
            File(files,book.storageName).appendText("changed original")
            assertEquals(BookSourceFailure.SOURCE,(runCatching { index.prepareSection(scope,section) }.exceptionOrNull() as BookSourceException).reason)
            for(query in listOf("needle","no matches"))
                assertEquals(BookSourceFailure.SOURCE,(runCatching { index.prepare(scope,query) }.exceptionOrNull() as BookSourceException).reason)
        }
    }
    @Test fun oversizedCompleteDocxSummaryFailsWithoutPrefixWhileKeywordSourcesStayBounded()=runBlocking {
        fixture {
            val book=add(text("<w:p><w:r><w:t>"+"source needle ".repeat(10_000)+"</w:t></w:r></w:p>"))
            val image=add(imageOnly());index.rebuild {}
            val scope=BookTextScope(book.id,book.sha256);val section=index.listSections(scope).single()
            assertTrue(section.chunkCount>MAX_COMPLETE_BOOK_SOURCE_REFERENCES);assertFalse(section.fitsSingleRequest)
            assertEquals(BookSourceFailure.LIMIT,(runCatching { index.prepareSection(scope,section) }.exceptionOrNull() as BookSourceException).reason)
            val keyword=index.prepare(scope,"needle")
            assertEquals(MAX_BOOK_SOURCE_REFERENCES,keyword.references.size);assertTrue(keyword.truncated)
            BookQuestionRequest.create(keyword,"Explain the excerpts")
            val imageScope=BookTextScope(image.id,image.sha256)
            assertEquals(BookSourceFailure.UNSUPPORTED,(runCatching { index.listSections(imageScope) }.exceptionOrNull() as BookSourceException).reason)
            assertEquals(BookSourceFailure.UNSUPPORTED,(runCatching { index.prepare(imageScope,"needle") }.exceptionOrNull() as BookSourceException).reason)
        }
    }


    @Test fun explicitSingleBookIndexTouchesOnlyItsRequestedGenerationAndNeverPrunesOtherCacheRows()=runBlocking {
        fixture {
            val first=add(text("<w:p><w:r><w:t>first needle</w:t></w:r></w:p>"))
            val other=add(text("<w:p><w:r><w:t>other needle</w:t></w:r></w:p>"));index.rebuild {}
            val firstScope=BookTextScope(first.id,first.sha256);val otherScope=BookTextScope(other.id,other.sha256)
            val prior=index.observeBook("first needle",firstScope).first().hits.single()
            val untouched=index.observeBook("other needle",otherScope).first().hits.single()
            val obsolete=UUID.randomUUID().toString()
            cache.index().insertBook(TextIndexBookEntity(obsolete,"a".repeat(64),1,UUID.randomUUID().toString(),LibraryTextBookState.NOT_INDEXED.name,0,0,BookFormat.DOCX.name))
            val calls=mutableListOf<String>();val actual=extractor
            val counted=object: BookTextExtractor {
                override fun supports(book: Book)=actual.supports(book)
                override suspend fun extract(book: Book): ExtractedBookText { calls+=book.id;return actual.extract(book) }
            }
            val selected=LocalLibraryTextIndex(cache,library,counted);val progress=mutableListOf<LibraryTextIndexProgress>()
            assertEquals(LibraryTextBookState.READY,selected.rebuildBook(firstScope) { progress+=it })
            assertEquals(listOf(first.id),calls)
            assertEquals(listOf(LibraryTextIndexProgress(0,1,null),LibraryTextIndexProgress(0,1,first.title),LibraryTextIndexProgress(1,1,null)),progress)
            assertNotEquals(prior.generation,selected.observeBook("first needle",firstScope).first().hits.single().generation)
            assertEquals(untouched,selected.observeBook("other needle",otherScope).first().hits.single())
            cache.openHelper.readableDatabase.query("SELECT COUNT(*) FROM text_index_books WHERE bookId=?",arrayOf(obsolete)).use {
                assertTrue(it.moveToFirst());assertEquals(1,it.getInt(0))
            }
        }
    }
    @Test fun singleBookCancellationAndSharedBusyGatePreserveEveryPriorGenerationAndRejectForeignScope()=runBlocking {
        fixture {
            val book=add(text("<w:p><w:r><w:t>first needle</w:t></w:r></w:p>"))
            val other=add(text("<w:p><w:r><w:t>other needle</w:t></w:r></w:p>"));index.rebuild {}
            val scope=BookTextScope(book.id,book.sha256);val otherScope=BookTextScope(other.id,other.sha256)
            val first=index.observeBook("first needle",scope).first().hits.single()
            val untouched=index.observeBook("other needle",otherScope).first().hits.single()
            val entered=CompletableDeferred<Unit>();var block=true;val actual=extractor
            val controlled=object: BookTextExtractor {
                override fun supports(book: Book)=actual.supports(book)
                override suspend fun extract(book: Book): ExtractedBookText {
                    if(block) { entered.complete(Unit);awaitCancellation() }
                    return actual.extract(book)
                }
            }
            val selected=LocalLibraryTextIndex(cache,library,controlled)
            val job=launch { selected.rebuildBook(scope) {} };entered.await()
            assertEquals(LibraryTextIndexFailure.BUSY,(runCatching { selected.rebuildBook(otherScope) {} }.exceptionOrNull() as LibraryTextIndexException).reason)
            assertEquals(LibraryTextIndexFailure.BUSY,(runCatching { selected.rebuild {} }.exceptionOrNull() as LibraryTextIndexException).reason)
            job.cancelAndJoin()
            assertEquals(first,index.observeBook("first needle",scope).first().hits.single())
            assertEquals(untouched,index.observeBook("other needle",otherScope).first().hits.single())
            assertEquals(LibraryTextIndexFailure.SOURCE,(runCatching { selected.rebuildBook(scope.copy(contentSha256="b".repeat(64))) {} }.exceptionOrNull() as LibraryTextIndexException).reason)
            block=false;assertEquals(LibraryTextBookState.READY,selected.rebuildBook(scope) {})
            assertEquals(untouched,selected.observeBook("other needle",otherScope).first().hits.single())
        }
    }
    @Test fun removedOriginalDuringExtractionCannotReturnAnUnpublishedFailureAsCompleted()=runBlocking {
        fixture {
            val book=add(text("<w:p><w:r><w:t>first needle</w:t></w:r></w:p>"))
            val other=add(text("<w:p><w:r><w:t>other needle</w:t></w:r></w:p>"));index.rebuild {}
            val scope=BookTextScope(book.id,book.sha256);val otherScope=BookTextScope(other.id,other.sha256)
            val prior=index.observeBook("first needle",scope).first().hits.single()
            val untouched=index.observeBook("other needle",otherScope).first().hits.single()
            val controlled=object: BookTextExtractor {
                override fun supports(book: Book)=true
                override suspend fun extract(book: Book): ExtractedBookText {
                    assertEquals(1,main.books().deleteExact(book.id,book.sha256))
                    throw TextExtractionException(TextExtractionFailure.SOURCE)
                }
            }
            val selected=LocalLibraryTextIndex(cache,library,controlled);val progress=mutableListOf<LibraryTextIndexProgress>()
            val failure=runCatching { selected.rebuildBook(scope) { progress+=it } }.exceptionOrNull()
            assertEquals(LibraryTextIndexFailure.SOURCE,(failure as LibraryTextIndexException).reason)
            assertFalse(progress.any { it.processed==1 })
            assertEquals(untouched,index.observeBook("other needle",otherScope).first().hits.single())
            cache.openHelper.readableDatabase.query("SELECT generation FROM text_index_books WHERE bookId=?",arrayOf(book.id)).use {
                assertTrue(it.moveToFirst());assertEquals(prior.generation,it.getString(0))
            }
        }
    }
    @Test fun singleBookOriginalFailureNoTextAndWholeResourceLimitReturnHonestStatesWithoutChangingOtherBook()=runBlocking {
        fixture {
            val book=add(text("<w:p><w:r><w:t>first needle</w:t></w:r></w:p>"))
            val other=add(text("<w:p><w:r><w:t>other needle</w:t></w:r></w:p>"));index.rebuild {}
            val otherScope=BookTextScope(other.id,other.sha256);val untouched=index.observeBook("other needle",otherScope).first().hits.single()
            File(files,book.storageName).appendText("changed")
            assertEquals(LibraryTextBookState.FAILED,index.rebuildBook(BookTextScope(book.id,book.sha256)) {})
            assertTrue(index.observeBook("needle",BookTextScope(book.id,book.sha256)).first().hits.isEmpty())
            val image=add(imageOnly())
            assertEquals(LibraryTextBookState.NO_TEXT,index.rebuildBook(BookTextScope(image.id,image.sha256)) {})
            val limited=add(text("<w:p><w:r><w:t>"+"雪".repeat(MAX_INDEX_RESOURCE_BYTES/3+1)+"</w:t></w:r></w:p>"))
            assertEquals(LibraryTextBookState.LIMIT,index.rebuildBook(BookTextScope(limited.id,limited.sha256)) {})
            assertTrue(index.observeBook("needle",BookTextScope(limited.id,limited.sha256)).first().hits.isEmpty())
            assertEquals(untouched,index.observeBook("other needle",otherScope).first().hits.single())
        }
    }


    private fun nativeMain(block: () -> Unit) =
        InstrumentationRegistry.getInstrumentation().runOnMainSync { block() }

    private suspend fun nativeSearchModel(source: LibraryTextIndex,
        block: suspend (LibraryTextSearchViewModel, Any) -> Unit) {
        lateinit var model: LibraryTextSearchViewModel
        lateinit var store: androidx.lifecycle.ViewModelStore
        val owner=Any()
        nativeMain {
            store=androidx.lifecycle.ViewModelStore()
            model=LibraryTextSearchViewModel(source,androidx.lifecycle.SavedStateHandle())
            store.put("owned-scoped-native",model)
            model.attachLifecycleOwner(owner);model.setLifecycleOwnerActive(owner,true)
        }
        try { block(model,owner) }
        finally { nativeMain { model.setLifecycleOwnerActive(owner,false);store.clear() } }
    }

    private suspend fun nativeSearchReady(model: LibraryTextSearchViewModel,scope: BookTextScope?) {
        val state=withTimeout(30_000) { model.state.first { it.scope==scope && !it.loading } }
        assertNull(state.failure);assertEquals(scope,state.result!!.scope)
    }

    @Test fun scopedSearchViewModelRebuildUsesOnlySelectedOriginalOnActualAndroidLooperAndKeepsOtherGeneration()=runBlocking {
        fixture {
            val book=add(text("<w:p><w:r><w:t>first needle</w:t></w:r></w:p>"))
            val other=add(text("<w:p><w:r><w:t>other needle</w:t></w:r></w:p>"));index.rebuild {}
            val scope=BookTextScope(book.id,book.sha256);val otherScope=BookTextScope(other.id,other.sha256)
            val before=index.observeBook("first needle",scope).first().hits.single()
            val untouched=index.observeBook("other needle",otherScope).first().hits.single()
            val actual=index;val global=java.util.concurrent.atomic.AtomicInteger()
            val selected=java.util.concurrent.CopyOnWriteArrayList<BookTextScope>()
            val source=object: LibraryTextIndex by actual {
                override suspend fun rebuild(onProgress: (LibraryTextIndexProgress)->Unit) { global.incrementAndGet();actual.rebuild(onProgress) }
                override suspend fun rebuildBook(scope: BookTextScope,onProgress: (LibraryTextIndexProgress)->Unit): LibraryTextBookState {
                    selected+=scope;return actual.rebuildBook(scope,onProgress)
                }
            }
            nativeSearchModel(source) { model,_->
                nativeSearchReady(model,null)
                nativeMain { model.changeScope(scope,model.state.value.revision) };nativeSearchReady(model,scope)
                nativeMain { val current=model.state.value;model.rebuild(current.build,current.scope,current.revision) }
                val state=withTimeout(30_000) { model.state.first { it.build is LibraryTextBuildPhase.Finished || it.build is LibraryTextBuildPhase.Failed } }
                assertTrue(state.build is LibraryTextBuildPhase.Finished)
                val done=state.build as LibraryTextBuildPhase.Finished
                assertFalse(done.cancelled);assertEquals(LibraryTextBookState.READY,done.bookState)
                assertEquals(listOf(scope),selected.toList());assertEquals(0,global.get())
                assertNotEquals(before.generation,index.observeBook("first needle",scope).first().hits.single().generation)
                assertEquals(untouched,index.observeBook("other needle",otherScope).first().hits.single())
            }
        }
    }

    @Test fun scopedSearchViewModelReportsActualNoTextAndPauseCancelsWithoutGlobalFallbackOrNewGeneration()=runBlocking {
        fixture {
            val other=add(text("<w:p><w:r><w:t>other needle</w:t></w:r></w:p>"))
            val image=add(imageOnly());val otherScope=BookTextScope(other.id,other.sha256);val scope=BookTextScope(image.id,image.sha256)
            index.rebuildBook(otherScope) {};val untouched=index.observeBook("other needle",otherScope).first().hits.single()
            val actualExtractor=extractor;val blocking=java.util.concurrent.atomic.AtomicBoolean(false);val entered=CompletableDeferred<Unit>()
            val controlled=object: BookTextExtractor {
                override fun supports(book: Book)=actualExtractor.supports(book)
                override suspend fun extract(book: Book): ExtractedBookText {
                    if(blocking.get()) { entered.complete(Unit);awaitCancellation() }
                    return actualExtractor.extract(book)
                }
            }
            val actual=LocalLibraryTextIndex(cache,library,controlled);val global=java.util.concurrent.atomic.AtomicInteger()
            val selected=java.util.concurrent.CopyOnWriteArrayList<BookTextScope>()
            val source=object: LibraryTextIndex by actual {
                override suspend fun rebuild(onProgress: (LibraryTextIndexProgress)->Unit) { global.incrementAndGet();actual.rebuild(onProgress) }
                override suspend fun rebuildBook(scope: BookTextScope,onProgress: (LibraryTextIndexProgress)->Unit): LibraryTextBookState {
                    selected+=scope;return actual.rebuildBook(scope,onProgress)
                }
            }
            nativeSearchModel(source) { model,owner->
                nativeSearchReady(model,null)
                nativeMain { model.changeScope(scope,model.state.value.revision) };nativeSearchReady(model,scope)
                nativeMain { val current=model.state.value;model.rebuild(current.build,current.scope,current.revision) }
                val noText=withTimeout(30_000) { model.state.first { it.build is LibraryTextBuildPhase.Finished || it.build is LibraryTextBuildPhase.Failed } }
                assertTrue(noText.build is LibraryTextBuildPhase.Finished)
                assertEquals(LibraryTextBookState.NO_TEXT,(noText.build as LibraryTextBuildPhase.Finished).bookState)
                val prior=cache.index().books().associateBy { it.bookId };blocking.set(true)
                nativeMain { val current=model.state.value;model.rebuild(current.build,current.scope,current.revision) }
                withTimeout(30_000) { entered.await() }
                nativeMain { model.setLifecycleOwnerActive(owner,false) }
                val cancelled=withTimeout(30_000) { model.state.first { it.build is LibraryTextBuildPhase.Finished && (it.build as LibraryTextBuildPhase.Finished).cancelled } }
                assertNull((cancelled.build as LibraryTextBuildPhase.Finished).bookState)
                assertEquals(listOf(scope,scope),selected.toList());assertEquals(0,global.get())
                assertEquals(prior,cache.index().books().associateBy { it.bookId })
                assertEquals(untouched,index.observeBook("other needle",otherScope).first().hits.single())
            }
        }
    }
}
