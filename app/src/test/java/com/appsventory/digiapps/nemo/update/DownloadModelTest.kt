package com.appsventory.digiapps.nemo.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DownloadModelTest {

    private fun asset(name: String, url: String = "https://example.com/$name", size: Long = 100) =
        GitHubAsset(name, url, "application/vnd.android.package-archive", size, null)

    @Test
    fun progress_percentOnlyWhenTotalKnown() {
        val known = AssetDownloadState.Downloading(50, 200)
        assertEquals(25, known.percent)
        assertEquals(0.25f, known.fraction)
        val unknown = AssetDownloadState.Downloading(50, -1)
        assertNull(unknown.fraction)
        assertNull(unknown.percent)
        val paused = AssetDownloadState.Downloading(50, 200, waiting = true)
        assertNull(paused.percent) // paused for network: no fake progress
        assertEquals(100, AssetDownloadState.Downloading(300, 200).percent) // clamped
    }

    @Test
    fun assetKey_differsPerAssetAndRelease_stableForSame() {
        val a = asset("a.apk"); val b = asset("b.apk")
        assertEquals(assetKey("v1", a), assetKey("v1", a))
        assertFalse(assetKey("v1", a) == assetKey("v1", b))
        assertFalse(assetKey("v1", a) == assetKey("v2", a))
        assertFalse(assetKey("v1", a) == assetKey("v1", asset("a.apk", url = "https://other/a.apk")))
    }

    @Test
    fun record_roundTrip_andBadInput() {
        val r = DownloadRecord(42, "/storage/emulated/0/Android/data/x/files/Download/a|b.apk", 1234)
        assertEquals(r, DownloadRecord.decode(r.encode())) // path may contain '|'
        assertNull(DownloadRecord.decode(null))
        assertNull(DownloadRecord.decode(""))
        assertNull(DownloadRecord.decode("x|1|/p"))
        assertNull(DownloadRecord.decode("1|2"))
    }

    @Test
    fun safeSegment_stripsPathChars() {
        assertEquals("v1.2.3", safeSegment("v1.2.3"))
        assertEquals("a_b_c", safeSegment("a/b\\c"))
        assertEquals("_", safeSegment("  "))
    }

    @Test
    fun verify_missingEmptyShortInvalidOk() {
        val dir = java.nio.file.Files.createTempDirectory("dlcheck").toFile()
        try {
            val f = File(dir, "a.apk")
            assertEquals(FileVerdict.MISSING, LocalFileCheck.verify(f, 10, true) { true })
            f.writeBytes(ByteArray(0))
            assertEquals(FileVerdict.INCOMPLETE, LocalFileCheck.verify(f, 10, true) { true })
            f.writeBytes(ByteArray(5))
            assertEquals(FileVerdict.INCOMPLETE, LocalFileCheck.verify(f, 10, true) { true })
            f.writeBytes(ByteArray(10))
            assertEquals(FileVerdict.INVALID, LocalFileCheck.verify(f, 10, true) { false })
            assertEquals(FileVerdict.OK, LocalFileCheck.verify(f, 10, true) { true })
            // unknown published size: any non-empty file passes the size check
            assertEquals(FileVerdict.OK, LocalFileCheck.verify(f, -1, true) { true })
            // non-APK never asks the package parser
            assertEquals(FileVerdict.OK, LocalFileCheck.verify(f, 10, false) { false })
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun onlyApksAreInstallable() {
        assertTrue(asset("a.apk").isInstallable)
        assertFalse(GitHubAsset("src.zip", "https://x/src.zip", "application/zip", 1, null).isInstallable)
    }
}
