package com.appsventory.digiapps.nemo.util

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.File
import java.io.InputStream

class TarCodecTest {

    private class Item(val name: String, val isDirectory: Boolean, val size: Long, val modified: Long, val data: ByteArray)

    /** Reads a whole tar stream; regular files get their data, others (folders, links) come with empty data. */
    private fun readAll(input: InputStream): List<Item> {
        val reader = TarReader(input)
        val out = ArrayList<Item>()
        while (true) {
            val e = reader.next() ?: break
            val data = if (e.isFile) reader.data.readBytes() else ByteArray(0)
            out += Item(e.name, e.isDirectory, e.size, e.modifiedMillis, data)
        }
        return out
    }

    private fun write(block: (TarWriter) -> Unit): ByteArray {
        val bytes = ByteArrayOutputStream()
        val w = TarWriter(bytes)
        block(w)
        w.finish()
        return bytes.toByteArray()
    }

    private fun TarWriter.file(name: String, data: ByteArray, modified: Long = 0) =
        putFile(name, data.size.toLong(), modified, ByteArrayInputStream(data), {}, {})

    // ---------------------------------------------------------------- our own writer -> our own reader

    @Test
    fun roundTrip_filesAndFolders() {
        val modified = 1_600_000_000_000L
        val sizes = listOf(0, 1, 511, 512, 513, 1024, 1500, 70_000) // around the 512-byte block edges
        val tar = write { w ->
            w.putDirectory("top", modified)
            sizes.forEach { n -> w.file("top/f$n.bin", ByteArray(n) { (it % 251).toByte() }, modified) }
        }
        assertEquals("a tar is always a multiple of 512 bytes", 0, tar.size % 512)

        val items = readAll(ByteArrayInputStream(tar))
        assertEquals(1 + sizes.size, items.size)
        assertEquals("top", items[0].name) // the trailing slash is dropped
        assertTrue(items[0].isDirectory)
        sizes.forEachIndexed { i, n ->
            val item = items[i + 1]
            assertEquals("top/f$n.bin", item.name)
            assertFalse(item.isDirectory)
            assertEquals(n.toLong(), item.size)
            assertArrayEquals("content of f$n", ByteArray(n) { (it % 251).toByte() }, item.data)
            assertEquals("whole seconds are kept", modified, item.modified)
        }
    }

    @Test
    fun roundTrip_longAndUnicodeNames() {
        val names = listOf(
            "a".repeat(100) + ".txt",          // 104 bytes: just over the 100-byte field
            "dir/" + "b".repeat(300) + ".txt", // far over, needs the GNU long-name entry
            "caf\u00e9/\u65e5\u672c\u8a9e/\u30d5\u30a1\u30a4\u30eb.txt",
            "emoji-\uD83D\uDE00.txt"
        )
        val tar = write { w -> names.forEach { w.file(it, it.toByteArray()) } }
        val items = readAll(ByteArrayInputStream(tar))
        assertEquals(names, items.map { it.name })
        items.forEach { assertEquals(it.name, String(it.data)) }
    }

    @Test
    fun roundTrip_emptyArchive() {
        val tar = write { }
        assertEquals(1024, tar.size) // just the two zero blocks
        assertTrue(readAll(ByteArrayInputStream(tar)).isEmpty())
    }

    @Test
    fun writer_zeroFillsAFileThatShrankWhileArchiving() {
        // header says 1000 bytes but only 10 are there: the archive must stay valid, not shift every later entry
        val tar = write { w ->
            w.putFile("short.bin", 1000, 0, ByteArrayInputStream(ByteArray(10) { 7 }), {}, {})
            w.file("after.txt", "still found".toByteArray())
        }
        val items = readAll(ByteArrayInputStream(tar))
        assertEquals(listOf("short.bin", "after.txt"), items.map { it.name })
        assertEquals(1000, items[0].data.size)
        assertEquals(7, items[0].data[9].toInt())
        assertEquals(0, items[0].data[10].toInt())
        assertEquals("still found", String(items[1].data))
    }

    @Test
    fun writer_reportsProgressInBytes() {
        var counted = 0L
        write { w -> w.putFile("x", 5000, 0, ByteArrayInputStream(ByteArray(5000)), {}, { counted += it }) }
        assertEquals(5000L, counted)
    }

    @Test
    fun writer_checkIsCalledSoACancelCanStopIt() {
        var calls = 0
        try {
            write { w ->
                w.putFile("big", 1_000_000, 0, ByteArrayInputStream(ByteArray(1_000_000)), {
                    calls++
                    if (calls == 3) throw IllegalStateException("cancelled")
                }, {})
            }
            fail("expected the cancel to stop the write")
        } catch (e: IllegalStateException) {
            assertEquals(3, calls)
        }
    }

    // ---------------------------------------------------------------- reader

    @Test
    fun reader_skipsDataItDidNotRead() {
        val tar = write { w ->
            w.file("one.bin", ByteArray(3000) { 1 })
            w.file("two.bin", ByteArray(700) { 2 })
            w.file("three.txt", "last".toByteArray())
        }
        val reader = TarReader(ByteArrayInputStream(tar))
        val names = ArrayList<String>()
        while (true) names += (reader.next() ?: break).name // never reads .data
        assertEquals(listOf("one.bin", "two.bin", "three.txt"), names)
    }

    @Test
    fun reader_readingPartOfAnEntryThenMovingOnIsFine() {
        val tar = write { w ->
            w.file("big.bin", ByteArray(5000) { 9 })
            w.file("next.txt", "next".toByteArray())
        }
        val reader = TarReader(ByteArrayInputStream(tar))
        reader.next()
        reader.data.read(ByteArray(100))
        val second = reader.next()
        assertEquals("next.txt", second!!.name)
        assertEquals("next", String(reader.data.readBytes()))
    }

    @Test
    fun reader_dataStopsAtTheEndOfTheEntry() {
        val tar = write { w ->
            w.file("a.txt", "AAAA".toByteArray())
            w.file("b.txt", "BBBBBB".toByteArray())
        }
        val reader = TarReader(ByteArrayInputStream(tar))
        reader.next()
        assertEquals("AAAA", String(reader.data.readBytes()))
        assertEquals(-1, reader.data.read())
    }

    @Test
    fun reader_truncatedArchiveFailsInsteadOfReturningShortData() {
        val tar = write { w -> w.file("a.bin", ByteArray(4000) { 5 }) }
        val cut = tar.copyOf(1500) // cuts the data of a.bin
        val reader = TarReader(ByteArrayInputStream(cut))
        assertNotNull(reader.next())
        try {
            reader.data.readBytes()
            fail("expected EOFException")
        } catch (e: EOFException) {
            // expected
        }
    }

    @Test
    fun reader_truncatedHeaderFails() {
        val tar = write { w -> w.file("a.txt", "x".toByteArray()) }
        try {
            TarReader(ByteArrayInputStream(tar.copyOf(300))).next()
            fail("expected EOFException")
        } catch (e: EOFException) {
            // expected
        }
    }

    @Test
    fun reader_endsCleanlyWithoutEndMarker() {
        // some tools just stop after the last entry
        val tar = write { w -> w.file("a.txt", "x".toByteArray()) }
        val noMarker = tar.copyOf(tar.size - 1024)
        val items = readAll(ByteArrayInputStream(noMarker))
        assertEquals(listOf("a.txt"), items.map { it.name })
    }

    // ---------------------------------------------------------------- tar files made by GNU tar

    private fun readFixture(name: String): List<Item> = TestFixtures.file(name).inputStream().use { readAll(it) }

    private fun assertTreeMatches(items: List<Item>, expectVeryLong: Boolean) {
        val files = items.filter { !it.isDirectory && it.data.isNotEmpty() || it.name.endsWith(".txt") && !it.isDirectory }
            .associate { it.name to String(it.data) }
        val expected = TestFixtures.TREE_FILES.toMutableMap()
        if (expectVeryLong) expected += TestFixtures.VERY_LONG_FILE
        assertEquals(expected, files)
        assertTrue("folders are reported", items.filter { it.isDirectory }.map { it.name }.toSet().containsAll(TestFixtures.DIRS))
        assertFalse("the symlink is not a file or folder", items.any { it.name == "tree/link-to-hello" && (it.isDirectory || it.data.isNotEmpty()) })
    }

    @Test
    fun reads_gnuTar_withLongNames() = assertTreeMatches(readFixture("gnu.tar"), expectVeryLong = true)

    @Test
    fun reads_paxTar_withLongNames() = assertTreeMatches(readFixture("pax.tar"), expectVeryLong = true)

    @Test
    fun reads_ustarTar_withPrefixField() = assertTreeMatches(readFixture("ustar.tar"), expectVeryLong = false)

    @Test
    fun reads_modifiedTimeFromGnuTar() {
        val hello = readFixture("gnu.tar").first { it.name == "tree/hello.txt" }
        // the fixtures were made with `touch -d '2020-01-02 03:04:05'` in UTC
        assertEquals(1_577_934_245_000L, hello.modified)
    }

    // ---------------------------------------------------------------- our tar -> GNU tar (interop) is checked by hand
    // (it needs the `tar` program, which a phone-less CI may not have): see tarWrittenByUs_isListedByGnuTar

    @Test
    fun tarWrittenByUs_isListedByGnuTar() {
        val tarProgram = listOf("/usr/bin/tar", "/bin/tar").firstOrNull { File(it).canExecute() } ?: return // skipped where tar is missing
        val tar = write { w ->
            w.putDirectory("top", 1_600_000_000_000L)
            w.file("top/hello.txt", "hello\n".toByteArray(), 1_600_000_000_000L)
            w.file("top/" + "n".repeat(150) + ".txt", "long".toByteArray(), 1_600_000_000_000L)
        }
        val file = File.createTempFile("ours", ".tar")
        try {
            file.writeBytes(tar)
            val process = ProcessBuilder(tarProgram, "-tf", file.path).redirectErrorStream(true).start()
            val listing = process.inputStream.bufferedReader().readText()
            assertEquals("GNU tar accepts our archive: $listing", 0, process.waitFor())
            assertTrue(listing.contains("top/hello.txt"))
            assertTrue("long name survives", listing.contains("n".repeat(150) + ".txt"))
        } finally {
            file.delete()
        }
    }

    @Test
    fun noEntryReturnedAfterTheEnd() {
        val reader = TarReader(ByteArrayInputStream(write { }))
        assertNull(reader.next())
        assertNull(reader.next())
    }
}
