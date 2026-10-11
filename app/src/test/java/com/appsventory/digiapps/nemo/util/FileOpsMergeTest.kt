package com.appsventory.digiapps.nemo.util

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Feature C: recursive folder merge on copy/move with Replace / Keep both / Skip.
 */
class FileOpsMergeTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun copyMergesSameNameDirectories() = runBlocking {
        val srcRoot = tmp.newFolder("src")
        val destRoot = tmp.newFolder("dest")
        val srcFolder = File(srcRoot, "photos").apply { mkdirs() }
        File(srcFolder, "a.jpg").writeText("A")
        File(srcFolder, "nested").mkdirs()
        File(srcFolder, "nested/b.jpg").writeText("B")

        val destFolder = File(destRoot, "photos").apply { mkdirs() }
        File(destFolder, "c.jpg").writeText("C")
        File(destFolder, "nested").mkdirs()
        File(destFolder, "nested/d.jpg").writeText("D")

        val result = FileOps.copyTracked(srcFolder.absolutePath, destRoot.absolutePath, ConflictPolicy.KEEP_BOTH) { }
        assertTrue(result is OpResult.Done)
        // Existing dest files preserved
        assertEquals("C", File(destFolder, "c.jpg").readText())
        assertEquals("D", File(destFolder, "nested/d.jpg").readText())
        // Source files merged in
        assertEquals("A", File(destFolder, "a.jpg").readText())
        assertEquals("B", File(destFolder, "nested/b.jpg").readText())
        // Destination folder itself was never replaced (same path)
        assertEquals(destFolder.absolutePath, (result as OpResult.Done).target)
    }

    @Test
    fun mergeReplaceOverwritesConflictingFile() = runBlocking {
        val srcRoot = tmp.newFolder("srcR")
        val destRoot = tmp.newFolder("destR")
        val srcFolder = File(srcRoot, "docs").apply { mkdirs() }
        File(srcFolder, "note.txt").writeText("NEW")
        val destFolder = File(destRoot, "docs").apply { mkdirs() }
        File(destFolder, "note.txt").writeText("OLD")

        FileOps.copyTracked(srcFolder.absolutePath, destRoot.absolutePath, ConflictPolicy.REPLACE) { }
        assertEquals("NEW", File(destFolder, "note.txt").readText())
    }

    @Test
    fun mergeSkipLeavesConflictingFile() = runBlocking {
        val srcRoot = tmp.newFolder("srcS")
        val destRoot = tmp.newFolder("destS")
        val srcFolder = File(srcRoot, "docs").apply { mkdirs() }
        File(srcFolder, "note.txt").writeText("NEW")
        File(srcFolder, "extra.txt").writeText("EXTRA")
        val destFolder = File(destRoot, "docs").apply { mkdirs() }
        File(destFolder, "note.txt").writeText("OLD")

        FileOps.copyTracked(srcFolder.absolutePath, destRoot.absolutePath, ConflictPolicy.SKIP) { }
        assertEquals("OLD", File(destFolder, "note.txt").readText())
        assertEquals("EXTRA", File(destFolder, "extra.txt").readText())
    }

    @Test
    fun keepBothCreatesUniqueName() = runBlocking {
        val srcRoot = tmp.newFolder("srcK")
        val destRoot = tmp.newFolder("destK")
        File(srcRoot, "file.txt").writeText("SRC")
        File(destRoot, "file.txt").writeText("DST")

        val result = FileOps.copyTracked(
            File(srcRoot, "file.txt").absolutePath,
            destRoot.absolutePath,
            ConflictPolicy.KEEP_BOTH
        ) { }
        assertTrue(result is OpResult.Done)
        assertEquals("DST", File(destRoot, "file.txt").readText())
        assertTrue(File(destRoot, "file (1).txt").exists())
        assertEquals("SRC", File(destRoot, "file (1).txt").readText())
    }

    @Test
    fun moveDoesNotDeleteSourceUntilCopySucceeds() = runBlocking {
        val srcRoot = tmp.newFolder("srcM")
        val destRoot = tmp.newFolder("destM")
        val srcFolder = File(srcRoot, "pack").apply { mkdirs() }
        File(srcFolder, "x.bin").writeText("X")
        File(destRoot, "pack").mkdirs()

        val result = FileOps.moveTracked(srcFolder.absolutePath, destRoot.absolutePath, ConflictPolicy.KEEP_BOTH) { }
        assertTrue(result is OpResult.Done)
        assertFalse(srcFolder.exists())
        assertEquals("X", File(destRoot, "pack/x.bin").readText())
    }
}
