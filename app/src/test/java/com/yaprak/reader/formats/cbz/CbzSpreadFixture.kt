package com.yaprak.reader.formats.cbz

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yaprak.reader.data.persistence.*
import com.yaprak.reader.data.storage.VerifiedBookFile
import com.yaprak.reader.domain.library.*
import com.yaprak.reader.reader.ReaderFailure
import com.yaprak.reader.reader.ReaderOpenException
import java.io.*
import java.util.UUID
import java.util.zip.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

internal class CbzSpreadFixture private constructor(val db: LibraryDatabase, val book: Book, val file: File,
    val bytes: ByteArray, val document: CbzDocument) : Closeable {
    var allowed = true
    override fun close() { document.close(); db.close(); file.delete() }
    companion object {
        val colors = listOf(Color.RED, Color.BLUE, Color.GREEN, Color.YELLOW, Color.MAGENTA)
        suspend fun create(big: Boolean = false,
            decode: suspend (ByteArray, CbzImage, Long) -> Bitmap = { bytes, image, limit -> CbzImages.decode(bytes, image, limit) },
        ): CbzSpreadFixture {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val directory = File(context.filesDir, "books").apply { mkdirs() }
            val id = UUID.randomUUID().toString(); val file = File(directory, "$id.cbz")
            ZipOutputStream(file.outputStream()).use { zip ->
                for (index in listOf(4, 1, 3, 0, 2)) {
                    zip.putNextEntry(ZipEntry("${index + 1}.png"))
                    val width = if (big && index < 2) 2000 else 80
                    val height = if (big && index < 2) 1500 else 120
                    zip.write(png(width, height, colors[index])); zip.closeEntry()
                }
            }
            val bytes = file.readBytes()
            val book = Book(id, "Çift sayfa", null, BookFormat.CBZ, "pages.cbz", file.name,
                bytes.size.toLong(), cbzHash(bytes), 1, 5)
            val db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
            val library = RoomLibraryRepository(db.books()); library.add(book)
            val archive = withContext(Dispatchers.IO) { CbzArchive.read(file) }
            lateinit var fixture: CbzSpreadFixture
            val document = CbzDocument(archive, book, VerifiedBookFile(directory), library, Mutex(), decodeImage = decode) {
                if (!fixture.allowed) throw ReaderOpenException(ReaderFailure.RECOVERY)
            }
            fixture = CbzSpreadFixture(db, book, file, bytes, document)
            return fixture
        }
        fun png(width: Int, height: Int, color: Int): ByteArray {
            val bytes = ByteArrayOutputStream(); val output = DataOutputStream(bytes)
            output.write(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10))
            fun chunk(name: String, data: ByteArray) {
                val type = name.toByteArray(Charsets.US_ASCII)
                output.writeInt(data.size); output.write(type); output.write(data)
                output.writeInt(CRC32().apply { update(type); update(data) }.value.toInt())
            }
            val header = ByteArrayOutputStream().also { buffer -> DataOutputStream(buffer).use {
                it.writeInt(width); it.writeInt(height); it.write(byteArrayOf(8, 6, 0, 0, 0))
            } }.toByteArray()
            chunk("IHDR", header)
            val pixels = ByteArrayOutputStream(); val row = ByteArray(1 + width * 4)
            repeat(width) {
                row[1 + it * 4] = Color.red(color).toByte(); row[2 + it * 4] = Color.green(color).toByte()
                row[3 + it * 4] = Color.blue(color).toByte(); row[4 + it * 4] = -1
            }
            DeflaterOutputStream(pixels).use { compressed -> repeat(height) { compressed.write(row) } }
            chunk("IDAT", pixels.toByteArray()); chunk("IEND", ByteArray(0))
            return bytes.toByteArray()
        }
    }
}
