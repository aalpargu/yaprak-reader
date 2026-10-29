package com.yaprak.reader.formats.cbr

import com.yaprak.reader.formats.cbz.CbzArchive
import com.yaprak.reader.formats.cbz.ComicArchiveFileLease
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One application-owned instance. All IO is performed by callers on IO, never by close().
 * A disk file alone is never an admission proof: a new process decodes the original again.
 * The original RAR is preserved. No archive path is ever extracted into the filesystem. */
class CbrPublicationCache(private val baseDirectory: File) {
    private val mutex = Mutex()
    private val records = mutableListOf<Record>()
    private var initialized = false
    private var clock = 0L
    private val directory = File(baseDirectory, "cbr-reader-v1")

    internal suspend fun acquire(source: File): Lease = mutex.withLock {
        currentCoroutineContext().ensureActive()
        prepareDirectory()
        reapRetired()
        val sourceHash = hash(source)
        val hit = records.firstOrNull { !it.retired.get() && it.sourceHash == sourceHash }
        if (hit != null) {
            if (owned(hit.file) && hit.file.isFile && hit.file.length() == hit.size && hash(hit.file) == hit.derivedHash &&
                hash(source) == sourceHash) {
                hit.lastUse = ++clock
                return@withLock lease(hit)
            }
            hit.retired.set(true)
        }
        val temporary = File(directory, "$PROCESS-${UUID.randomUUID()}.part")
        var published: File? = null
        try {
            checkOwned(temporary)
            if (!temporary.createNewFile()) invalid()
            val summary = FileOutputStream(temporary).use { output ->
                val bounded = LimitedOutput(output)
                ZipOutputStream(bounded, Charsets.UTF_8).use { zip ->
                    val result = CbrArchiveTransport.read(source) { entry, bytes ->
                        currentCoroutineContext().ensureActive()
                        zip.putNextEntry(ZipEntry(entry.name).apply { time = 0L })
                        zip.write(bytes)
                        zip.closeEntry()
                    }
                    zip.finish(); zip.flush(); output.fd.sync()
                    result
                }
            }
            // This checks every container, CRC, raster and native decode, including later pages.
            val archive = CbzArchive.read(temporary)
            if (archive.pages.size != summary.imageCount) invalid()
            val derivedHash = hash(temporary)
            if (hash(source) != sourceHash) invalid()
            currentCoroutineContext().ensureActive()
            val size = temporary.length()
            makeRoom(size)
            val target = File(directory, temporary.name.removeSuffix(".part") + ".cbz")
            checkOwned(target)
            if (target.exists() || !temporary.renameTo(target)) invalid()
            published = target
            val record = Record(target, archive, sourceHash, derivedHash, size, ++clock)
            records += record
            val result = lease(record)
            published = null
            result
        } finally {
            if (owned(temporary)) temporary.delete()
            published?.takeIf(::owned)?.delete()
        }
    }

    private fun prepareDirectory() {
        if (!baseDirectory.isDirectory && !baseDirectory.mkdirs()) invalid()
        if (directory.canonicalFile != File(baseDirectory.canonicalFile, directory.name)) invalid()
        if (!directory.isDirectory && !directory.mkdir()) invalid()
        if (!initialized) {
            // Only this cache's flat, generated names from previous processes are eligible.
            directory.listFiles()?.filter { file -> owned(file) && file.isFile && !file.name.startsWith("$PROCESS-") }
                ?.forEach { if (!it.delete()) invalid() }
            initialized = true
        }
    }

    private fun makeRoom(size: Long) {
        if (size !in 1..MAX_BYTES) limit()
        fun remove(record: Record) {
            record.retired.set(true)
            if (record.pins.get() != 0) invalid()
            checkOwned(record.file)
            if (record.file.exists() && !record.file.delete()) invalid()
            records.remove(record)
        }
        records.toList().filter { it.retired.get() && it.pins.get() == 0 }.forEach(::remove)
        while (records.size >= MAX_RECORDS || records.sumOf { it.size } > MAX_BYTES - size) {
            val victim = records.filter { it.pins.get() == 0 }.minByOrNull { it.lastUse } ?: limit()
            remove(victim)
        }
    }

    private fun reapRetired() {
        records.toList().filter { it.retired.get() && it.pins.get() == 0 }.forEach { record ->
            checkOwned(record.file)
            if (record.file.exists() && !record.file.delete()) invalid()
            records.remove(record)
        }
    }

    private fun lease(record: Record): Lease {
        if (record.retired.get()) invalid()
        record.pins.incrementAndGet()
        return Lease(record)
    }

    internal class Lease internal constructor(private val record: Record) : Closeable {
        private val closed = AtomicBoolean(false)
        val archive get() = record.archive
        val sourceHash get() = record.sourceHash
        /** Child pins cover a complete frame/thumbnail read even if its root session closes. */
        fun retainFile(expectedHash: String): ComicArchiveFileLease {
            if (closed.get() || record.retired.get() || record.sourceHash != expectedHash) invalid()
            record.pins.incrementAndGet()
            if (closed.get() || record.retired.get()) {
                record.pins.decrementAndGet(); invalid()
            }
            return ComicArchiveFileLease(record.file) { record.pins.decrementAndGet() }
        }
        override fun close() { if (closed.compareAndSet(false, true)) record.pins.decrementAndGet() }
    }

    internal class Record(val file: File, val archive: CbzArchive, val sourceHash: String,
        val derivedHash: String, val size: Long, var lastUse: Long) {
        val pins = AtomicInteger(0)
        val retired = AtomicBoolean(false)
    }
    private fun owned(file: File) = FILE_NAME.matches(file.name) && file.canonicalFile == File(directory.canonicalFile, file.name)
    private fun checkOwned(file: File) { if (!owned(file)) invalid() }
    private class LimitedOutput(private val output: OutputStream) : OutputStream() {
        private var written = 0L
        override fun write(value: Int) { if (written >= MAX_BYTES) limit(); output.write(value); written++ }
        override fun write(bytes: ByteArray, offset: Int, count: Int) {
            currentCheck(count); output.write(bytes, offset, count); written += count
        }
        private fun currentCheck(count: Int) { if (count < 0 || count > MAX_BYTES - written) limit() }
        override fun flush() = output.flush()
        override fun close() = output.close()
    }
    private companion object {
        const val MAX_BYTES = 512L * 1024 * 1024
        const val MAX_RECORDS = 8
        val PROCESS = UUID.randomUUID().toString()
        val FILE_NAME = Regex("[0-9a-f-]{36}-[0-9a-f-]{36}\\.(part|cbz)")
        fun invalid(): Nothing = throw CbrException(CbrFailure.INVALID)
        fun limit(): Nothing = throw CbrException(CbrFailure.LIMIT)
        suspend fun hash(file: File): String {
            if (!file.isFile || file.length() < 1) invalid()
            if (file.length() > MAX_BYTES) limit()
            val digest = MessageDigest.getInstance("SHA-256")
            var count = 0L
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    count += read
                    if (count > MAX_BYTES) limit()
                    digest.update(buffer, 0, read)
                }
            }
            if (count != file.length()) invalid()
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
