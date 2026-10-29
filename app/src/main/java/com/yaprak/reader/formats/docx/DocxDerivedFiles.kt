package com.yaprak.reader.formats.docx

import java.io.File
import java.io.IOException

internal class DocxDerivedFiles(private val directory: File) {
    fun create(): File {
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create DOCX viewing directory")
        if ((directory.listFiles()?.size ?: 0) >= 64) throw IOException("DOCX viewing cache limit")
        return File.createTempFile("docx-view-", ".epub", directory)
    }
    fun release(file: File) {
        require(file.canonicalFile.parentFile == directory.canonicalFile)
        file.delete()
    }
    /** Startup only, before any active reading session owns an artifact. */
    fun recoverAbandoned() {
        directory.listFiles()?.filter { it.isFile && it.name.matches(Regex("docx-view-[A-Za-z0-9-]+\\.epub")) }?.forEach {
            if (it.canonicalFile.parentFile != directory.canonicalFile || !it.delete())
                throw IOException("Cannot clean interrupted DOCX view")
        }
    }
}
