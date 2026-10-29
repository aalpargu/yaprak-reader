package com.yaprak.reader.formats.fb2

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.Inflater
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal fun hasFb2ZipSignature(bytes: ByteArray) = bytes.size >= 4 &&
    bytes[0] == 0x50.toByte() && bytes[1] == 0x4b.toByte() && bytes[2] == 3.toByte() && bytes[3] == 4.toByte()

/** Single-file ZIP32 profile. Validates the entire archive; never extracts a path to disk. */
internal object Fb2Zip {
    suspend fun xml(bytes: ByteArray, checkActive: () -> Unit = {}): ByteArray {
        val caller = currentCoroutineContext()
        fun active() { caller.ensureActive(); checkActive() }
        active()
        try {
            if (bytes.size > MAX_FB2_BYTES) fail(Fb2Failure.LIMIT)
            if (!hasFb2ZipSignature(bytes) || bytes.size < 22) fail()
            val end = (bytes.size - 22 downTo maxOf(0, bytes.size - 65557)).firstOrNull { at ->
                bytes.u32(at) == 0x06054b50L && at + 22 + bytes.u16(at + 20) == bytes.size
            } ?: fail()
            if (bytes.u16(end + 4) != 0 || bytes.u16(end + 6) != 0) fail(Fb2Failure.UNSUPPORTED)
            val count = bytes.u16(end + 10)
            if (count == 0 || bytes.u16(end + 8) != count) fail()
            if (count != 1) fail(Fb2Failure.UNSUPPORTED)
            val centralSize = bytes.u32(end + 12)
            val centralOffset = bytes.u32(end + 16)
            if (centralOffset + centralSize != end.toLong() || centralOffset > bytes.size) fail()
            val at = centralOffset.toInt()
            bytes.need(at, 46)
            if (bytes.u32(at) != 0x02014b50L || bytes.u16(at + 34) != 0 || bytes.u32(at + 42) != 0L) fail()
            val version = bytes.u16(at + 6)
            val flags = bytes.u16(at + 8)
            val method = bytes.u16(at + 10)
            if (version !in 10..20 || flags and 0x080e.inv() != 0 || method !in setOf(0, 8) ||
                method == 0 && flags and 6 != 0) fail(Fb2Failure.UNSUPPORTED)
            val crc = bytes.u32(at + 16)
            val compressed = bytes.u32(at + 20)
            val expanded = bytes.u32(at + 24)
            if (compressed > MAX_FB2_BYTES || expanded > MAX_FB2_BYTES) fail(Fb2Failure.LIMIT)
            if (expanded == 0L) fail()
            val nameLength = bytes.u16(at + 28)
            val extraLength = bytes.u16(at + 30)
            val commentLength = bytes.u16(at + 32)
            if (nameLength !in 1..1024) fail(Fb2Failure.LIMIT)
            if (at.toLong() + 46 + nameLength + extraLength + commentLength != end.toLong()) fail()
            val rawName = bytes.copyOfRange(at + 46, at + 46 + nameLength)
            if (flags and 0x800 == 0 && rawName.any { it < 0 }) fail(Fb2Failure.UNSUPPORTED)
            val name = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(rawName)).toString()
            if (name.isBlank() || name.startsWith('/') || name.any { it.isISOControl() || it in "\\:%?#" } ||
                name.split('/').any { it.isBlank() || it in setOf(".", "..") || it != it.trim() }) fail()
            if (!name.lowercase(Locale.ROOT).endsWith(".fb2")) fail(Fb2Failure.UNSUPPORTED)
            val attributes = bytes.u32(at + 38)
            val mode = (attributes ushr 16).toInt() and 0xf000
            if (mode !in setOf(0, 0x8000) || attributes and 0x10L != 0L) fail()
            extra(bytes, at + 46 + nameLength, extraLength)

            bytes.need(0, 30)
            if (bytes.u32(0) != 0x04034b50L || bytes.u16(4) != version || bytes.u16(6) != flags ||
                bytes.u16(8) != method || bytes.u16(26) != nameLength) fail()
            val localExtra = bytes.u16(28)
            val data = 30 + nameLength + localExtra
            if (data.toLong() + compressed > centralOffset) fail()
            if (!bytes.copyOfRange(30, 30 + nameLength).contentEquals(rawName)) fail()
            extra(bytes, 30 + nameLength, localExtra)
            val localFields = listOf(bytes.u32(14), bytes.u32(18), bytes.u32(22))
            val actualFields = listOf(crc, compressed, expanded)
            if (localFields.zip(actualFields).any { (local, expected) ->
                    local != expected && (flags and 8 == 0 || local != 0L)
                }) fail()
            var after = data + compressed.toInt()
            if (flags and 8 != 0) {
                if (bytes.u32(after) == 0x08074b50L) after += 4
                if (after.toLong() + 12 != centralOffset || bytes.u32(after) != crc ||
                    bytes.u32(after + 4) != compressed || bytes.u32(after + 8) != expanded) fail()
                after += 12
            }
            if (after.toLong() != centralOffset) fail()
            active()
            val output = if (method == 0) {
                if (compressed != expanded) fail()
                bytes.copyOfRange(data, data + compressed.toInt())
            } else {
                val inflater = Inflater(true)
                try {
                    inflater.setInput(bytes, data, compressed.toInt())
                    val out = ByteArrayOutputStream(minOf(expanded.toInt(), 64 * 1024))
                    val buffer = ByteArray(32 * 1024)
                    while (!inflater.finished()) {
                        active()
                        val read = inflater.inflate(buffer)
                        if (read > expanded - out.size() || read > MAX_FB2_BYTES - out.size()) fail(Fb2Failure.LIMIT)
                        if (read > 0) out.write(buffer, 0, read)
                        else if (!inflater.finished()) fail()
                    }
                    if (inflater.remaining != 0 || out.size().toLong() != expanded) fail()
                    out.toByteArray()
                } finally { inflater.end() }
            }
            active()
            if (CRC32().apply { update(output) }.value != crc) fail()
            active()
            return output
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Fb2Exception) { throw failure }
        catch (_: OutOfMemoryError) { fail(Fb2Failure.LIMIT) }
        catch (_: Exception) { fail() }
    }

    private fun extra(bytes: ByteArray, start: Int, count: Int) {
        bytes.need(start, count)
        var at = start
        val end = start + count
        while (at < end) {
            if (end - at < 4) fail()
            val kind = bytes.u16(at)
            if (kind in setOf(1, 0x9901, 0x7075, 0x6375)) fail(Fb2Failure.UNSUPPORTED)
            val size = bytes.u16(at + 2)
            at += 4
            if (size > end - at) fail()
            at += size
        }
    }

    private fun ByteArray.need(at: Int, count: Int) { if (at < 0 || count < 0 || at > size - count) fail() }
    private fun ByteArray.u16(at: Int): Int { need(at, 2); return (this[at].toInt() and 255) or ((this[at + 1].toInt() and 255) shl 8) }
    private fun ByteArray.u32(at: Int): Long { need(at, 4); return (0..3).sumOf { (this[at + it].toLong() and 255) shl (it * 8) } }
    private fun fail(reason: Fb2Failure = Fb2Failure.INVALID): Nothing = throw Fb2Exception(reason)
}
