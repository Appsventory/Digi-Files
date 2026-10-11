package com.appsventory.digiapps.nemo.util

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ArchivesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ---------------------------------------------------------------- helpers

    /** The folder every create-test packs: text, binary, empty file, empty folder, nesting, unicode, spaces. */
    private fun sourceTree(): File {
        val existing = File(tmp.root, "data")
        if (existing.exists()) return existing // every test packs the very same tree
        val root = tmp.newFolder("data")
        File(root, "a.txt").writeText("alpha")
        File(root, "with space.txt").writeText("spaces are fine")
        File(root, "sub/deeper").mkdirs()
        File(root, "sub/c.txt").writeText("gamma")
        File(root, "sub/deeper/d.txt").writeText("delta")
        File(root, "sub/empty.txt").writeText("")
        File(root, "emptydir").mkdirs()
        File(root, "unicode").mkdirs()
        File(root, "unicode/\u65e5\u672c\u8a9e.txt").writeText("\u3053\u3093\u306b\u3061\u306f")
        // bigger than the 64 KB copy buffer, compressible, and not a multiple of 512
        File(root, "big.txt").writeText("The quick brown fox jumps over the lazy dog. ".repeat(6000))
        return root
    }

    private fun expectedFiles(root: File): Map<String, String> =
        TestFixtures.filesUnder(root).mapKeys { "data/${it.key}" }

    private fun newDest(name: String = "dest"): File = tmp.newFolder(name)

    private fun create(
        format: ArchiveFormat,
        level: ArchiveLevel = ArchiveLevel.NORMAL,
        password: CharArray? = null,
        sources: List<File> = listOf(sourceTree()),
        name: String = "out.${format.extension}"
    ): File {
        val out = File(tmp.root, name)
        runBlocking { Archives.create(sources, out, format, level, password) { } }
        return out
    }

    private fun extract(archive: File, format: ArchiveFormat, dest: File, password: CharArray? = null, created: MutableList<String> = ArrayList()): Archives.ExtractResult =
        runBlocking { Archives.extractAll(archive, format, dest, created, password) { } }

    private fun list(archive: File, format: ArchiveFormat, password: CharArray? = null) =
        runBlocking { Archives.list(archive, format, password) }

    // ---------------------------------------------------------------- names

    @Test
    fun formatIsDetectedFromTheName() {
        val cases = mapOf(
            "a.zip" to ArchiveFormat.ZIP, "A.ZIP" to ArchiveFormat.ZIP, "a.7z" to ArchiveFormat.SEVEN_Z,
            "a.rar" to ArchiveFormat.RAR, "a.tar" to ArchiveFormat.TAR,
            "a.tar.gz" to ArchiveFormat.TAR_GZ, "a.tgz" to ArchiveFormat.TAR_GZ, "b.TAR.GZ" to ArchiveFormat.TAR_GZ,
            "a.tar.bz2" to ArchiveFormat.TAR_BZ2, "a.tbz2" to ArchiveFormat.TAR_BZ2,
            "a.tar.xz" to ArchiveFormat.TAR_XZ, "a.txz" to ArchiveFormat.TAR_XZ,
            "notes.txt.gz" to ArchiveFormat.GZ, "a.bz2" to ArchiveFormat.BZ2, "a.xz" to ArchiveFormat.XZ
        )
        cases.forEach { (name, format) -> assertEquals(name, format, archiveFormatOf(name)) }
    }

    @Test
    fun notArchives() {
        listOf("photo.jpg", "readme.txt", "archive", "zip", ".zip", "a.zip.txt", "x.apk").forEach {
            assertNull("$it is not an archive", archiveFormatOf(it))
        }
    }

    @Test
    fun baseNameDropsTheWholeArchiveSuffix() {
        assertEquals("photos", archiveBaseName("photos.tar.gz"))
        assertEquals("photos", archiveBaseName("photos.tgz"))
        assertEquals("my.backup", archiveBaseName("my.backup.zip"))
        assertEquals("notes.txt", archiveBaseName("notes.txt.gz"))
        assertEquals("plain.txt", archiveBaseName("plain.txt"))
    }

    // ---------------------------------------------------------------- create -> list -> extract

    private fun roundTrip(format: ArchiveFormat, level: ArchiveLevel = ArchiveLevel.NORMAL, password: CharArray? = null) {
        val source = sourceTree()
        val archive = create(format, level, password, listOf(source), "rt-${format.name}-${level.name}-${password != null}.${format.extension}")
        val entries = list(archive, format, password)
        val names = entries.map { it.path.trim('/') }.toSet()
        assertTrue("$format lists the folder", "data" in names)
        assertTrue("$format lists the empty folder", "data/emptydir" in names)
        assertTrue("$format lists a nested file", "data/sub/deeper/d.txt" in names)

        val dest = newDest("dest-${format.name}-${level.name}-${password != null}")
        val created = ArrayList<String>()
        val result = extract(archive, format, dest, password, created)
        assertEquals("nothing refused", 0, result.failed)
        assertEquals("$format content", expectedFiles(source), TestFixtures.filesUnder(dest))
        assertTrue("$format keeps the empty folder", File(dest, "data/emptydir").isDirectory)
        assertTrue("every created path exists", created.all { File(it).exists() })
    }

    @Test fun zip_roundTrip() = roundTrip(ArchiveFormat.ZIP)
    @Test fun sevenZ_roundTrip() = roundTrip(ArchiveFormat.SEVEN_Z)
    @Test fun tar_roundTrip() = roundTrip(ArchiveFormat.TAR)
    @Test fun tarGz_roundTrip() = roundTrip(ArchiveFormat.TAR_GZ)

    @Test
    fun everyLevelWorksForEveryFormat() {
        for (format in listOf(ArchiveFormat.ZIP, ArchiveFormat.SEVEN_Z, ArchiveFormat.TAR_GZ)) {
            for (level in ArchiveLevel.entries) roundTrip(format, level)
        }
    }

    @Test
    fun strongerLevelsMakeSmallerFiles() {
        for (format in listOf(ArchiveFormat.ZIP, ArchiveFormat.SEVEN_Z, ArchiveFormat.TAR_GZ)) {
            val source = sourceTree()
            val store = create(format, ArchiveLevel.STORE, null, listOf(source), "store.${format.extension}").length()
            val max = create(format, ArchiveLevel.MAXIMUM, null, listOf(source), "max.${format.extension}").length()
            assertTrue("$format: maximum ($max) < store ($store)", max < store)
        }
    }

    @Test
    fun levelsAndEncryptionFlags() {
        assertFalse(Archives.hasLevels(ArchiveFormat.TAR))
        assertTrue(Archives.hasLevels(ArchiveFormat.ZIP) && Archives.hasLevels(ArchiveFormat.SEVEN_Z) && Archives.hasLevels(ArchiveFormat.TAR_GZ))
        assertFalse("RAR can't be created", Archives.hasLevels(ArchiveFormat.RAR))
        assertTrue(Archives.canEncrypt(ArchiveFormat.ZIP) && Archives.canEncrypt(ArchiveFormat.SEVEN_Z))
        assertFalse(Archives.canEncrypt(ArchiveFormat.TAR_GZ))
        assertFalse(Archives.canEncrypt(ArchiveFormat.RAR))
    }

    @Test
    fun cannotCreateRarOrEncryptedTar() {
        val source = sourceTree()
        try {
            runBlocking { Archives.create(listOf(source), File(tmp.root, "x.rar"), ArchiveFormat.RAR) { } }
            fail("RAR can't be created")
        } catch (e: IllegalArgumentException) {
            // expected
        }
        try {
            runBlocking { Archives.create(listOf(source), File(tmp.root, "x.tar"), ArchiveFormat.TAR, ArchiveLevel.NORMAL, "pw".toCharArray()) { } }
            fail("TAR can't be encrypted")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun singleFileAndSeveralSources() {
        val a = tmp.newFile("one.txt").also { it.writeText("1") }
        val folder = tmp.newFolder("two").also { File(it, "inner.txt").writeText("2") }
        for (format in listOf(ArchiveFormat.ZIP, ArchiveFormat.SEVEN_Z, ArchiveFormat.TAR)) {
            val archive = create(format, sources = listOf(a, folder), name = "multi.${format.extension}")
            val dest = newDest("multi-${format.name}")
            extract(archive, format, dest)
            assertEquals(mapOf("one.txt" to "1", "two/inner.txt" to "2"), TestFixtures.filesUnder(dest))
        }
    }

    @Test
    fun archiveInsideTheFolderItPacksIsNotPackedIntoItself() {
        val source = sourceTree()
        val out = File(source, "self.zip") // output lands inside the source folder
        runBlocking { Archives.create(listOf(source), out, ArchiveFormat.ZIP) { } }
        val names = list(out, ArchiveFormat.ZIP).map { it.path }
        assertFalse(names.any { it.endsWith("self.zip") })
        assertTrue(names.any { it.endsWith("a.txt") })
    }

    @Test
    fun emptyFolderAlone() {
        val empty = tmp.newFolder("nothing")
        for (format in listOf(ArchiveFormat.ZIP, ArchiveFormat.SEVEN_Z, ArchiveFormat.TAR)) {
            val archive = create(format, sources = listOf(empty), name = "empty.${format.extension}")
            val dest = newDest("empty-${format.name}")
            extract(archive, format, dest)
            assertTrue(File(dest, "nothing").isDirectory)
        }
    }

    // ---------------------------------------------------------------- passwords

    private val secret = "p\u00e4ss w\u00f6rd 123".toCharArray() // spaces and non-ASCII on purpose

    private fun encryptedRoundTrip(format: ArchiveFormat, level: ArchiveLevel = ArchiveLevel.NORMAL) {
        val source = sourceTree()
        val archive = create(format, level, secret, listOf(source), "enc-${format.name}-${level.name}.${format.extension}")

        assertTrue("$format asks for a password", Archives.needsPassword(archive, format))

        // no password
        try {
            extract(archive, format, newDest("nopw-${format.name}-${level.name}"))
            fail("$format opened without its password")
        } catch (e: ArchivePasswordException) {
            assertFalse("missing, not wrong", e.wrong)
        }
        // wrong password
        try {
            extract(archive, format, newDest("wrongpw-${format.name}-${level.name}"), "nope".toCharArray())
            fail("$format opened with a wrong password")
        } catch (e: ArchivePasswordException) {
            assertTrue("wrong", e.wrong)
        }
        assertFalse(runBlocking { Archives.verifyPassword(archive, format, "nope".toCharArray()) })
        assertTrue(runBlocking { Archives.verifyPassword(archive, format, secret) })

        // right password
        val dest = newDest("rightpw-${format.name}-${level.name}")
        assertEquals(0, extract(archive, format, dest, secret).failed)
        assertEquals(format.name, expectedFiles(source), TestFixtures.filesUnder(dest))
    }

    @Test fun zip_encrypted() = encryptedRoundTrip(ArchiveFormat.ZIP)
    @Test fun sevenZ_encrypted() = encryptedRoundTrip(ArchiveFormat.SEVEN_Z)

    @Test
    fun encryptedWithEveryLevel() {
        for (level in ArchiveLevel.entries) {
            encryptedRoundTrip(ArchiveFormat.ZIP, level)
            encryptedRoundTrip(ArchiveFormat.SEVEN_Z, level)
        }
    }

    @Test
    fun anArchiveCreatedWithAPasswordAlwaysNeedsIt() {
        // the point of the password: opening the result without it must fail, in every tool-visible way
        for (format in listOf(ArchiveFormat.ZIP, ArchiveFormat.SEVEN_Z)) {
            val archive = create(format, password = secret, name = "locked-${format.name}.${format.extension}")
            assertTrue("$format is protected", Archives.needsPassword(archive, format))
            try {
                extract(archive, format, newDest("locked-dest-${format.name}"))
                fail("$format extracted without the password")
            } catch (e: ArchivePasswordException) {
                // expected
            }
            // and the other password-free route: reading a single entry
            try {
                runBlocking { Archives.extractEntry(archive, format, "data/a.txt", File(tmp.root, "x-${format.name}.txt")) }
                fail("$format gave up a file without the password")
            } catch (e: ArchivePasswordException) {
                // expected
            }
        }
    }

    @Test
    fun emptyOnlyArchiveWithPasswordIsStillCreated() {
        // nothing to encrypt (only an empty folder): the "is it protected?" check must not reject this
        val empty = tmp.newFolder("only-empty")
        for (format in listOf(ArchiveFormat.ZIP, ArchiveFormat.SEVEN_Z)) {
            val archive = create(format, password = secret, sources = listOf(empty), name = "empty-pw-${format.name}.${format.extension}")
            assertTrue(archive.exists() && archive.length() > 0)
        }
    }

    @Test
    fun plainArchivesDoNotAskForAPassword() {
        for (format in listOf(ArchiveFormat.ZIP, ArchiveFormat.SEVEN_Z, ArchiveFormat.TAR, ArchiveFormat.TAR_GZ)) {
            assertFalse(format.name, Archives.needsPassword(create(format, name = "plain-${format.name}.${format.extension}"), format))
        }
    }

    @Test
    fun singleEntryFromAnEncryptedArchive() {
        val archive = create(ArchiveFormat.ZIP, password = secret)
        val out = File(tmp.root, "picked.txt")
        assertTrue(runBlocking { Archives.extractEntry(archive, ArchiveFormat.ZIP, "data/sub/c.txt", out, secret) })
        assertEquals("gamma", out.readText())
    }

    @Test
    fun zipCryptoFromOtherTools() {
        // made by `zip -P secret`: the old PKWARE encryption many tools still produce
        val zip = TestFixtures.file("zipcrypto.zip")
        assertTrue(Archives.needsPassword(zip, ArchiveFormat.ZIP))
        assertFalse(runBlocking { Archives.verifyPassword(zip, ArchiveFormat.ZIP, "wrong".toCharArray()) })
        assertTrue(runBlocking { Archives.verifyPassword(zip, ArchiveFormat.ZIP, "secret".toCharArray()) })
        val dest = newDest()
        assertEquals(0, extract(zip, ArchiveFormat.ZIP, dest, "secret".toCharArray()).failed)
        assertEquals(mapOf("hello.txt" to "hello\n", "sub/world.txt" to "world world\n"), TestFixtures.filesUnder(dest))
    }

    // ---------------------------------------------------------------- archives made by other tools

    @Test
    fun plainZipFromZipTool() {
        val zip = TestFixtures.file("plain.zip")
        assertFalse(Archives.needsPassword(zip, ArchiveFormat.ZIP))
        val dest = newDest()
        extract(zip, ArchiveFormat.ZIP, dest)
        assertEquals(mapOf("hello.txt" to "hello\n", "sub/world.txt" to "world world\n", "empty.txt" to ""), TestFixtures.filesUnder(dest))
    }

    @Test
    fun tarFamilyFromGnuTar() {
        val expected = (TestFixtures.TREE_FILES + TestFixtures.VERY_LONG_FILE)
        listOf(
            "gnu.tar" to ArchiveFormat.TAR, "pax.tar" to ArchiveFormat.TAR,
            "tree.tar.gz" to ArchiveFormat.TAR_GZ, "tree.tar.bz2" to ArchiveFormat.TAR_BZ2, "tree.tar.xz" to ArchiveFormat.TAR_XZ
        ).forEach { (name, format) ->
            assertEquals(name, format, archiveFormatOf(name))
            val dest = newDest("from-$name")
            val result = extract(TestFixtures.file(name), format, dest)
            assertEquals(name, 0, result.failed)
            assertEquals(name, expected, TestFixtures.filesUnder(dest))
            assertTrue("$name: folders", TestFixtures.dirsUnder(dest).containsAll(TestFixtures.DIRS))
        }
    }

    @Test
    fun singleCompressedFiles() {
        listOf("note.txt.gz", "note.txt.bz2", "note.txt.xz").forEach { name ->
            val format = archiveFormatOf(name)!!
            val entries = list(TestFixtures.file(name), format)
            assertEquals(name, listOf("note.txt"), entries.map { it.path })
            val dest = newDest("single-$name")
            extract(TestFixtures.file(name), format, dest)
            assertEquals(name, mapOf("note.txt" to "single file payload\n"), TestFixtures.filesUnder(dest))
        }
    }

    @Test
    fun rar4() {
        val rar = TestFixtures.file("rar4.rar")
        assertFalse(Archives.needsPassword(rar, ArchiveFormat.RAR))
        val dest = newDest()
        assertEquals(0, extract(rar, ArchiveFormat.RAR, dest).failed)
        assertEquals(mapOf("FILE1.TXT" to "file1\r\n", "FILE2.TXT" to "file2\r\n"), TestFixtures.filesUnder(dest))
    }

    @Test
    fun rar5IsReportedNotCrashed() {
        // junrar can't read RAR5: that must be an ordinary error (the UI says "can't open"), not a password prompt
        val rar = TestFixtures.file("rar5.rar")
        assertFalse(Archives.needsPassword(rar, ArchiveFormat.RAR))
        try {
            list(rar, ArchiveFormat.RAR)
            fail("RAR5 is not supported")
        } catch (e: ArchivePasswordException) {
            fail("RAR5 is not a password problem")
        } catch (e: Exception) {
            // expected
        }
    }

    @Test
    fun rarWithPasswordOnTheContent() {
        val rar = TestFixtures.file("rar4-password.rar")
        assertTrue(Archives.needsPassword(rar, ArchiveFormat.RAR))
        assertEquals("names are visible without a password", listOf("file1.txt"), list(rar, ArchiveFormat.RAR).map { it.path })
        try {
            extract(rar, ArchiveFormat.RAR, newDest("a"))
            fail("opened without password")
        } catch (e: ArchivePasswordException) {
            assertFalse(e.wrong)
        }
        try {
            extract(rar, ArchiveFormat.RAR, newDest("b"), "wrong".toCharArray())
            fail("opened with wrong password")
        } catch (e: ArchivePasswordException) {
            assertTrue(e.wrong)
        }
        assertFalse(runBlocking { Archives.verifyPassword(rar, ArchiveFormat.RAR, "wrong".toCharArray()) })
        assertTrue(runBlocking { Archives.verifyPassword(rar, ArchiveFormat.RAR, "junrar".toCharArray()) })
        val dest = newDest("c")
        extract(rar, ArchiveFormat.RAR, dest, "junrar".toCharArray())
        assertEquals(mapOf("file1.txt" to "file1\n"), TestFixtures.filesUnder(dest))
    }

    @Test
    fun rarWithEncryptedNames() {
        val rar = TestFixtures.file("rar4-header-encrypted.rar")
        assertTrue(Archives.needsPassword(rar, ArchiveFormat.RAR))
        try {
            list(rar, ArchiveFormat.RAR)
            fail("even the names need the password")
        } catch (e: ArchivePasswordException) {
            assertFalse(e.wrong)
        }
        try {
            list(rar, ArchiveFormat.RAR, "wrong".toCharArray())
            fail("wrong password")
        } catch (e: ArchivePasswordException) {
            assertTrue(e.wrong)
        }
        assertEquals(listOf("file1.txt"), list(rar, ArchiveFormat.RAR, "junrar".toCharArray()).map { it.path })
    }

    @Test
    fun rarWithUnicodeNameAndOnlyContentEncrypted() {
        val rar = TestFixtures.file("rar4-content-encrypted.rar")
        val dest = newDest()
        assertEquals(0, extract(rar, ArchiveFormat.RAR, dest, "test".toCharArray()).failed)
        val files = TestFixtures.filesUnder(dest)
        assertEquals(1, files.size)
        assertTrue(files.keys.single().endsWith(".txt"))
        assertEquals("aaaaaaaaaa", files.values.single())
    }

    // ---------------------------------------------------------------- safety

    private fun zipWith(vararg entries: Pair<String, String>): File {
        val file = File(tmp.root, "evil-${System.nanoTime()}.zip")
        ZipOutputStream(FileOutputStream(file)).use { zip ->
            entries.forEach { (name, text) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
        }
        return file
    }

    @Test
    fun zipSlipIsRefused() {
        val outer = tmp.newFolder("outer")
        val dest = File(outer, "dest").also { it.mkdirs() }
        val zip = zipWith("ok.txt" to "fine", "../evil.txt" to "bad", "a/../../evil2.txt" to "bad", "sub/../../../evil3.txt" to "bad")
        val result = extract(zip, ArchiveFormat.ZIP, dest)
        assertEquals("the three escapes are refused", 3, result.failed)
        assertEquals(1, result.extracted)
        assertFalse("nothing written next to the destination", File(outer, "evil.txt").exists())
        assertFalse(File(tmp.root, "evil2.txt").exists())
        assertFalse(File(tmp.root, "evil3.txt").exists())
        assertEquals(mapOf("ok.txt" to "fine"), TestFixtures.filesUnder(dest))
    }

    @Test
    fun absolutePathsStayInsideTheDestination() {
        val dest = newDest()
        val zip = zipWith("/abs/inside.txt" to "x")
        extract(zip, ArchiveFormat.ZIP, dest)
        assertEquals(mapOf("abs/inside.txt" to "x"), TestFixtures.filesUnder(dest))
    }

    @Test
    fun windowsSeparatorsAreSeparators() {
        val dest = newDest()
        val zip = zipWith("dir\\file.txt" to "x")
        extract(zip, ArchiveFormat.ZIP, dest)
        assertEquals(mapOf("dir/file.txt" to "x"), TestFixtures.filesUnder(dest))
    }

    @Test
    fun existingFilesAreNeverOverwritten() {
        val dest = newDest()
        File(dest, "hello.txt").writeText("MINE")
        File(dest, "sub").mkdirs()
        File(dest, "sub/world.txt").writeText("ALSO MINE")
        val created = ArrayList<String>()
        extract(TestFixtures.file("plain.zip"), ArchiveFormat.ZIP, dest, null, created)
        assertEquals("MINE", File(dest, "hello.txt").readText())
        assertEquals("hello\n", File(dest, "hello (1).txt").readText())
        assertEquals("ALSO MINE", File(dest, "sub/world.txt").readText())
        assertEquals("world world\n", File(dest, "sub/world (1).txt").readText())
        assertTrue("only new things are reported for Undo", created.none { it.endsWith("MINE") } && File(dest, "hello.txt").path !in created)
        assertTrue(File(dest, "hello (1).txt").path in created)
        assertFalse("the existing folder isn't ours to remove", File(dest, "sub").path in created)
    }

    @Test
    fun createdListHasParentsBeforeChildren() {
        val dest = File(tmp.root, "fresh/nested/dest") // doesn't exist yet
        val created = ArrayList<String>()
        extract(TestFixtures.file("tree.tar.gz"), ArchiveFormat.TAR_GZ, dest, null, created)
        val paths = created.map { File(it) }
        paths.forEachIndexed { i, f ->
            val parent = f.parentFile
            val parentIndex = paths.indexOf(parent)
            if (parentIndex >= 0) assertTrue("${f.path}: parent comes first", parentIndex < i)
        }
        assertTrue(File(tmp.root, "fresh").path in created)
        // and undoing in reverse order removes everything
        created.asReversed().forEach { File(it).delete() }
        assertFalse(File(tmp.root, "fresh").exists())
    }

    // ---------------------------------------------------------------- entries, progress, cancel

    @Test
    fun extractOneEntry() {
        for (format in listOf(ArchiveFormat.ZIP, ArchiveFormat.SEVEN_Z, ArchiveFormat.TAR, ArchiveFormat.TAR_GZ)) {
            val archive = create(format, name = "pick-${format.name}.${format.extension}")
            val out = File(tmp.root, "pick-${format.name}.txt")
            assertTrue(format.name, runBlocking { Archives.extractEntry(archive, format, "data/sub/c.txt", out) })
            assertEquals(format.name, "gamma", out.readText())
            val missing = File(tmp.root, "missing-${format.name}.txt")
            assertFalse(format.name, runBlocking { Archives.extractEntry(archive, format, "data/nope.txt", missing) })
        }
    }

    @Test
    fun progressTotalMatchesWhatIsReported() {
        // ZIP / 7z / RAR: uncompressed bytes. Stream formats: bytes of the archive file read.
        for (format in listOf(ArchiveFormat.ZIP, ArchiveFormat.SEVEN_Z)) {
            val source = sourceTree()
            val archive = create(format, sources = listOf(source), name = "prog-${format.name}.${format.extension}")
            var counted = 0L
            val total = runBlocking { Archives.progressTotal(archive, format) }
            runBlocking { Archives.extractAll(archive, format, newDest("prog-${format.name}"), ArrayList(), null) { counted += it } }
            assertEquals(format.name, total, counted)
            assertEquals(format.name, TestFixtures.filesUnder(source).values.sumOf { it.toByteArray().size.toLong() }, total)
        }
        val gz = TestFixtures.file("tree.tar.gz")
        var counted = 0L
        runBlocking { Archives.extractAll(gz, ArchiveFormat.TAR_GZ, newDest("prog-gz"), ArrayList(), null) { counted += it } }
        assertEquals(runBlocking { Archives.progressTotal(gz, ArchiveFormat.TAR_GZ) }, gz.length())
        assertEquals("every compressed byte was read", gz.length(), counted)
    }

    @Test
    fun createReportsEveryByte() {
        val source = sourceTree()
        var counted = 0L
        runBlocking { Archives.create(listOf(source), File(tmp.root, "count.zip"), ArchiveFormat.ZIP) { counted += it } }
        assertEquals(Archives.sizeOfSources(listOf(source)), counted)
    }

    @Test
    fun cancelStopsExtractionBetweenChunks() {
        val big = tmp.newFolder("bigsrc").also { File(it, "big.bin").writeBytes(ByteArray(2_000_000) { (it * 31).toByte() }) }
        val archive = create(ArchiveFormat.ZIP, ArchiveLevel.STORE, sources = listOf(big), name = "cancel.zip")
        val dest = newDest("cancel-dest")
        var cancelled = false
        try {
            runBlocking {
                val job = currentCoroutineContext()[Job]!!
                Archives.extractAll(archive, ArchiveFormat.ZIP, dest, ArrayList(), null) { job.cancel() }
            }
        } catch (e: CancellationException) {
            cancelled = true
        }
        assertTrue("the cancel was noticed", cancelled)
        assertTrue("it stopped well before the end", (File(dest, "bigsrc/big.bin").takeIf { it.exists() }?.length() ?: 0) < 2_000_000)
    }

    @Test
    fun cancelStopsCreation() {
        val big = tmp.newFolder("bigsrc2").also { File(it, "big.bin").writeBytes(ByteArray(2_000_000) { (it * 7).toByte() }) }
        val out = File(tmp.root, "cancel-create.zip")
        var cancelled = false
        try {
            runBlocking {
                val job = currentCoroutineContext()[Job]!!
                Archives.create(listOf(big), out, ArchiveFormat.ZIP) { job.cancel() }
            }
        } catch (e: CancellationException) {
            cancelled = true
        }
        assertTrue(cancelled)
    }

    @Test
    fun brokenArchiveIsAnErrorNotAHang() {
        val junk = File(tmp.root, "junk.zip").also { it.writeBytes(ByteArray(300) { 3 }) }
        try {
            list(junk, ArchiveFormat.ZIP)
            fail("junk is not a zip")
        } catch (e: ArchivePasswordException) {
            fail("junk is not a password problem")
        } catch (e: Exception) {
            // expected
        }
        val junk7z = File(tmp.root, "junk.7z").also { it.writeBytes(ByteArray(300) { 3 }) }
        try {
            list(junk7z, ArchiveFormat.SEVEN_Z)
            fail("junk is not a 7z")
        } catch (e: ArchivePasswordException) {
            fail("junk is not a password problem")
        } catch (e: Exception) {
            // expected
        }
        assertFalse(Archives.needsPassword(junk, ArchiveFormat.ZIP))
        assertFalse(Archives.needsPassword(junk7z, ArchiveFormat.SEVEN_Z))
    }
}
