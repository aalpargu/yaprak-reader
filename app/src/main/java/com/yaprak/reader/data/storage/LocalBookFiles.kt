package com.yaprak.reader.data.storage

import com.yaprak.reader.data.importing.SourceDocument
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.domain.library.BookImportException
import com.yaprak.reader.domain.library.ImportFailure
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

data class StagedBook(val file: File, val size: Long, val sha256: String, val format: BookFormat)

/** All methods are called on the importer's IO dispatcher and under its mutex. */
class LocalBookFiles(private val directory: File, private val maxBytes: Long = 512L * 1024 * 1024) : BookFileCleanup {
    suspend fun stage(source: SourceDocument, id: String): StagedBook {
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create library")
        val target = File(directory, "$id.part")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var size = 0L
            source.open().use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count == -1) break
                        size += count
                        if (source.name.endsWith(".docx", ignoreCase = true) &&
                            size > com.yaprak.reader.formats.docx.MAX_DOCX_PACKAGE_BYTES)
                            throw BookImportException(ImportFailure.TOO_LARGE)
                        if (size > maxBytes || (source.name.endsWith(".fb2", ignoreCase = true) ||
                                source.name.endsWith(".fb2.zip", ignoreCase = true)) && size > com.yaprak.reader.formats.fb2.MAX_FB2_BYTES)
                            throw BookImportException(ImportFailure.TOO_LARGE)
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                    }
                    output.fd.sync()
                }
            }
            if (size == 0L) throw BookImportException(ImportFailure.INVALID)
            val signature = target.inputStream().use { input ->
                ByteArray(minOf(8L, size).toInt()).also { java.io.DataInputStream(input).readFully(it) }
            }
            val format = when {
                source.name.endsWith(".docx", ignoreCase = true) -> {
                    try { com.yaprak.reader.formats.docx.DocxMetadataReader().read(target) }
                    catch (failure: BookImportException) { throw failure }
                    BookFormat.DOCX
                }
                source.name.endsWith(".fb2", ignoreCase = true) || source.name.endsWith(".fb2.zip", ignoreCase = true) -> {
                    // Persist the original container name so export chooses the correct suffix and MIME.
                    if (com.yaprak.reader.formats.fb2.hasFb2ZipSignature(signature) != source.name.endsWith(".fb2.zip", ignoreCase = true))
                        throw BookImportException(ImportFailure.INVALID)
                    try { com.yaprak.reader.formats.fb2.StrictFb2.read(target) }
                    catch (failure: com.yaprak.reader.formats.fb2.Fb2Exception) {
                        throw BookImportException(when (failure.reason) {
                            com.yaprak.reader.formats.fb2.Fb2Failure.LIMIT -> ImportFailure.TOO_LARGE
                            com.yaprak.reader.formats.fb2.Fb2Failure.UNSUPPORTED -> ImportFailure.UNSUPPORTED
                            else -> ImportFailure.INVALID
                        })
                    }
                    BookFormat.FB2
                }
                signature.take(5).toByteArray().contentEquals("%PDF-".toByteArray(Charsets.US_ASCII)) -> BookFormat.PDF
                com.yaprak.reader.formats.cbr.hasCbrSignature(signature) -> BookFormat.CBR
                source.name.endsWith(".cbr", ignoreCase = true) -> throw BookImportException(ImportFailure.INVALID)
                signature.size >= 4 && signature.take(4) == listOf<Byte>(0x50, 0x4b, 0x03, 0x04) ->
                    if (source.name.endsWith(".cbz", ignoreCase = true)) BookFormat.CBZ else BookFormat.EPUB
                source.name.endsWith(".txt", ignoreCase = true) -> {
                    try { com.yaprak.reader.formats.txt.StrictTxt.read(target) }
                    catch (failure: com.yaprak.reader.formats.txt.TxtException) {
                        throw BookImportException(if (failure.reason == com.yaprak.reader.formats.txt.TxtFailure.LIMIT)
                            ImportFailure.TOO_LARGE else ImportFailure.INVALID)
                    }
                    BookFormat.TXT
                }
                else -> throw BookImportException(ImportFailure.UNSUPPORTED)
            }
            return StagedBook(target, size, digest.digest().joinToString("") { "%02x".format(it) }, format)
        } catch (error: Throwable) {
            target.delete()
            throw error
        }
    }

    fun commit(staged: StagedBook, id: String): File {
        val target = File(directory, "$id.${staged.format.extension}")
        if (target.exists() || !staged.file.renameTo(target)) throw IOException("Cannot commit book")
        return target
    }

    /** Never follows a damaged database name outside the private books directory. */
    override fun removeCommitted(storageName: String): Boolean {
        if (storageName.isBlank() || File(storageName).name != storageName) return false
        val target = File(directory, storageName)
        if (target.canonicalFile.parentFile != directory.canonicalFile) return false
        if (!target.exists()) return true
        return target.isFile && target.delete()
    }

    fun recover(referencedNames: Set<String>) {
        // Dedicated private directory; never touches the source document or arbitrary paths.
        directory.listFiles()?.filter { it.isFile && it.name !in referencedNames }?.forEach {
            if (!it.delete()) throw IOException("Cannot clean interrupted import")
        }
    }
}
