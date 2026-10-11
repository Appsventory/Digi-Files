package com.appsventory.digiapps.nemo.util

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Feature B: selective archive extraction (files + recursive folders).
 */
class ArchivesSelectiveTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun makeZip(): File {
        val zip = tmp.newFile("sample.zip")
        ZipOutputStream(FileOutputStream(zip)).use { zos ->
            fun put(path: String, content: String?) {
                zos.putNextEntry(ZipEntry(path))
                if (content != null) zos.write(content.toByteArray())
                zos.closeEntry()
            }
            put("readme.txt", "ROOT")
            put("photos/", null)
            put("photos/a.jpg", "A")
            put("photos/b.jpg", "B")
            put("photos/nested/", null)
            put("photos/nested/c.jpg", "C")
            put("docs/", null)
            put("docs/note.txt", "NOTE")
            put("secret.txt", "SECRET")
        }
        return zip
    }

    @Test
    fun extractSingleFileOnly() = runBlocking {
        val zip = makeZip()
        val dest = tmp.newFolder("out1")
        val created = mutableListOf<String>()
        val result = Archives.extractSelected(
            zip, ArchiveFormat.ZIP, dest, listOf("readme.txt"), created
        )
        assertEquals(1, result.extracted)
        assertEquals(0, result.failed)
        assertEquals("ROOT", File(dest, "readme.txt").readText())
        assertFalse(File(dest, "secret.txt").exists())
        assertFalse(File(dest, "photos").exists())
    }

    @Test
    fun extractFolderRecursively() = runBlocking {
        val zip = makeZip()
        val dest = tmp.newFolder("out2")
        val created = mutableListOf<String>()
        val result = Archives.extractSelected(
            zip, ArchiveFormat.ZIP, dest, listOf("photos"), created
        )
        assertTrue(result.extracted >= 3)
        assertEquals("A", File(dest, "photos/a.jpg").readText())
        assertEquals("B", File(dest, "photos/b.jpg").readText())
        assertEquals("C", File(dest, "photos/nested/c.jpg").readText())
        assertFalse(File(dest, "docs").exists())
        assertFalse(File(dest, "secret.txt").exists())
        assertFalse(File(dest, "readme.txt").exists())
    }

    @Test
    fun extractMultipleMixed() = runBlocking {
        val zip = makeZip()
        val dest = tmp.newFolder("out3")
        val created = mutableListOf<String>()
        val result = Archives.extractSelected(
            zip, ArchiveFormat.ZIP, dest, listOf("readme.txt", "docs/note.txt"), created
        )
        assertEquals(2, result.extracted)
        assertEquals("ROOT", File(dest, "readme.txt").readText())
        assertEquals("NOTE", File(dest, "docs/note.txt").readText())
        assertFalse(File(dest, "photos").exists())
    }

    @Test
    fun rejectsPathTraversal() = runBlocking {
        val zip = tmp.newFile("evil.zip")
        ZipOutputStream(FileOutputStream(zip)).use { zos ->
            zos.putNextEntry(ZipEntry("../escape.txt"))
            zos.write("BAD".toByteArray())
            zos.closeEntry()
        }
        val dest = tmp.newFolder("out4")
        val created = mutableListOf<String>()
        val result = Archives.extractSelected(
            zip, ArchiveFormat.ZIP, dest, listOf("../escape.txt"), created
        )
        // safeTarget rejects; nothing written outside dest
        assertFalse(File(tmp.root, "escape.txt").exists())
        assertTrue(result.extracted == 0 || result.failed >= 1)
    }
}
