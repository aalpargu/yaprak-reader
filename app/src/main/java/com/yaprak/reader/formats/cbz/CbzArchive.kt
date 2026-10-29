package com.yaprak.reader.formats.cbz

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.ZipFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal enum class CbzFailure { INVALID, LIMIT, UNSUPPORTED, PROTECTED }
internal class CbzException(val reason: CbzFailure) : Exception(reason.name)
internal fun cbzFail(reason: CbzFailure = CbzFailure.INVALID): Nothing = throw CbzException(reason)

internal data class CbzEntry(val name: String, val size: Long, val compressed: Long, val crc: Long,
    val method: Int, val flags: Int, val offset: Long)
internal data class CbzPage(val entry: CbzEntry, val image: CbzImage, val sha256: String)
internal data class CbzArchive(val pages: List<CbzPage>) {
    companion object {
        const val MAX_ENTRIES = 2048
        const val MAX_PAGES = 2000
        const val MAX_BYTES = 512L * 1024 * 1024
        const val MAX_IMAGE_BYTES = 64L * 1024 * 1024

        /** Every entry and image is checked before admission; no extraction or first-page shortcut. */
        suspend fun read(file: File): CbzArchive {
            try {
                val entries = index(file)
                val pages = ArrayList<CbzPage>()
                ZipFile(file, Charsets.UTF_8).use { zip ->
                    val actual = zip.entries().asSequence().toList()
                    if (actual.map { it.name } != entries.map { it.name }) cbzFail()
                    for (entry in entries) {
                        currentCoroutineContext().ensureActive()
                        val bytes = readEntry(zip, entry)
                        if (entry.name.endsWith('/')) continue
                        val extension = entry.name.substringAfterLast('.', "").lowercase(Locale.ROOT)
                        if (extension in setOf("jpg", "jpeg", "png", "webp")) {
                            if (pages.size >= MAX_PAGES) cbzFail(CbzFailure.LIMIT)
                            val image = CbzImages.validate(bytes, extension)
                            pages += CbzPage(entry, image, cbzHash(bytes))
                        } else if (entry.name.substringAfterLast('/') != "ComicInfo.xml") {
                            cbzFail(CbzFailure.UNSUPPORTED)
                        }
                    }
                }
                if (pages.isEmpty()) cbzFail()
                return CbzArchive(pages.sortedWith { a, b -> cbzNaturalOrder(a.entry.name, b.entry.name) })
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: CbzException) { throw failure }
            catch (_: OutOfMemoryError) { cbzFail(CbzFailure.LIMIT) }
            catch (_: Exception) { cbzFail() }
        }

        /** ZIP32 only, strict matching central/local headers, no encryption, aliasing or trailing data. */
        internal suspend fun index(file: File): List<CbzEntry> = RandomAccessFile(file, "r").use { input ->
            val length = input.length()
            if (length > MAX_BYTES) cbzFail(CbzFailure.LIMIT)
            if (length < 22) cbzFail()
            val tail = ByteArray(minOf(length, 65557L).toInt())
            input.seek(length - tail.size); input.readFully(tail)
            val end = (tail.size - 22 downTo 0).firstOrNull { i ->
                tail.u32(i) == 0x06054b50L && i + 22 + tail.u16(i + 20) == tail.size
            } ?: cbzFail()
            if (tail.u16(end + 4) != 0 || tail.u16(end + 6) != 0 ||
                tail.u16(end + 8) != tail.u16(end + 10)) cbzFail()
            val count = tail.u16(end + 10)
            if (count !in 1..MAX_ENTRIES) cbzFail(CbzFailure.LIMIT)
            val centralSize = tail.u32(end + 12); val centralOffset = tail.u32(end + 16)
            if (centralOffset + centralSize != length - tail.size + end) cbzFail()
            val entries = ArrayList<CbzEntry>(); val names = HashSet<String>()
            var expanded = 0L
            input.seek(centralOffset)
            repeat(count) {
                currentCoroutineContext().ensureActive()
                val header = ByteArray(46); input.readFully(header)
                if (header.u32(0) != 0x02014b50L || header.u16(34) != 0) cbzFail()
                val flags = header.u16(8); val method = header.u16(10)
                if (flags and 1 != 0 || flags and 64 != 0) cbzFail(CbzFailure.PROTECTED)
                if (flags and 0x0808.inv() != 0 || method !in setOf(0, 8)) cbzFail(CbzFailure.UNSUPPORTED)
                // Non-UTF8 names are accepted only when ASCII: identical decoding on every supported host.
                val nameSize = header.u16(28); val extraSize = header.u16(30); val commentSize = header.u16(32)
                if (nameSize !in 1..1024) cbzFail(CbzFailure.LIMIT)
                val rawName = ByteArray(nameSize); input.readFully(rawName)
                if (flags and 0x800 == 0 && rawName.any { it < 0 }) cbzFail(CbzFailure.UNSUPPORTED)
                val name = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(rawName)).toString()
                if (!safeCbzName(name) || !names.add(Normalizer.normalize(name, Normalizer.Form.NFC).lowercase(Locale.ROOT))) cbzFail()
                val extra = ByteArray(extraSize); input.readFully(extra); validateExtra(extra)
                input.seek(input.filePointer + commentSize)
                val size = header.u32(24); val compressed = header.u32(20)
                val image = name.substringAfterLast('.', "").lowercase(Locale.ROOT) in setOf("png", "jpg", "jpeg", "webp")
                if (size > (if (image) MAX_IMAGE_BYTES else 1024L * 1024)) cbzFail(CbzFailure.LIMIT)
                expanded += size
                if (expanded > MAX_BYTES || compressed > MAX_BYTES || name.endsWith('/') && size != 0L) cbzFail(CbzFailure.LIMIT)
                val mode = (header.u32(38) ushr 16).toInt() and 0xf000
                if (mode !in setOf(0, 0x8000, 0x4000)) cbzFail() // No symlink or special file.
                entries += CbzEntry(name, size, compressed, header.u32(16), method, flags, header.u32(42))
            }
            if (input.filePointer != centralOffset + centralSize) cbzFail()
            if (entries.count { it.name.substringAfterLast('.', "").lowercase(Locale.ROOT) in setOf("jpg", "jpeg", "png", "webp") } > MAX_PAGES) cbzFail(CbzFailure.LIMIT)
            var next = 0L
            for (entry in entries.sortedBy { it.offset }) {
                currentCoroutineContext().ensureActive()
                if (entry.offset != next) cbzFail()
                input.seek(next); val header = ByteArray(30); input.readFully(header)
                if (header.u32(0) != 0x04034b50L || header.u16(6) != entry.flags || header.u16(8) != entry.method) cbzFail()
                val rawName = ByteArray(header.u16(26)); input.readFully(rawName)
                if (!rawName.contentEquals(entry.name.toByteArray(Charsets.UTF_8))) cbzFail()
                val extra = ByteArray(header.u16(28)); input.readFully(extra); validateExtra(extra)
                if (entry.flags and 8 == 0 && (header.u32(14) != entry.crc || header.u32(18) != entry.compressed || header.u32(22) != entry.size)) cbzFail()
                if (entry.flags and 8 != 0 && (header.u32(14) !in setOf(0L, entry.crc) ||
                    header.u32(18) !in setOf(0L, entry.compressed) || header.u32(22) !in setOf(0L, entry.size))) cbzFail()
                next = input.filePointer + entry.compressed
                if (next > centralOffset) cbzFail()
                if (entry.flags and 8 != 0) {
                    input.seek(next); val first = ByteArray(4); input.readFully(first)
                    val signed = first.u32(0) == 0x08074b50L
                    val descriptor = ByteArray(12)
                    if (signed) input.readFully(descriptor) else { first.copyInto(descriptor); input.readFully(descriptor, 4, 8) }
                    if (descriptor.u32(0) != entry.crc || descriptor.u32(4) != entry.compressed || descriptor.u32(8) != entry.size) cbzFail()
                    next = input.filePointer
                }
            }
            if (next != centralOffset) cbzFail()
            entries
        }

        internal suspend fun readEntry(zip: ZipFile, entry: CbzEntry): ByteArray {
            val actual = zip.getEntry(entry.name) ?: cbzFail()
            if (actual.size != entry.size || actual.compressedSize != entry.compressed || actual.crc != entry.crc || actual.method != entry.method) cbzFail()
            val output = ByteArrayOutputStream(entry.size.toInt()); val crc = CRC32()
            zip.getInputStream(actual).use { input ->
                val buffer = ByteArray(32768); var total = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer); if (count < 0) break
                    total += count
                    if (total > entry.size || total > MAX_IMAGE_BYTES) cbzFail(CbzFailure.LIMIT)
                    output.write(buffer, 0, count); crc.update(buffer, 0, count)
                }
                if (total != entry.size || crc.value != entry.crc) cbzFail()
            }
            return output.toByteArray()
        }

        private fun validateExtra(bytes: ByteArray) {
            var at = 0
            while (at < bytes.size) {
                if (at + 4 > bytes.size) cbzFail()
                val kind = bytes.u16(at); val size = bytes.u16(at + 2)
                if (kind in setOf(1, 0x9901, 0x7075)) cbzFail(CbzFailure.UNSUPPORTED)
                at += 4 + size; if (at > bytes.size) cbzFail()
            }
        }
    }
}

internal fun safeCbzName(name: String): Boolean = name.isNotBlank() && name.length <= 1024 &&
    !name.startsWith('/') && name.none { it.isISOControl() || it in "\\:%?#" } &&
    name.removeSuffix("/").split('/').all { it.isNotBlank() && it != "." && it != ".." && it == it.trim() }

internal fun cbzHash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
    .joinToString("") { "%02x".format(it) }
internal fun ByteArray.u16(at: Int): Int = (this[at].toInt() and 255) or ((this[at + 1].toInt() and 255) shl 8)
internal fun ByteArray.u32(at: Int): Long = (0..3).sumOf { (this[at + it].toLong() and 255) shl (8 * it) }

/** Numeric runs are compared without converting potentially huge filenames to machine integers. */
internal fun cbzNaturalOrder(first: String, second: String): Int {
    val a = first.lowercase(Locale.ROOT); val b = second.lowercase(Locale.ROOT)
    var x = 0; var y = 0
    while (x < a.length && y < b.length) {
        if (a[x] in '0'..'9' && b[y] in '0'..'9') {
            val xs = x; val ys = y
            while (x < a.length && a[x] in '0'..'9') x++
            while (y < b.length && b[y] in '0'..'9') y++
            val an = a.substring(xs, x).trimStart('0').ifEmpty { "0" }
            val bn = b.substring(ys, y).trimStart('0').ifEmpty { "0" }
            val compared = an.length.compareTo(bn.length).takeIf { it != 0 } ?: an.compareTo(bn)
            if (compared != 0) return compared
            val length = (x - xs).compareTo(y - ys); if (length != 0) return length
        } else {
            val compared = a[x++].compareTo(b[y++]); if (compared != 0) return compared
        }
    }
    return (a.length - x).compareTo(b.length - y).takeIf { it != 0 } ?: first.compareTo(second)
}
