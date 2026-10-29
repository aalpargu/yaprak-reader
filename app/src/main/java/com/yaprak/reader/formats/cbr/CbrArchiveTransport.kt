package com.yaprak.reader.formats.cbr

import com.github.junrar.Archive
import com.github.junrar.ArchiveOptions
import com.github.junrar.RarFormat
import com.github.junrar.exception.*
import com.github.junrar.io.SeekableReadOnlyByteChannel
import com.github.junrar.rarfile.*
import com.github.junrar.rarfile.rar5.Rar5BlockType
import com.github.junrar.rarfile.rar5.Rar5MainHeader
import com.github.junrar.volume.Volume
import com.github.junrar.volume.VolumeManager
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.text.Normalizer
import java.util.Locale
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal enum class CbrFailure { INVALID, LIMIT, UNSUPPORTED, PROTECTED }
internal class CbrException(val reason: CbrFailure) : Exception(reason.name)
private fun fail(reason: CbrFailure = CbrFailure.INVALID): Nothing = throw CbrException(reason)
internal data class CbrEntry(val name: String, val extension: String, val declaredBytes: Int)
internal data class CbrDecodedSummary(val imageCount: Int, val fileCount: Int, val expandedBytes: Long)

internal fun hasCbrSignature(bytes: ByteArray): Boolean =
    bytes.take(7).toByteArray().contentEquals(byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1a, 0x07, 0x00)) ||
        bytes.take(8).toByteArray().contentEquals(byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1a, 0x07, 0x01, 0x00))

/** Inert transport only: every sink result is provisional until this returns and the caller
 * verifies the original and admits every raster before publishing its private derived ZIP. */
internal object CbrArchiveTransport {
    const val MAX_SOURCE_BYTES = 512L * 1024 * 1024
    const val MAX_EXPANDED_BYTES = 512L * 1024 * 1024
    const val MAX_IMAGE_BYTES = 64L * 1024 * 1024
    const val MAX_DICTIONARY_BYTES = 64L * 1024 * 1024
    const val MAX_ENTRIES = 2048
    const val MAX_IMAGES = 2000
    private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")
    private val V4 = byteArrayOf(0x52,0x61,0x72,0x21,0x1a,0x07,0x00)
    private val V5 = byteArrayOf(0x52,0x61,0x72,0x21,0x1a,0x07,0x01,0x00)

    suspend fun read(source: File, sink: suspend (CbrEntry, ByteArray) -> Unit): CbrDecodedSummary {
        val context = currentCoroutineContext()
        context.ensureActive()
        try {
            if (!source.isFile || source.length() < V4.size) fail()
            if (source.length() > MAX_SOURCE_BYTES) fail(CbrFailure.LIMIT)
            Channel(source, context).use { channel ->
                val signature = ByteArray(minOf(8L, channel.length).toInt())
                channel.readFully(signature, signature.size)
                if (!(signature.take(7).toByteArray().contentEquals(V4) || signature.contentEquals(V5)))
                    fail(CbrFailure.UNSUPPORTED)
                channel.setPosition(0)
                val manager = object : VolumeManager {
                    override fun nextVolume(archive: Archive, previous: Volume?): Volume {
                        if (previous != null) throw StreamFailure(CbrFailure.UNSUPPORTED)
                        return object : Volume {
                            override fun getChannel(): SeekableReadOnlyByteChannel = channel
                            override fun getLength() = channel.length
                            override fun getArchive() = archive
                        }
                    }
                }
                val options = ArchiveOptions.builder().maxDictionarySize(MAX_DICTIONARY_BYTES).build()
                Archive(manager, options).use { archive ->
                    context.ensureActive(); channel.checkFailure()
                    if (archive.hasBrokenHeaders()) fail()
                    if (archive.getFormat() !in setOf(RarFormat.RAR15, RarFormat.RAR50)) fail(CbrFailure.UNSUPPORTED)
                    if (archive.isPasswordProtected()) fail(CbrFailure.PROTECTED)
                    if (archive.getMainHeader()?.isMultiVolume() == true) fail(CbrFailure.UNSUPPORTED)
                    val blocks = archive.getHeaders()
                    if (blocks.any { it is Rar5MainHeader && it.isVolume() }) fail(CbrFailure.UNSUPPORTED)
                    val end = blocks.lastOrNull() ?: fail()
                    val endSize = when (end) {
                        is Rar5MainHeader -> {
                            if (end.getRar5Type() != Rar5BlockType.ENDARC || end.hasData()) fail()
                            if (end.isNextVolume()) fail(CbrFailure.UNSUPPORTED)
                            end.getRar5HeaderSize().toLong()
                        }
                        is EndArcHeader -> {
                            if (!end.isValid()) fail()
                            if (end.getFlags().toInt() and 1 != 0) fail(CbrFailure.UNSUPPORTED)
                            (end.getHeaderSize(false).toInt() and 0xffff).toLong()
                        }
                        else -> fail()
                    }
                    if (endSize <= 0 || end.getPositionInFile() != channel.length - endSize) fail()
                    val headers = archive.getFileHeaders()
                    if (headers.isEmpty()) fail()
                    if (headers.size > MAX_ENTRIES) fail(CbrFailure.LIMIT)
                    val entries = mutableListOf<Pair<FileHeader,CbrEntry>>()
                    val names = HashSet<String>()
                    var total = 0L
                    var images = 0
                    for (header in headers) {
                        context.ensureActive()
                        if (header.isEncrypted()) fail(CbrFailure.PROTECTED)
                        if (header.isSplitBefore() || header.isSplitAfter() || header.getRedirection() != null ||
                            header.isUnpSizeUnknown() || header.getFileVersion() != 0L) fail(CbrFailure.UNSUPPORTED)
                        if (header.getRar5WinSize() > MAX_DICTIONARY_BYTES) fail(CbrFailure.LIMIT)
                        val size = header.getFullUnpackSize()
                        if (size < 0 || header.getFullPackSize() !in 0..MAX_SOURCE_BYTES) fail()
                        val directory = header.isDirectory()
                        val name = normalizeName(header.getFileName(), directory)
                        if (!names.add(Normalizer.normalize(name.removeSuffix("/"), Normalizer.Form.NFC).lowercase(Locale.ROOT))) fail()
                        val unix = header.getHostOS() == HostSystem.unix || header.isRar5Container() && header.getRar5HostOsValue() == 1L
                        val kind = header.getFileAttr() and 0xf000
                        if (unix && kind !in setOf(0, 0x8000, 0x4000)) fail(CbrFailure.UNSUPPORTED)
                        if (directory) {
                            if (size != 0L || header.getFullPackSize() != 0L || unix && kind == 0x8000) fail()
                            continue
                        }
                        if (unix && kind == 0x4000) fail()
                        val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
                        val image = extension in IMAGE_EXTENSIONS
                        if (!image && name.substringAfterLast('/') != "ComicInfo.xml") fail(CbrFailure.UNSUPPORTED)
                        val limit = if (image) MAX_IMAGE_BYTES else 1024L * 1024
                        if (size > limit || total > MAX_EXPANDED_BYTES - size) fail(CbrFailure.LIMIT)
                        if (image && size == 0L) fail()
                        total += size
                        if (image && ++images > MAX_IMAGES) fail(CbrFailure.LIMIT)
                        entries += header to CbrEntry(name, extension, size.toInt())
                    }
                    if (images == 0) fail(CbrFailure.UNSUPPORTED)
                    channel.beginExtraction()
                    for ((header, entry) in entries) {
                        context.ensureActive()
                        val output = ExactBytes(entry.declaredBytes, context)
                        archive.extractFile(header, output)
                        context.ensureActive(); channel.checkFailure()
                        if (output.written != entry.declaredBytes) fail()
                        try { sink(entry, output.bytes) }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (failure: Exception) { throw SinkFailure(failure) }
                    }
                    return CbrDecodedSummary(images, entries.size, total)
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: CbrException) { throw failure }
        catch (failure: SinkFailure) { throw failure.original }
        catch (_: OutOfMemoryError) { fail(CbrFailure.LIMIT) }
        catch (_: StackOverflowError) { fail(CbrFailure.LIMIT) }
        catch (failure: Exception) {
            val chain = generateSequence<Throwable>(failure) { it.cause }.take(16).toList()
            chain.filterIsInstance<CancellationException>().firstOrNull()?.let { throw it }
            chain.filterIsInstance<StreamFailure>().firstOrNull()?.let { fail(it.reason) }
            if (chain.any { it is UnsupportedDictionarySizeException }) fail(CbrFailure.LIMIT)
            if (chain.any { it is UnsupportedRarEncryptedException || it is WrongPasswordException || it is InitDeciphererFailedException }) fail(CbrFailure.PROTECTED)
            if (chain.any { it is UnsupportedRarVersionException || it is UnsupportedRarMethodException || it is MissingNextVolumeException || it is MissingPreviousVolumeException }) fail(CbrFailure.UNSUPPORTED)
            fail()
        }
    }

    private fun normalizeName(raw: String, directory: Boolean): String {
        val name = raw.replace('\\','/').let { if (directory && !it.endsWith('/')) "$it/" else it }
        if (name.isBlank() || name.startsWith('/') || name.length > 1024 ||
            name.any { it.isISOControl() || it in "\\:%?#" } ||
            name.removeSuffix("/").split('/').any { it.isBlank() || it == "." || it == ".." || it != it.trim() }) fail()
        val encoded = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(name))
        if (encoded.remaining() > 1024) fail(CbrFailure.LIMIT)
        return name
    }
    private class SinkFailure(val original: Exception) : RuntimeException(original)
    private class StreamFailure(val reason: CbrFailure) : IOException(reason.name)
    private class ExactBytes(size: Int, private val context: CoroutineContext) : OutputStream() {
        val bytes = ByteArray(size)
        var written = 0; private set
        override fun write(value: Int) { context.ensureActive(); if (written == bytes.size) throw StreamFailure(CbrFailure.INVALID); bytes[written++] = value.toByte() }
        override fun write(source: ByteArray, offset: Int, count: Int) {
            context.ensureActive()
            if (count > bytes.size - written) throw StreamFailure(CbrFailure.INVALID)
            System.arraycopy(source, offset, bytes, written, count); written += count
        }
    }
    private class Channel(source: File, private val context: CoroutineContext) : SeekableReadOnlyByteChannel, Closeable {
        private val file = RandomAccessFile(source,"r")
        val length = file.length()
        private var remaining = 8L * 1024 * 1024
        private var operations = 50_000
        private var failure: CbrFailure? = null
        private var closed = false
        fun checkFailure() { failure?.let(::fail) }
        fun beginExtraction() { checkFailure(); remaining = 2L * 1024 * 1024 * 1024; operations = 2_000_000 }
        private fun stop(reason: CbrFailure): Nothing { failure = reason; throw StreamFailure(reason) }
        private fun check() { context.ensureActive(); if (--operations < 0) stop(CbrFailure.LIMIT) }
        override fun getPosition(): Long { check(); return file.filePointer }
        override fun setPosition(position: Long) { check(); if (position !in 0..length) stop(CbrFailure.INVALID); file.seek(position) }
        override fun read(): Int {
            check(); if (remaining <= 0 && file.filePointer < length) stop(CbrFailure.LIMIT)
            return file.read().also { if (it >= 0) remaining-- }
        }
        override fun read(buffer: ByteArray, offset: Int, count: Int): Int {
            check(); if (count == 0) return 0
            if (remaining <= 0 && file.filePointer < length) stop(CbrFailure.LIMIT)
            return file.read(buffer, offset, minOf(count.toLong(), maxOf(1L,remaining)).toInt()).also { if (it > 0) remaining -= it }
        }
        override fun readFully(buffer: ByteArray, count: Int): Int {
            var done = 0
            while (done < count) { val n = read(buffer, done, count - done); if (n <= 0) stop(CbrFailure.INVALID); done += n }
            return done
        }
        override fun close() { if (!closed) { closed = true; file.close() } }
    }
}
