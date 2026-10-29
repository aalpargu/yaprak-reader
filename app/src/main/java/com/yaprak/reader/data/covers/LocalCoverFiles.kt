package com.yaprak.reader.data.covers

import com.yaprak.reader.domain.library.BookCover
import com.yaprak.reader.domain.library.CoverEditFailure
import com.yaprak.reader.domain.library.MAX_CUSTOM_COVER_BYTES
import com.yaprak.reader.formats.CustomCoverBitmapDecoder
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

fun interface CoverFileCleanup {
    fun removeCommitted(storageName: String): Boolean
}

class CoverEditException(val reason: CoverEditFailure) : IOException("Cover operation failed: $reason")

data class StagedCover(val file: File, val cover: BookCover)

/** Called on IO under the shared file mutex. No provider name or URI becomes a file path. */
class LocalCoverFiles(private val directory: File, private val maxBytes: Long = MAX_CUSTOM_COVER_BYTES.toLong()) : CoverFileCleanup {
    init { require(maxBytes in 1..MAX_CUSTOM_COVER_BYTES.toLong()) }

    fun resolve(storageName: String): File {
        if (storageName.isBlank() || File(storageName).name != storageName || storageName.indexOf('\\') >= 0 || storageName.indexOf('/') >= 0)
            throw IOException("Invalid cover name")
        val file = File(directory, storageName)
        if (file.canonicalFile.parentFile != directory.canonicalFile) throw IOException("Invalid cover path")
        return file
    }

    suspend fun stage(source: () -> InputStream, id: String): StagedCover {
        require(runCatching { UUID.fromString(id).toString() }.getOrNull() == id)
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create cover directory")
        val target = resolve("$id.part")
        if (!target.createNewFile()) throw IOException("Cover stage already exists")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var size = 0L
            source().use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        var count = input.read(buffer)
                        if (count == -1) break
                        if (count == 0) {
                            val single = input.read()
                            if (single == -1) break
                            buffer[0] = single.toByte(); count = 1
                        }
                        size += count
                        if (size > maxBytes) throw CoverEditException(CoverEditFailure.TOO_LARGE)
                        output.write(buffer, 0, count); digest.update(buffer, 0, count)
                    }
                    output.fd.sync()
                }
            }
            if (size == 0L) throw CoverEditException(CoverEditFailure.INVALID_IMAGE)
            currentCoroutineContext().ensureActive()
            val image = CustomCoverBitmapDecoder.validate(target.readBytes())
                ?: throw CoverEditException(CoverEditFailure.INVALID_IMAGE)
            currentCoroutineContext().ensureActive()
            val extension = when (image.mediaType) { "image/jpeg" -> "jpg"; "image/png" -> "png"; else -> "webp" }
            return StagedCover(target, BookCover(id, "$id.$extension", image.mediaType, size,
                digest.digest().joinToString("") { "%02x".format(it) }, image.width, image.height).validated())
        } catch (error: Throwable) {
            target.delete()
            throw error
        }
    }

    fun commit(staged: StagedCover): File {
        staged.cover.validated()
        val target = resolve(staged.cover.storageName)
        if (staged.file.canonicalFile != resolve("${staged.cover.id}.part").canonicalFile ||
            target.exists() || !staged.file.renameTo(target)) throw IOException("Cannot commit cover")
        return target
    }

    override fun removeCommitted(storageName: String): Boolean = try {
        val target = resolve(storageName)
        !target.exists() || (target.isFile && target.delete())
    } catch (_: IOException) { false }

    fun recover(referencedNames: Collection<String>) {
        val retained = referencedNames.toSet()
        directory.listFiles()?.filter { it.isFile && it.name !in retained }?.forEach {
            if (!removeCommitted(it.name)) throw IOException("Cannot clean interrupted cover write")
        }
    }
}
