package com.yaprak.reader.data.exporting

import com.yaprak.reader.data.storage.VerifiedBookFile
import com.yaprak.reader.domain.library.BookExporter
import com.yaprak.reader.domain.library.ExportFailure
import com.yaprak.reader.domain.library.ExportResult
import com.yaprak.reader.domain.library.LibraryRepository
import com.yaprak.reader.reader.ReaderOpenException
import java.io.FileNotFoundException
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class DefaultBookExporter(
    private val library: LibraryRepository,
    private val sourceFiles: VerifiedBookFile,
    private val documents: ExportDocumentProvider,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val fileMutex: Mutex = Mutex(),
) : BookExporter {
    override suspend fun export(bookId: String, destinationUri: String): ExportResult = try {
        withContext(io) {
            fileMutex.withLock {
            val accepted = try {
                documents.accepts(destinationUri)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                false
            }
            if (!accepted) return@withLock ExportResult.Failed(ExportFailure.ACCESS)
            try {
                val book = library.findById(bookId) ?: throw ExportException(ExportFailure.MISSING_BOOK)
                // A changed or missing local copy must never be opened as a destination export.
                val source = sourceFiles.resolve(book)
                val digest = MessageDigest.getInstance("SHA-256")
                var copied = 0L
                source.inputStream().use { input ->
                    val destination = try {
                        documents.open(destinationUri)
                    } catch (_: FileNotFoundException) {
                        throw ExportException(ExportFailure.ACCESS)
                    }
                    destination.use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count == -1) break
                            if (count == 0) continue
                            if (copied > book.byteSize - count) throw ExportException(ExportFailure.CHANGED_SOURCE)
                            output.write(buffer, 0, count)
                            digest.update(buffer, 0, count)
                            copied += count
                        }
                    }
                }
                currentCoroutineContext().ensureActive()
                val copiedHash = digest.digest().joinToString("") { "%02x".format(it) }
                if (copied != book.byteSize || copiedHash != book.sha256) {
                    throw ExportException(ExportFailure.CHANGED_SOURCE)
                }
                ExportResult.Exported
            } catch (error: CancellationException) {
                throw error
            } catch (error: ExportException) {
                failed(destinationUri, error.reason)
            } catch (error: ReaderOpenException) {
                failed(destinationUri, ExportFailure.CHANGED_SOURCE)
            } catch (error: SecurityException) {
                failed(destinationUri, ExportFailure.ACCESS)
            } catch (error: FileNotFoundException) {
                failed(destinationUri, ExportFailure.CHANGED_SOURCE)
            } catch (error: IOException) {
                failed(destinationUri, ExportFailure.IO)
            } catch (error: Exception) {
                failed(destinationUri, ExportFailure.IO)
            }
            }
        }
    } catch (error: CancellationException) {
        withContext(NonCancellable + io) { deleteBestEffort(destinationUri) }
        throw error
    }

    private suspend fun failed(uri: String, reason: ExportFailure): ExportResult.Failed =
        withContext(NonCancellable) {
            ExportResult.Failed(reason, partialFileMayRemain = !deleteBestEffort(uri))
        }

    private fun deleteBestEffort(uri: String): Boolean = try {
        documents.delete(uri)
    } catch (_: Exception) {
        false
    }

    private class ExportException(val reason: ExportFailure) : Exception(reason.name)
}
