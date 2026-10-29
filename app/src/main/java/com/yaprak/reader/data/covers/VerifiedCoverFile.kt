package com.yaprak.reader.data.covers

import com.yaprak.reader.domain.library.BookCover
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** A thumbnail source is accepted only for the stored immutable revision's exact bytes. */
class VerifiedCoverFile(directory: File) {
    private val files = LocalCoverFiles(directory)
    suspend fun read(cover: BookCover): ByteArray {
        cover.validated()
        val file = files.resolve(cover.storageName)
        if (!file.isFile || file.length() != cover.byteSize) throw IOException("Cover bytes changed")
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = ByteArrayOutputStream(cover.byteSize.toInt())
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            var size = 0L
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count == -1) break
                if (count == 0) continue
                size += count
                if (size > cover.byteSize) throw IOException("Cover bytes changed")
                bytes.write(buffer, 0, count); digest.update(buffer, 0, count)
            }
            if (size != cover.byteSize) throw IOException("Cover bytes changed")
        }
        if (digest.digest().joinToString("") { "%02x".format(it) } != cover.sha256) throw IOException("Cover bytes changed")
        return bytes.toByteArray()
    }
}
