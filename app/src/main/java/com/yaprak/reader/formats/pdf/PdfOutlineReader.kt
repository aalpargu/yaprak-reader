package com.yaprak.reader.formats.pdf

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.yaprak.reader.reader.ContentsLoadException
import com.yaprak.reader.reader.ContentsLoadFailure
import java.io.File
import java.io.IOException
import java.nio.file.Files

/** Metadata-only local parse. No rendering, attachments, script execution or original-file writes. */
internal class PdfOutlineReader(private val context: Context) {
    fun read(file: File, expectedPageCount: Int, checkActive: () -> Unit): ParsedPdfOutline {
        checkActive()
        if (file.length() !in 1..MAX_SOURCE_BYTES || expectedPageCount !in 1..BoundedPdfOutline.MAX_PAGES)
            throw ContentsLoadException(ContentsLoadFailure.LIMIT)
        PDFBoxResourceLoader.init(context.applicationContext)
        val scratch = Files.createTempDirectory(context.cacheDir.toPath(), "pdf-outline-").toFile()
        try {
            val memory = MemoryUsageSetting.setupMixed(8L * 1024 * 1024, 64L * 1024 * 1024).setTempDir(scratch)
            PDDocument.load(file, memory).use { document ->
                checkActive()
                if (document.isEncrypted) throw ContentsLoadException(ContentsLoadFailure.UNSUPPORTED)
                return BoundedPdfOutline(checkActive).read(document.documentCatalog.cosObject, expectedPageCount)
            }
        } catch (cancelled: java.util.concurrent.CancellationException) {
            throw cancelled
        } catch (_: InvalidPasswordException) {
            throw ContentsLoadException(ContentsLoadFailure.UNSUPPORTED)
        } catch (failure: PdfOutlineParseException) {
            throw ContentsLoadException(if (failure.problem == PdfOutlineProblem.LIMIT) ContentsLoadFailure.LIMIT else ContentsLoadFailure.SOURCE)
        } catch (_: StackOverflowError) {
            throw ContentsLoadException(ContentsLoadFailure.LIMIT)
        } finally {
            // PDFBox owns only flat temporary files in this newly created private directory.
            try {
                scratch.listFiles()?.forEach { temporary ->
                    if (temporary.isFile && temporary.parentFile?.canonicalFile == scratch.canonicalFile) temporary.delete()
                }
                scratch.delete()
            } catch (_: IOException) {
                // Cache cleanup must never replace cancellation or the actual parse failure.
            }
        }
    }

    companion object { const val MAX_SOURCE_BYTES = 64L * 1024 * 1024 }
}
