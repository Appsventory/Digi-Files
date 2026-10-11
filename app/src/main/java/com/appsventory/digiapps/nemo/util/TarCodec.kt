package com.appsventory.digiapps.nemo.util

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/*
 * Minimal TAR reader / writer (ustar + GNU long names + PAX paths).
 *
 * Written by hand on purpose: commons-compress' TarArchiveEntry depends on java.nio.file.attribute.FileTime,
 * which doesn't exist below Android 8 (this app supports Android 7). Streams only, nothing else.
 */

private const val BLOCK = 512

/** Reads entries one after another from a (already decompressed) tar stream. */
class TarReader(private val input: InputStream) {

    class Entry(
        val name: String,
        val size: Long,
        val isDirectory: Boolean,
        /** A regular file (links, devices... are neither a file nor a directory and get skipped). */
        val isFile: Boolean,
        val modifiedMillis: Long
    )

    private val header = ByteArray(BLOCK)
    private var remaining = 0L
    private var padding = 0L

    /** Data of the current entry (ends after its size). */
    val data: InputStream = object : InputStream() {
        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (remaining <= 0) return -1
            val n = input.read(b, off, minOf(len.toLong(), remaining).toInt())
            if (n < 0) throw EOFException("Unexpected end of tar data")
            remaining -= n
            return n
        }
    }

    /** Moves to the next entry (what is left of the current one is skipped). Null at the end of the archive. */
    fun next(): Entry? {
        skipFully(remaining + padding)
        remaining = 0
        padding = 0
        var longName: String? = null
        var paxPath: String? = null
        var paxSize = -1L
        while (true) {
            if (!readBlock()) return null
            if (header.all { it == 0.toByte() }) return null // end-of-archive marker
            val type = header[156].toInt().toChar()
            val size = parseNumber(header, 124, 12)
            when (type) {
                'L' -> { // GNU long name: the data is the real name
                    longName = String(readBytes(size), Charsets.UTF_8).trimEnd('\u0000')
                    continue
                }
                'x' -> { // PAX extended header for the next entry
                    val pax = parsePax(readBytes(size))
                    pax["path"]?.let { paxPath = it }
                    pax["size"]?.toLongOrNull()?.let { paxSize = it }
                    continue
                }
                'g', 'K' -> { // global PAX header / GNU long link target: not needed
                    skipFully(size + padFor(size))
                    continue
                }
            }
            var name = longName ?: paxPath ?: headerName()
            val isDir = type == '5' || name.endsWith("/")
            val isFile = type == '0' || type == '\u0000' || type == '7'
            name = name.trimEnd('/')
            val realSize = if (paxSize >= 0) paxSize else size
            // only regular files carry data we read; a link's size field is meaningless
            remaining = if (isFile) realSize else 0
            padding = if (isFile) padFor(realSize) else 0
            if (!isFile && !isDir && size > 0) skipFully(size + padFor(size)) // a link / device with data: skip it
            return Entry(name, realSize, isDir, isFile, parseNumber(header, 136, 12) * 1000)
        }
    }

    private fun headerName(): String {
        val name = cString(header, 0, 100)
        val isUstar = cString(header, 257, 5) == "ustar"
        val prefix = if (isUstar) cString(header, 345, 155) else ""
        return if (prefix.isNotEmpty()) "$prefix/$name" else name
    }

    private fun readBlock(): Boolean {
        var read = 0
        while (read < BLOCK) {
            val n = input.read(header, read, BLOCK - read)
            if (n < 0) {
                if (read == 0) return false
                throw EOFException("Truncated tar header")
            }
            read += n
        }
        return true
    }

    private fun readBytes(size: Long): ByteArray {
        if (size < 0 || size > 1_000_000) throw IOException("Bad tar header size")
        val out = ByteArray(size.toInt())
        var read = 0
        while (read < out.size) {
            val n = input.read(out, read, out.size - read)
            if (n < 0) throw EOFException("Truncated tar data")
            read += n
        }
        skipFully(padFor(size))
        return out
    }

    private fun skipFully(count: Long) {
        var left = count
        if (left <= 0) return
        val sink = ByteArray(8192)
        while (left > 0) {
            val skipped = input.skip(left)
            if (skipped > 0) {
                left -= skipped
            } else {
                val n = input.read(sink, 0, minOf(sink.size.toLong(), left).toInt())
                if (n < 0) throw EOFException("Truncated tar data")
                left -= n
            }
        }
    }

    private fun padFor(size: Long): Long = (BLOCK - size % BLOCK) % BLOCK

    private fun cString(buf: ByteArray, off: Int, len: Int): String {
        var end = off
        while (end < off + len && buf[end] != 0.toByte()) end++
        return String(buf, off, end - off, Charsets.UTF_8)
    }

    /** Octal text, or the binary form (high bit set) used for numbers that don't fit. */
    private fun parseNumber(buf: ByteArray, off: Int, len: Int): Long {
        if (buf[off].toInt() and 0x80 != 0) {
            var value = (buf[off].toInt() and 0x7F).toLong()
            for (i in 1 until len) value = (value shl 8) or (buf[off + i].toLong() and 0xFF)
            return value
        }
        val text = cString(buf, off, len).trim()
        return if (text.isEmpty()) 0 else text.toLongOrNull(8) ?: 0
    }

    /** "<length> <key>=<value>\n" records. */
    private fun parsePax(bytes: ByteArray): Map<String, String> {
        val out = HashMap<String, String>()
        var pos = 0
        while (pos < bytes.size) {
            var sp = pos
            while (sp < bytes.size && bytes[sp] != ' '.code.toByte()) sp++
            val length = String(bytes, pos, sp - pos, Charsets.US_ASCII).toIntOrNull() ?: break
            if (length <= 0 || pos + length > bytes.size) break
            val record = String(bytes, sp + 1, pos + length - sp - 2, Charsets.UTF_8) // without the newline
            val eq = record.indexOf('=')
            if (eq > 0) out[record.substring(0, eq)] = record.substring(eq + 1)
            pos += length
        }
        return out
    }
}

/** Writes a tar stream. Call [finish] last (it writes the end marker); closing the stream stays with the caller. */
class TarWriter(private val out: OutputStream) {

    fun putDirectory(name: String, modifiedMillis: Long) {
        writeHeader(name.trimEnd('/') + "/", 0, modifiedMillis, '5', 493 /* 0755 */)
    }

    /** Writes [size] bytes from [data]; if the file shrank meanwhile the rest is zero-filled so the archive stays valid. */
    fun putFile(
        name: String,
        size: Long,
        modifiedMillis: Long,
        data: InputStream,
        check: () -> Unit,
        onBytes: (Long) -> Unit
    ) {
        writeHeader(name, size, modifiedMillis, '0', 420 /* 0644 */)
        val buffer = ByteArray(64 * 1024)
        var left = size
        while (left > 0) {
            check()
            val n = data.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
            if (n < 0) break
            out.write(buffer, 0, n)
            left -= n
            onBytes(n.toLong())
        }
        while (left > 0) { // file got shorter while archiving
            val n = minOf(buffer.size.toLong(), left).toInt()
            java.util.Arrays.fill(buffer, 0, n, 0)
            out.write(buffer, 0, n)
            left -= n
        }
        val pad = ((BLOCK - size % BLOCK) % BLOCK).toInt()
        if (pad > 0) out.write(ByteArray(pad))
    }

    fun finish() {
        out.write(ByteArray(BLOCK * 2))
    }

    private fun writeHeader(name: String, size: Long, modifiedMillis: Long, type: Char, mode: Int) {
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        if (nameBytes.size > 100) {
            // GNU long name: a special entry whose data is the full name
            val data = nameBytes + 0.toByte()
            writeRaw("././@LongLink".toByteArray(), data.size.toLong(), 0, 'L', 0)
            out.write(data)
            val pad = ((BLOCK - data.size % BLOCK) % BLOCK).toInt()
            if (pad > 0) out.write(ByteArray(pad))
        }
        writeRaw(nameBytes.copyOf(minOf(nameBytes.size, 100)), size, modifiedMillis, type, mode)
    }

    private fun writeRaw(nameBytes: ByteArray, size: Long, modifiedMillis: Long, type: Char, mode: Int) {
        val h = ByteArray(BLOCK)
        System.arraycopy(nameBytes, 0, h, 0, nameBytes.size)
        putOctal(h, 100, 8, mode.toLong())
        putOctal(h, 108, 8, 0) // uid
        putOctal(h, 116, 8, 0) // gid
        if (size > 8_589_934_591L) { // doesn't fit 11 octal digits: binary form
            h[124] = 0x80.toByte()
            for (i in 0 until 11) h[135 - i] = (size shr (8 * i)).toByte()
        } else {
            putOctal(h, 124, 12, size)
        }
        putOctal(h, 136, 12, maxOf(0, modifiedMillis / 1000))
        h[156] = type.code.toByte()
        "ustar".toByteArray().copyInto(h, 257) // magic "ustar\0"
        h[263] = '0'.code.toByte() // version "00"
        h[264] = '0'.code.toByte()
        // checksum: sum of all header bytes with the checksum field counted as spaces
        for (i in 148 until 156) h[i] = ' '.code.toByte()
        var sum = 0
        for (b in h) sum += b.toInt() and 0xFF
        val text = Integer.toOctalString(sum).padStart(6, '0')
        text.toByteArray().copyInto(h, 148)
        h[154] = 0
        h[155] = ' '.code.toByte()
        out.write(h)
    }

    /** Zero-padded octal in [len] bytes, last byte NUL. */
    private fun putOctal(buf: ByteArray, off: Int, len: Int, value: Long) {
        val digits = java.lang.Long.toOctalString(value).padStart(len - 1, '0')
        digits.toByteArray().copyInto(buf, off, 0, len - 1)
        buf[off + len - 1] = 0
    }
}
