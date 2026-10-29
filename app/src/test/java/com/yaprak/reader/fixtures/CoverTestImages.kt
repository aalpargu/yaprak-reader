package com.yaprak.reader.fixtures

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream

/** Small, valid, locally generated raster fixtures; no Android compressor or external assets. */
object CoverTestImages {
    fun png(width: Int = 8, height: Int = 12): ByteArray {
        val bytes = ByteArrayOutputStream()
        val output = DataOutputStream(bytes)
        output.write(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10))
        fun chunk(name: String, data: ByteArray) {
            val type = name.toByteArray(Charsets.US_ASCII)
            output.writeInt(data.size); output.write(type); output.write(data)
            output.writeInt(CRC32().apply { update(type); update(data) }.value.toInt())
        }
        val header = ByteArrayOutputStream().also { buffer ->
            DataOutputStream(buffer).use { data ->
                data.writeInt(width); data.writeInt(height)
                data.write(byteArrayOf(8, 6, 0, 0, 0))
            }
        }.toByteArray()
        chunk("IHDR", header)
        val pixels = ByteArrayOutputStream()
        val row = ByteArray(1 + width * 4)
        repeat(width) { index ->
            row[1 + index * 4] = 23
            row[2 + index * 4] = 98
            row[3 + index * 4] = 65
            row[4 + index * 4] = -1
        }
        DeflaterOutputStream(pixels).use { compressed ->
            repeat(height) { compressed.write(row) }
        }
        chunk("IDAT", pixels.toByteArray()); chunk("IEND", ByteArray(0))
        return bytes.toByteArray()
    }
}
