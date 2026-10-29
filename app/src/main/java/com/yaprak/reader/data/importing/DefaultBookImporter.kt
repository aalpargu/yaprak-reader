package com.yaprak.reader.data.importing

import com.yaprak.reader.data.storage.LocalBookFiles
import com.yaprak.reader.domain.library.Book
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.domain.library.BookImportException
import com.yaprak.reader.domain.library.BookImporter
import com.yaprak.reader.domain.library.ImportFailure
import com.yaprak.reader.domain.library.ImportResult
import com.yaprak.reader.domain.library.LibraryRepository
import com.yaprak.reader.formats.BookMetadataReader
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class DefaultBookImporter(
    private val documents: DocumentProvider,
    private val files: LocalBookFiles,
    private val metadata: BookMetadataReader,
    private val library: LibraryRepository,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val mutex: Mutex = Mutex(),
) : BookImporter {
    override suspend fun import(documentUri: String): ImportResult = withContext(io) {
        mutex.withLock { importLocked(documentUri) }
    }

    private suspend fun importLocked(uri: String): ImportResult {
        var temporary: File? = null
        var committed: File? = null
        var saved = false
        try {
            files.recover(library.storedFileNames())
            val source = documents.resolve(uri)
            val id = UUID.randomUUID().toString()
            val staged = files.stage(source, id)
            temporary = staged.file
            if (library.findByHash(staged.sha256) != null) return ImportResult.Duplicate
            val info = metadata.read(staged.file, staged.format)
            val fallbackTitle = (if (staged.format == BookFormat.FB2 && source.name.endsWith(".fb2.zip", ignoreCase = true))
                source.name.dropLast(".fb2.zip".length) else source.name.substringBeforeLast('.', source.name))
                .trim().ifBlank { "Kitap" }
            // The short commit section must finish even if the screen's scope is cancelled.
            // On process death, the next import removes files with no committed database row.
            withContext(NonCancellable) {
                val finalFile = files.commit(staged, id)
                committed = finalFile
                library.addImported(
                    Book(
                        id = id,
                        title = info.title ?: fallbackTitle,
                        authors = info.authors,
                        format = staged.format,
                        originalName = source.name,
                        storageName = finalFile.name,
                        byteSize = staged.size,
                        sha256 = staged.sha256,
                        importedAt = System.currentTimeMillis(),
                        pageCount = info.pageCount,
                    ), info.initialOrganization,
                )
                saved = true
            }
            return ImportResult.Added
        } catch (error: CancellationException) {
            throw error
        } catch (error: BookImportException) {
            return ImportResult.Failed(error.reason)
        } catch (error: SecurityException) {
            return ImportResult.Failed(ImportFailure.ACCESS)
        } catch (error: IOException) {
            return ImportResult.Failed(ImportFailure.IO)
        } catch (error: Exception) {
            return ImportResult.Failed(ImportFailure.UNEXPECTED)
        } finally {
            temporary?.delete()
            if (!saved) committed?.delete()
        }
    }
}
