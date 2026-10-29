package com.yaprak.reader.formats.pdf

import android.graphics.Paint
import android.os.Build
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yaprak.reader.data.persistence.*
import com.yaprak.reader.domain.library.*
import com.yaprak.reader.domain.pdfannotations.*
import com.yaprak.reader.reader.*
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real Android15+ decoder, fresh original file and on-device Room; compiled host tests cannot replace it. */
@RunWith(AndroidJUnit4::class)
class PdfAnnotationDeviceTest {
    @Test fun nativeSelectionGeometryAndAttachedNoteSurviveRoomReopenAndFreshSourceProof() = runBlocking {
        fixture { book, file, directory, name ->
            val context=InstrumentationRegistry.getInstrumentation().targetContext
            val sourceBytes=file.readBytes()
            val selected=withContext(Dispatchers.IO) { PdfDocument.open(file) }.use { document ->
                val point=PdfTextPoint(110.0/300,94.0/300)
                requireNotNull(document.selectText(1,point,point))
            }
            val anchor=requireNotNull(selected.sourceAnchor)
            assertEquals(1,anchor.pageIndex); assertEquals(300,anchor.pageWidth); assertEquals(300,anchor.pageHeight)
            assertTrue(anchor.bounds.isNotEmpty()); assertEquals("alpha",selected.text.trim())
            var db=Room.databaseBuilder(context,LibraryDatabase::class.java,name).build()
            try {
                db.books().insert(book.toEntity())
                val saved=RoomPdfAnnotationRepository(db,PdfOriginalAnnotations(directory),Mutex()).create(book.id,book.sha256,
                    selected.text,anchor,PdfHighlightColor.BLUE,"  native note 🌿\nexact  ")
                db.close(); db=Room.databaseBuilder(context,LibraryDatabase::class.java,name).build()
                val repo=RoomPdfAnnotationRepository(db,PdfOriginalAnnotations(directory),Mutex())
                assertEquals(saved,repo.observe(book.id,book.sha256).first().single())
                repo.verifyExisting(book.id,book.sha256,listOf(saved))
                val edited=repo.update(book.id,book.sha256,saved,PdfHighlightColor.PINK,"edited")
                assertEquals(1L,edited.revision); assertEquals(anchor,edited.anchor); assertEquals(selected.text,edited.text)
                assertArrayEquals(sourceBytes,file.readBytes()); repo.remove(book.id,book.sha256,edited)
                assertTrue(repo.observe(book.id,book.sha256).first().isEmpty())
            } finally { db.close() }
        }
    }
    @Test fun shiftedNativeBoundsAndChangedOriginalBytesCannotBeCommittedOrRepainted() = runBlocking {
        fixture { book,file,directory,name ->
            val selected=withContext(Dispatchers.IO) { PdfDocument.open(file) }.use { document ->
                val point=PdfTextPoint(110.0/300,94.0/300); requireNotNull(document.selectText(1,point,point))
            }
            val anchor=requireNotNull(selected.sourceAnchor)
            val shifted=PdfSourceAnchor(anchor.pageIndex,anchor.pageWidth,anchor.pageHeight,anchor.start,anchor.stop,
                anchor.bounds.map { PdfTextBounds(it.left,it.top+1,it.right,it.bottom+1) })
            val context=InstrumentationRegistry.getInstrumentation().targetContext
            val db=Room.databaseBuilder(context,LibraryDatabase::class.java,name).build()
            try {
                db.books().insert(book.toEntity()); val repo=RoomPdfAnnotationRepository(db,PdfOriginalAnnotations(directory),Mutex())
                assertTrue(runCatching { repo.create(book.id,book.sha256,selected.text,shifted,PdfHighlightColor.YELLOW,null) }.exceptionOrNull() is PdfAnnotationSourceException)
                assertTrue(repo.observe(book.id,book.sha256).first().isEmpty())
                val saved=repo.create(book.id,book.sha256,selected.text,anchor,PdfHighlightColor.YELLOW,null)
                val bytes=file.readBytes(); val changed=bytes.copyOf(); changed[0]=(changed[0].toInt() xor 1).toByte(); file.writeBytes(changed)
                assertTrue(runCatching { repo.verifyExisting(book.id,book.sha256,listOf(saved)) }.exceptionOrNull() is PdfAnnotationSourceException)
                assertTrue(runCatching { repo.update(book.id,book.sha256,saved,PdfHighlightColor.GREEN,"changed") }.exceptionOrNull() is PdfAnnotationSourceException)
                assertEquals(saved,repo.observe(book.id,book.sha256).first().single()); file.writeBytes(bytes)
            } finally { db.close() }
        }
    }
    private suspend fun fixture(block: suspend (Book,File,File,String)->Unit) {
        assumeTrue(Build.VERSION.SDK_INT>=35)
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val id=UUID.randomUUID().toString(); val name="pa-${id.take(8)}.db"
        val directory=File(context.cacheDir,"pa-${id.take(8)}").apply { check(mkdir()) }; val file=File(directory,"$id.pdf")
        try {
            withContext(Dispatchers.IO) {
                val writer=android.graphics.pdf.PdfDocument()
                try { listOf("First alpha","Second alpha").forEachIndexed { index,text ->
                    val page=writer.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(300,300,index+1).create())
                    try { page.canvas.drawText(text,20f,100f,Paint().apply { textSize=18f }) } finally { writer.finishPage(page) }
                }; file.outputStream().use(writer::writeTo) } finally { writer.close() }
            }
            val bytes=file.readBytes(); val hash=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            block(Book(id,"PDF",null,BookFormat.PDF,"source.pdf",file.name,bytes.size.toLong(),hash,1,pageCount=2),file,directory,name)
        } finally { context.deleteDatabase(name); file.delete(); directory.delete() }
    }
}
