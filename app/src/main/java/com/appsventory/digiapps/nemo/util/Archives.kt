package com.appsventory.digiapps.nemo.util

import com.github.junrar.Archive
import com.github.junrar.exception.RarException
import com.github.junrar.exception.UnsupportedRarEncryptedException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import net.lingala.zip4j.exception.ZipException
import net.lingala.zip4j.io.outputstream.ZipOutputStream as AesZipOutputStream
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.CompressionLevel as AesZipLevel
import net.lingala.zip4j.model.enums.CompressionMethod
import net.lingala.zip4j.model.enums.EncryptionMethod
import net.lingala.zip4j.ZipFile as AesZipFile
import org.apache.commons.compress.PasswordRequiredException
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.sevenz.SevenZMethod
import org.apache.commons.compress.archivers.sevenz.SevenZMethodConfiguration
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.tukaani.xz.LZMA2Options
import org.tukaani.xz.XZInputStream
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.util.Date
import java.util.zip.Deflater
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Archive types by file name. [canCreate]: can this app also make them (RAR can't be written by anybody but WinRAR). */
enum class ArchiveFormat(val label: String, val extension: String, val canCreate: Boolean) {
    ZIP("ZIP", "zip", true),
    SEVEN_Z("7z", "7z", true),
    RAR("RAR", "rar", false),
    TAR("TAR", "tar", true),
    TAR_GZ("TAR.GZ", "tar.gz", true),
    TAR_BZ2("TAR.BZ2", "tar.bz2", false),
    TAR_XZ("TAR.XZ", "tar.xz", false),
    GZ("GZ", "gz", false),
    BZ2("BZ2", "bz2", false),
    XZ("XZ", "xz", false)
}

// longest suffix first: "a.tar.gz" is a TAR_GZ, not a GZ
private val ARCHIVE_SUFFIXES = listOf(
    ".tar.gz" to ArchiveFormat.TAR_GZ, ".tgz" to ArchiveFormat.TAR_GZ,
    ".tar.bz2" to ArchiveFormat.TAR_BZ2, ".tbz2" to ArchiveFormat.TAR_BZ2, ".tbz" to ArchiveFormat.TAR_BZ2,
    ".tar.xz" to ArchiveFormat.TAR_XZ, ".txz" to ArchiveFormat.TAR_XZ,
    ".tar" to ArchiveFormat.TAR, ".zip" to ArchiveFormat.ZIP, ".7z" to ArchiveFormat.SEVEN_Z,
    ".rar" to ArchiveFormat.RAR, ".gz" to ArchiveFormat.GZ, ".bz2" to ArchiveFormat.BZ2, ".xz" to ArchiveFormat.XZ
)

fun archiveFormatOf(fileName: String): ArchiveFormat? {
    val lower = fileName.lowercase()
    return ARCHIVE_SUFFIXES.firstOrNull { lower.endsWith(it.first) && lower.length > it.first.length }?.second
}

/** "photos.tar.gz" -> "photos" (the name an "extract to folder" uses). */
fun archiveBaseName(fileName: String): String {
    val lower = fileName.lowercase()
    val suffix = ARCHIVE_SUFFIXES.firstOrNull { lower.endsWith(it.first) && lower.length > it.first.length }?.first
    return if (suffix != null) fileName.dropLast(suffix.length) else fileName
}

/** How hard to compress when creating an archive. TAR has no compression at all, so it ignores this. */
enum class ArchiveLevel { STORE, FAST, NORMAL, MAXIMUM }

/** A password was asked for, but the finished archive doesn't need one: it was removed rather than handed over as "protected". */
class ArchiveNotEncryptedException : IOException("The archive was written without encryption")

/** The archive is encrypted and the password is missing ([wrong] = false) or doesn't fit ([wrong] = true). */
class ArchivePasswordException(val wrong: Boolean) : IOException(if (wrong) "Wrong password" else "Password required")

/**
 * Reading and writing archives. Everything here blocks: call it from Dispatchers.IO.
 * The functions marked suspend only do so to notice a cancelled task between two chunks of data.
 */
object Archives {

    class Entry(val path: String, val isDirectory: Boolean, val size: Long, val modified: Long)

    class ExtractResult(val extracted: Int, val failed: Int)

    private const val BUFFER = 64 * 1024

    /** Where data goes when only the fact that it unpacks matters (password check). */
    private object NullOutputStream : OutputStream() {
        override fun write(b: Int) {}
        override fun write(b: ByteArray, off: Int, len: Int) {}
    }

    /** Thrown from a visitor's stream when the wanted entry was fully read: ends the walk early. */
    private class StopWalk : RuntimeException() {
        override fun fillInStackTrace(): Throwable = this
    }

    // ---------------------------------------------------------------- reading

    /** Only ZIP and 7z can be written with a password (RAR can't be written at all, TAR has no encryption). */
    fun canEncrypt(format: ArchiveFormat) = format == ArchiveFormat.ZIP || format == ArchiveFormat.SEVEN_Z

    /** TAR is stored as is; everything else has a compression level. */
    fun hasLevels(format: ArchiveFormat) = format.canCreate && format != ArchiveFormat.TAR

    /** Does opening or unpacking [file] need a password? Quick: no data is unpacked. */
    @Suppress("DEPRECATION")
    fun needsPassword(file: File, format: ArchiveFormat): Boolean = when (format) {
        ArchiveFormat.ZIP -> isZipEncrypted(file)
        ArchiveFormat.SEVEN_Z -> try {
            RandomAccessFile(file, "r").use { raf ->
                SevenZFile(raf.channel).use { sz ->
                    // the decoder of an encrypted folder is only built when its data is first read: read one byte
                    var asked = false
                    while (!asked) {
                        val entry = sz.nextEntry ?: break
                        if (entry.isDirectory || entry.size <= 0) continue
                        try {
                            sz.read()
                        } catch (e: PasswordRequiredException) {
                            asked = true
                        }
                        break
                    }
                    asked
                }
            }
        } catch (e: PasswordRequiredException) { // even the list of names is encrypted
            true
        } catch (e: Exception) {
            false
        }
        ArchiveFormat.RAR -> try {
            Archive(file).use { it.isPasswordProtected }
        } catch (e: UnsupportedRarEncryptedException) { // even the list of names is encrypted
            true
        } catch (e: Exception) {
            false
        }
        else -> false
    }

    /**
     * Is [password] the right one? Unpacks the smallest file to nowhere: the libraries only notice a wrong
     * password when they check the data (a few bytes for ZIP / 7z, a checksum for RAR), so this stays cheap.
     */
    suspend fun verifyPassword(file: File, format: ArchiveFormat, password: CharArray): Boolean {
        val entries = try {
            list(file, format, password)
        } catch (e: ArchivePasswordException) {
            return false
        }
        val smallest = entries.filter { !it.isDirectory && it.size > 0 }.minByOrNull { it.size } ?: return true
        return try {
            extractEntryTo(file, format, smallest.path, password, NullOutputStream)
            true
        } catch (e: ArchivePasswordException) {
            false
        }
    }

    /** All entries (folders included). Stream formats (tar.gz...) have to be read through to get them. */
    suspend fun list(file: File, format: ArchiveFormat, password: CharArray? = null): List<Entry> {
        val check = checker()
        val out = ArrayList<Entry>()
        walk(file, format, password, check, {}) { entry ->
            out += entry
            null
        }
        return out
    }

    /**
     * What "100 %" means for [extractAll]'s progress: uncompressed size for ZIP / 7z / RAR (known up front),
     * the file size for stream formats (progress then counts compressed bytes read).
     */
    suspend fun progressTotal(file: File, format: ArchiveFormat, password: CharArray? = null): Long = when (format) {
        ArchiveFormat.ZIP, ArchiveFormat.SEVEN_Z, ArchiveFormat.RAR ->
            list(file, format, password).sumOf { if (it.isDirectory) 0L else it.size.coerceAtLeast(0) }.coerceAtLeast(1)
        else -> file.length().coerceAtLeast(1)
    }

    /**
     * Extracts everything into [destDir]. Never overwrites: an existing file makes the new one "name (1)".
     * Every file / folder it creates is added to [created] (parents first), so a cancel or an Undo can remove them.
     * @throws ArchivePasswordException the archive is encrypted and [password] is missing or wrong
     */
    suspend fun extractAll(
        file: File,
        format: ArchiveFormat,
        destDir: File,
        created: MutableList<String>,
        password: CharArray? = null,
        onBytes: (Long) -> Unit
    ): ExtractResult {
        val check = checker()
        if (!ensureDir(destDir, created)) throw IOException("Can't create ${destDir.path}")
        val root = destDir.canonicalFile
        var extracted = 0
        var failed = 0
        walk(file, format, password, check, onBytes) { entry ->
            val target = safeTarget(root, entry.path)
            when {
                target == null -> { // would land outside the destination ("../" tricks): refuse
                    failed++
                    null
                }
                entry.isDirectory -> {
                    if (ensureDir(target, created)) extracted++ else failed++
                    null
                }
                else -> {
                    val parent = target.parentFile
                    if (parent == null || !ensureDir(parent, created)) {
                        failed++
                        null
                    } else {
                        val unique = uniqueFile(target)
                        try {
                            val stream = BufferedOutputStream(FileOutputStream(unique), BUFFER)
                            created += unique.path
                            extracted++
                            stream
                        } catch (e: IOException) {
                            failed++
                            null
                        }
                    }
                }
            }
        }
        return ExtractResult(extracted, failed)
    }

    /**
     * Selective extraction: unpack only the given entry paths (files and/or folders).
     * Folder paths extract recursively (every entry whose path is under that prefix).
     * Relative directory structure under the selected roots is preserved.
     * Path traversal is rejected via [safeTarget]; unselected entries are never written.
     */
    suspend fun extractSelected(
        file: File,
        format: ArchiveFormat,
        destDir: File,
        selectedPaths: Collection<String>,
        created: MutableList<String>,
        password: CharArray? = null,
        onBytes: (Long) -> Unit = {}
    ): ExtractResult {
        if (selectedPaths.isEmpty()) return ExtractResult(0, 0)
        val check = checker()
        if (!ensureDir(destDir, created)) throw IOException("Can't create ${destDir.path}")
        val root = destDir.canonicalFile
        // Normalize selection: exact files + directory prefixes (trailing slash semantics).
        val exact = selectedPaths.map { normalize(it) }.toHashSet()
        val prefixes = exact.map { if (it.endsWith("/")) it else "$it/" }.toHashSet()
        fun isSelected(entryPath: String): Boolean {
            val n = normalize(entryPath)
            if (n in exact) return true
            return prefixes.any { n.startsWith(it) }
        }
        var extracted = 0
        var failed = 0
        walk(file, format, password, check, onBytes) { entry ->
            if (!isSelected(entry.path)) return@walk null
            val target = safeTarget(root, entry.path)
            when {
                target == null -> {
                    failed++
                    null
                }
                entry.isDirectory -> {
                    if (ensureDir(target, created)) extracted++ else failed++
                    null
                }
                else -> {
                    val parent = target.parentFile
                    if (parent == null || !ensureDir(parent, created)) {
                        failed++
                        null
                    } else {
                        val unique = uniqueFile(target)
                        try {
                            val stream = BufferedOutputStream(FileOutputStream(unique), BUFFER)
                            created += unique.path
                            extracted++
                            stream
                        } catch (e: IOException) {
                            failed++
                            null
                        }
                    }
                }
            }
        }
        return ExtractResult(extracted, failed)
    }

    /** Extracts one file of the archive (for "view"). False when it isn't in there. */
    suspend fun extractEntry(
        file: File,
        format: ArchiveFormat,
        entryPath: String,
        target: File,
        password: CharArray? = null
    ): Boolean = extractEntryTo(file, format, entryPath, password, BufferedOutputStream(FileOutputStream(target), BUFFER))

    /** Writes one file of the archive into [sink] (closed afterwards). False when it isn't in there. */
    suspend fun extractEntryTo(
        file: File,
        format: ArchiveFormat,
        entryPath: String,
        password: CharArray?,
        sink: OutputStream
    ): Boolean {
        val check = checker()
        val wanted = normalize(entryPath)
        var found = false
        try {
            walk(file, format, password, check, {}) { entry ->
                if (entry.isDirectory || normalize(entry.path) != wanted) {
                    null
                } else {
                    found = true
                    // when the file is fully written (stream closed) there is nothing else to look for
                    object : FilterOutputStream(sink) {
                        override fun write(b: ByteArray, off: Int, len: Int) {
                            sink.write(b, off, len)
                        }

                        override fun close() {
                            super.close()
                            throw StopWalk()
                        }
                    }
                }
            }
        } catch (_: StopWalk) {
            // done
        }
        if (!found) sink.close()
        return found
    }

    /**
     * Calls [visitor] for each entry. For a file entry the visitor returns where its data goes (null = skip it);
     * folders are only announced. [count] gets the bytes processed (see [progressTotal] for what they mean).
     */
    private fun walk(
        file: File,
        format: ArchiveFormat,
        password: CharArray?,
        check: () -> Unit,
        count: (Long) -> Unit,
        visitor: (Entry) -> OutputStream?
    ) {
        when (format) {
            ArchiveFormat.ZIP -> walkZip(file, password, check, count, visitor)
            ArchiveFormat.SEVEN_Z -> walkSevenZ(file, password, check, count, visitor)
            ArchiveFormat.RAR -> walkRar(file, password, check, count, visitor)
            ArchiveFormat.TAR, ArchiveFormat.TAR_GZ, ArchiveFormat.TAR_BZ2, ArchiveFormat.TAR_XZ ->
                walkTar(file, format, check, count, visitor)
            ArchiveFormat.GZ, ArchiveFormat.BZ2, ArchiveFormat.XZ -> walkSingle(file, format, check, count, visitor)
        }
    }

    /** zip4j reads the header: is any file in this ZIP encrypted? (Any failure: let the JDK try it.) */
    private fun isZipEncrypted(file: File): Boolean =
        runCatching { AesZipFile(file).use { zip -> zip.fileHeaders.any { it.isEncrypted } } }.getOrDefault(false)

    private fun walkZip(file: File, password: CharArray?, check: () -> Unit, count: (Long) -> Unit, visitor: (Entry) -> OutputStream?) {
        // plain ZIPs go through the JDK (fast, battle tested); only encrypted ones need zip4j
        if (!isZipEncrypted(file)) {
            walkZipPlain(file, check, count, visitor)
            return
        }
        AesZipFile(file, password).use { zip ->
            for (header in zip.fileHeaders) {
                check()
                val entry = Entry(header.fileName, header.isDirectory, header.uncompressedSize.coerceAtLeast(0), header.lastModifiedTimeEpoch.coerceAtLeast(0))
                if (header.isDirectory) {
                    visitor(entry)
                    continue
                }
                val out = visitor(entry) ?: continue
                out.use { o ->
                    if (header.isEncrypted && password == null) throw ArchivePasswordException(false)
                    try {
                        zip.getInputStream(header).use { copy(it, o, check, count) }
                    } catch (e: ZipException) {
                        // a wrong ZipCrypto password is only noticed by the checksum at the end
                        if (header.isEncrypted && (e.type == ZipException.Type.WRONG_PASSWORD || e.type == ZipException.Type.CHECKSUM_MISMATCH)) {
                            throw ArchivePasswordException(true)
                        }
                        throw e
                    }
                }
            }
        }
    }

    private fun walkZipPlain(file: File, check: () -> Unit, count: (Long) -> Unit, visitor: (Entry) -> OutputStream?) {
        ZipFile(file).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                check()
                val ze = entries.nextElement()
                val entry = Entry(ze.name, ze.isDirectory, ze.size.coerceAtLeast(0), ze.time.coerceAtLeast(0))
                if (ze.isDirectory) {
                    visitor(entry)
                    continue
                }
                val out = visitor(entry) ?: continue
                out.use { o -> zip.getInputStream(ze).use { copy(it, o, check, count) } }
            }
        }
    }

    @Suppress("DEPRECATION") // the channel constructors: the File one needs java.nio.file (Android 8+)
    private fun walkSevenZ(file: File, password: CharArray?, check: () -> Unit, count: (Long) -> Unit, visitor: (Entry) -> OutputStream?) {
        RandomAccessFile(file, "r").use { raf ->
            val sz = try {
                if (password == null) SevenZFile(raf.channel) else SevenZFile(raf.channel, password)
            } catch (e: PasswordRequiredException) {
                throw ArchivePasswordException(false)
            } catch (e: IOException) {
                // the list of names is encrypted too: a wrong password shows up right here
                if (password != null) throw ArchivePasswordException(true) else throw e
            }
            sz.use {
                val stream = object : InputStream() {
                    override fun read(): Int = sz.read()
                    override fun read(b: ByteArray, off: Int, len: Int): Int = sz.read(b, off, len)
                }
                while (true) {
                    check()
                    val se = sz.nextEntry ?: break
                    val modified = if (se.hasLastModifiedDate) se.lastModifiedDate.time else 0L
                    val entry = Entry(se.name, se.isDirectory, se.size.coerceAtLeast(0), modified)
                    if (se.isDirectory) {
                        visitor(entry)
                        continue
                    }
                    val out = visitor(entry) ?: continue // unread data is skipped by getNextEntry
                    out.use { o ->
                        try {
                            copy(stream, o, check, count)
                        } catch (e: PasswordRequiredException) {
                            throw ArchivePasswordException(false)
                        } catch (e: IOException) {
                            // decrypting with a wrong password gives garbage: the decoder or the checksum complains
                            // (the entry's methods are known once its data was touched)
                            val encrypted = se.contentMethods?.any { it.method == SevenZMethod.AES256SHA256 } == true
                            if (encrypted && password != null && e !is ArchivePasswordException) throw ArchivePasswordException(true)
                            throw e
                        }
                    }
                }
            }
        }
    }

    private fun walkRar(file: File, password: CharArray?, check: () -> Unit, count: (Long) -> Unit, visitor: (Entry) -> OutputStream?) {
        val rar = try {
            if (password == null) Archive(file) else Archive(file, String(password))
        } catch (e: UnsupportedRarEncryptedException) {
            throw ArchivePasswordException(false) // even the list of names is encrypted
        } catch (e: RarException) {
            if (password != null) throw ArchivePasswordException(true) else throw e
        }
        rar.use {
            // junrar doesn't fail when the NAMES are encrypted and the password is missing / wrong: it just finds nothing
            if (rar.isEncrypted && rar.fileHeaders.isEmpty()) throw ArchivePasswordException(password != null)
            for (header in rar.fileHeaders) {
                check()
                val name = header.fileName.replace('\\', '/')
                val entry = Entry(name, header.isDirectory, header.fullUnpackSize.coerceAtLeast(0), header.mTime?.time ?: 0L)
                if (header.isDirectory) {
                    visitor(entry)
                    continue
                }
                val out = visitor(entry) ?: continue
                if (header.isEncrypted && password == null) {
                    out.close()
                    throw ArchivePasswordException(false)
                }
                out.use { o ->
                    try {
                        // junrar pushes the data: count it and notice a cancel while it works
                        rar.extractFile(header, object : FilterOutputStream(o) {
                            override fun write(b: ByteArray, off: Int, len: Int) {
                                check()
                                o.write(b, off, len)
                                count(len.toLong())
                            }

                            override fun close() { // the caller closes [o]
                            }
                        })
                    } catch (e: RarException) {
                        if (header.isEncrypted && password != null) throw ArchivePasswordException(true)
                        throw e
                    }
                }
            }
        }
    }

    private fun walkTar(
        file: File,
        format: ArchiveFormat,
        check: () -> Unit,
        count: (Long) -> Unit,
        visitor: (Entry) -> OutputStream?
    ) {
        val raw = CountingInputStream(BufferedInputStream(FileInputStream(file), BUFFER), count)
        val decoded: InputStream = when (format) {
            ArchiveFormat.TAR_GZ -> GZIPInputStream(raw, BUFFER)
            ArchiveFormat.TAR_BZ2 -> BZip2CompressorInputStream(raw)
            ArchiveFormat.TAR_XZ -> XZInputStream(raw)
            else -> raw
        }
        decoded.use {
            val tar = TarReader(decoded)
            while (true) {
                check()
                val te = tar.next() ?: break
                val entry = Entry(te.name, te.isDirectory, te.size, te.modifiedMillis)
                if (te.isDirectory) {
                    visitor(entry)
                    continue
                }
                if (!te.isFile) continue // links, devices
                val out = visitor(entry) ?: continue
                out.use { o -> copy(tar.data, o, check, null) } // progress = compressed bytes, counted on [raw]
            }
        }
    }

    /** .gz / .bz2 / .xz holding a single file. */
    private fun walkSingle(
        file: File,
        format: ArchiveFormat,
        check: () -> Unit,
        count: (Long) -> Unit,
        visitor: (Entry) -> OutputStream?
    ) {
        val raw = CountingInputStream(BufferedInputStream(FileInputStream(file), BUFFER), count)
        val decoded: InputStream = when (format) {
            ArchiveFormat.GZ -> GZIPInputStream(raw, BUFFER)
            ArchiveFormat.BZ2 -> BZip2CompressorInputStream(raw)
            else -> XZInputStream(raw)
        }
        decoded.use {
            val entry = Entry(archiveBaseName(file.name), false, 0, file.lastModified())
            val out = visitor(entry) ?: return
            out.use { o -> copy(decoded, o, check, null) }
        }
    }

    // ---------------------------------------------------------------- writing

    /**
     * Creates [out] from [sources] (files and folders, with their contents). Only [ArchiveFormat.canCreate] formats.
     * [password] (ZIP: AES-256, 7z: AES-256 too) only for [canEncrypt] formats; [level] for [hasLevels] ones.
     */
    suspend fun create(
        sources: List<File>,
        out: File,
        format: ArchiveFormat,
        level: ArchiveLevel = ArchiveLevel.NORMAL,
        password: CharArray? = null,
        onBytes: (Long) -> Unit
    ) {
        require(format.canCreate) { "Can't create ${format.label}" }
        require(password == null || canEncrypt(format)) { "${format.label} can't be encrypted" }
        val check = checker()
        val items = ArrayList<Item>()
        val outCanonical = runCatching { out.canonicalPath }.getOrNull()
        sources.forEach { collect(it, it.name, outCanonical, items, 0) }
        when (format) {
            ArchiveFormat.ZIP -> createZip(items, out, level, password, check, onBytes)
            ArchiveFormat.SEVEN_Z -> createSevenZ(items, out, level, password, check, onBytes)
            else -> createTar(items, out, format == ArchiveFormat.TAR_GZ, level, check, onBytes)
        }
        // Trust but verify: someone who typed a password must never get an archive that opens without one.
        // (An archive with nothing but empty files and folders has no data to encrypt: nothing to check there.)
        if (password != null && sources.sumOf { sizeOf(it, 0) } > 0 && !needsPassword(out, format)) {
            out.delete()
            throw ArchiveNotEncryptedException()
        }
    }

    /** Bytes that creating an archive from [sources] will read (progress total). */
    fun sizeOfSources(sources: List<File>): Long = sources.sumOf { sizeOf(it, 0) }.coerceAtLeast(1)

    private fun sizeOf(file: File, depth: Int): Long {
        if (depth > 64) return 0
        if (!file.isDirectory) return file.length()
        return file.listFiles()?.sumOf { sizeOf(it, depth + 1) } ?: 0
    }

    private class Item(val file: File, val name: String, val isDirectory: Boolean)

    private fun collect(file: File, name: String, skipCanonical: String?, out: MutableList<Item>, depth: Int) {
        if (depth > 64 || !file.exists()) return // depth: guards against folder loops
        if (skipCanonical != null && runCatching { file.canonicalPath }.getOrNull() == skipCanonical) return // the archive itself
        if (file.isDirectory) {
            out += Item(file, name, true)
            file.listFiles()?.sortedBy { it.name }?.forEach { collect(it, "$name/${it.name}", skipCanonical, out, depth + 1) }
        } else {
            out += Item(file, name, false)
        }
    }

    /** java.util.zip.Deflater level (also gzip). */
    private fun deflaterLevel(level: ArchiveLevel) = when (level) {
        ArchiveLevel.STORE -> Deflater.NO_COMPRESSION
        ArchiveLevel.FAST -> Deflater.BEST_SPEED
        ArchiveLevel.NORMAL -> 6
        ArchiveLevel.MAXIMUM -> Deflater.BEST_COMPRESSION
    }

    /**
     * LZMA2 preset for 7z. The encoder's memory grows with it (preset 6 ~ 100 MB, 9 ~ 700 MB), so "maximum"
     * stops at 6: more would run a phone out of memory for a few percent.
     */
    private fun lzmaPreset(level: ArchiveLevel) = when (level) {
        ArchiveLevel.STORE -> 0
        ArchiveLevel.FAST -> 1
        ArchiveLevel.NORMAL -> 4
        ArchiveLevel.MAXIMUM -> 6
    }

    private fun createZip(items: List<Item>, out: File, level: ArchiveLevel, password: CharArray?, check: () -> Unit, onBytes: (Long) -> Unit) {
        if (password != null) {
            // encrypted: zip4j writes WinZip AES-256 (opens in 7-Zip, WinRAR, Keka... but not in Windows' own zip folder)
            AesZipOutputStream(BufferedOutputStream(FileOutputStream(out), BUFFER), password).use { zip ->
                for (item in items) {
                    check()
                    val params = ZipParameters()
                    params.setFileNameInZip(if (item.isDirectory) item.name + "/" else item.name)
                    params.setLastModifiedFileTime(item.file.lastModified())
                    params.setCompressionMethod(CompressionMethod.DEFLATE)
                    params.setCompressionLevel(
                        when (level) {
                            ArchiveLevel.STORE, ArchiveLevel.FAST -> AesZipLevel.FASTEST // streamed STORE needs the CRC up front
                            ArchiveLevel.NORMAL -> AesZipLevel.NORMAL
                            ArchiveLevel.MAXIMUM -> AesZipLevel.ULTRA
                        }
                    )
                    params.setEncryptFiles(!item.isDirectory)
                    params.setEncryptionMethod(EncryptionMethod.AES)
                    params.setAesKeyStrength(AesKeyStrength.KEY_STRENGTH_256)
                    zip.putNextEntry(params)
                    if (!item.isDirectory) FileInputStream(item.file).use { copy(it, zip, check, onBytes) }
                    zip.closeEntry()
                }
            }
            return
        }
        ZipOutputStream(BufferedOutputStream(FileOutputStream(out), BUFFER)).use { zip ->
            zip.setLevel(deflaterLevel(level))
            for (item in items) {
                check()
                val entry = ZipEntry(if (item.isDirectory) item.name + "/" else item.name)
                entry.time = item.file.lastModified()
                zip.putNextEntry(entry)
                if (!item.isDirectory) FileInputStream(item.file).use { copy(it, zip, check, onBytes) }
                zip.closeEntry()
            }
        }
    }

    private fun createSevenZ(items: List<Item>, out: File, level: ArchiveLevel, password: CharArray?, check: () -> Unit, onBytes: (Long) -> Unit) {
        RandomAccessFile(out, "rw").use { raf ->
            raf.setLength(0)
            val sz = if (password == null) SevenZOutputFile(raf.channel) else SevenZOutputFile(raf.channel, password)
            sz.use {
                sz.setContentMethods(
                    listOf(
                        if (level == ArchiveLevel.STORE) SevenZMethodConfiguration(SevenZMethod.COPY)
                        else SevenZMethodConfiguration(SevenZMethod.LZMA2, LZMA2Options(lzmaPreset(level)))
                    )
                )
                val sink = object : OutputStream() {
                    override fun write(b: Int) = sz.write(b)
                    override fun write(b: ByteArray, off: Int, len: Int) = sz.write(b, off, len)
                }
                for (item in items) {
                    check()
                    val size = if (item.isDirectory) 0L else item.file.length()
                    val entry = SevenZArchiveEntry()
                    entry.setName(item.name)
                    entry.setDirectory(item.isDirectory)
                    entry.setHasStream(size > 0)
                    entry.setSize(size)
                    entry.setLastModifiedDate(Date(item.file.lastModified()))
                    sz.putArchiveEntry(entry)
                    if (size > 0) FileInputStream(item.file).use { copy(it, sink, check, onBytes) }
                    sz.closeArchiveEntry()
                }
                sz.finish()
            }
        }
    }

    private fun createTar(items: List<Item>, out: File, gzip: Boolean, level: ArchiveLevel, check: () -> Unit, onBytes: (Long) -> Unit) {
        val raw = BufferedOutputStream(FileOutputStream(out), BUFFER)
        val stream: OutputStream = if (gzip) {
            object : GZIPOutputStream(raw, BUFFER) {
                init {
                    def.setLevel(deflaterLevel(level))
                }
            }
        } else raw
        stream.use { s ->
            val tar = TarWriter(s)
            for (item in items) {
                check()
                if (item.isDirectory) {
                    tar.putDirectory(item.name, item.file.lastModified())
                } else {
                    FileInputStream(item.file).use {
                        tar.putFile(item.name, item.file.length(), item.file.lastModified(), it, check, onBytes)
                    }
                }
            }
            tar.finish()
        }
    }

    // ---------------------------------------------------------------- helpers

    private suspend fun checker(): () -> Unit {
        val context = currentCoroutineContext()
        return { context.ensureActive() }
    }

    private fun copy(input: InputStream, out: OutputStream, check: () -> Unit, count: ((Long) -> Unit)?) {
        val buffer = ByteArray(BUFFER)
        while (true) {
            check()
            val n = input.read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
            count?.invoke(n.toLong())
        }
    }

    private fun normalize(path: String) = path.replace('\\', '/').trim('/')

    /** [entryPath] inside [root]; null if it would escape it (zip-slip). */
    private fun safeTarget(root: File, entryPath: String): File? {
        val clean = normalize(entryPath)
        if (clean.isEmpty()) return null
        val target = File(root, clean)
        val canonical = runCatching { target.canonicalFile }.getOrNull() ?: return null
        val prefix = root.path.trimEnd(File.separatorChar) + File.separator
        return if (canonical.path == root.path || canonical.path.startsWith(prefix)) canonical else null
    }

    /** Creates [dir] and its missing parents, recording what it created (parents first). */
    private fun ensureDir(dir: File, created: MutableList<String>): Boolean {
        if (dir.isDirectory) return true
        val missing = ArrayList<File>()
        var d: File? = dir
        while (d != null && !d.exists()) {
            missing += d
            d = d.parentFile
        }
        if (!dir.mkdirs() && !dir.isDirectory) return false
        missing.reversed().forEach { created += it.path }
        return true
    }

    private fun uniqueFile(target: File): File {
        if (!target.exists()) return target
        val name = target.name
        val base = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "").let { if (it.isEmpty() || it == name) "" else ".$it" }
        var i = 1
        var candidate: File
        do {
            candidate = File(target.parentFile, "$base ($i)$ext")
            i++
        } while (candidate.exists())
        return candidate
    }

    /** Counts what is read (and skipped) from the archive file itself. */
    private class CountingInputStream(input: InputStream, private val count: (Long) -> Unit) : FilterInputStream(input) {
        override fun read(): Int {
            val b = super.read()
            if (b >= 0) count(1)
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = super.read(b, off, len)
            if (n > 0) count(n.toLong())
            return n
        }

        override fun skip(n: Long): Long {
            val skipped = super.skip(n)
            if (skipped > 0) count(skipped)
            return skipped
        }
    }
}
