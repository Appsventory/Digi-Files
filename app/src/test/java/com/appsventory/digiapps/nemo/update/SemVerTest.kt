package com.appsventory.digiapps.nemo.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SemVerTest {

    @Test
    fun parse_stripsLeadingV() {
        assertEquals(SemVer(2, 1, 0), SemVer.parse("v2.1.0"))
        assertEquals(SemVer(2, 1, 0), SemVer.parse("2.1.0"))
        assertEquals(SemVer(1, 0, 0), SemVer.parse("v1.0.0"))
    }

    @Test
    fun parse_partialVersions() {
        assertEquals(SemVer(1, 0, 0), SemVer.parse("1"))
        assertEquals(SemVer(1, 9, 0), SemVer.parse("1.9"))
        assertEquals(SemVer(1, 10, 0), SemVer.parse("1.10.0"))
    }

    @Test
    fun parse_invalidReturnsNull() {
        assertNull(SemVer.parse(null))
        assertNull(SemVer.parse(""))
        assertNull(SemVer.parse("   "))
        assertNull(SemVer.parse("not-a-version"))
        assertNull(SemVer.parse("v"))
    }

    @Test
    fun compare_ordersCorrectly() {
        assertTrue(SemVer.parse("1.9.0")!! < SemVer.parse("1.10.0")!!)
        assertTrue(SemVer.parse("1.10.0")!! < SemVer.parse("2.0.0")!!)
        assertTrue(SemVer.parse("v2.1.0")!! == SemVer.parse("2.1.0")!!)
    }

    @Test
    fun relation_newerAvailable() {
        assertEquals(
            VersionRelation.NEWER_AVAILABLE,
            compareInstalledToRemote("1.0.0", "v2.1.0")
        )
    }

    @Test
    fun relation_upToDate() {
        assertEquals(
            VersionRelation.UP_TO_DATE,
            compareInstalledToRemote("2.1.0", "v2.1.0")
        )
    }

    @Test
    fun relation_noDowngrade() {
        assertEquals(
            VersionRelation.OLDER_OR_EQUAL,
            compareInstalledToRemote("2.2.0", "2.1.0")
        )
    }

    @Test
    fun relation_invalidTag() {
        assertEquals(
            VersionRelation.UNKNOWN,
            compareInstalledToRemote("1.0.0", "latest")
        )
        assertEquals(
            VersionRelation.UNKNOWN,
            compareInstalledToRemote(null, "1.0.0")
        )
    }
}
