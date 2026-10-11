package com.appsventory.digiapps.nemo.util

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ArchivePasswordCacheTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @After
    fun cleanUp() = ArchivePasswordCache.clear()

    private fun archive(name: String = "a.zip", text: String = "x") = tmp.newFile(name).also { it.writeText(text) }

    @Test
    fun rememberedForTheSameArchive() {
        val file = archive()
        assertNull(ArchivePasswordCache.get(file))
        ArchivePasswordCache.put(file, "secret".toCharArray())
        assertArrayEquals("secret".toCharArray(), ArchivePasswordCache.get(file))
    }

    @Test
    fun otherArchivesAreNotAffected() {
        val a = archive("a.zip")
        val b = archive("b.zip")
        ArchivePasswordCache.put(a, "pw-a".toCharArray())
        assertNull(ArchivePasswordCache.get(b))
        ArchivePasswordCache.put(b, "pw-b".toCharArray())
        assertArrayEquals("pw-a".toCharArray(), ArchivePasswordCache.get(a))
        assertArrayEquals("pw-b".toCharArray(), ArchivePasswordCache.get(b))
    }

    @Test
    fun callersGetACopyAndCannotDamageTheStoredPassword() {
        val file = archive()
        val typed = "secret".toCharArray()
        ArchivePasswordCache.put(file, typed)
        typed.fill('\u0000') // a task wipes what it was given
        val first = ArchivePasswordCache.get(file)!!
        first.fill('\u0000')
        val second = ArchivePasswordCache.get(file)!!
        assertNotSame(first, second)
        assertArrayEquals("secret".toCharArray(), second)
    }

    @Test
    fun forgottenAfterTenMinutesWithoutUse() {
        val file = archive()
        ArchivePasswordCache.put(file, "secret".toCharArray(), now = 1_000)
        assertNotNull(ArchivePasswordCache.get(file, now = 1_000 + ArchivePasswordCache.TTL_MS))
        assertNull(ArchivePasswordCache.get(file, now = 1_000 + 2 * ArchivePasswordCache.TTL_MS + 1))
    }

    @Test
    fun usingItKeepsItAlive() {
        val file = archive()
        ArchivePasswordCache.put(file, "secret".toCharArray(), now = 0)
        val step = ArchivePasswordCache.TTL_MS - 1
        // each use restarts the countdown: still there after three steps, far past one TTL since the first typing
        assertNotNull(ArchivePasswordCache.get(file, now = step))
        assertNotNull(ArchivePasswordCache.get(file, now = 2 * step))
        assertNotNull(ArchivePasswordCache.get(file, now = 3 * step))
    }

    @Test
    fun droppedWhenTheArchiveChanged() {
        val file = archive(text = "short")
        ArchivePasswordCache.put(file, "secret".toCharArray())
        file.writeText("a different, longer content") // replaced by another archive with the same name
        assertNull(ArchivePasswordCache.get(file))
        assertNull("and it stays gone", ArchivePasswordCache.get(file))
    }

    @Test
    fun forgetAndClear() {
        val a = archive("a.zip")
        val b = archive("b.zip")
        ArchivePasswordCache.put(a, "1".toCharArray())
        ArchivePasswordCache.put(b, "2".toCharArray())
        ArchivePasswordCache.forget(a)
        assertNull(ArchivePasswordCache.get(a))
        assertEquals("2", String(ArchivePasswordCache.get(b)!!))
        ArchivePasswordCache.clear()
        assertNull(ArchivePasswordCache.get(b))
    }

    @Test
    fun typingANewPasswordReplacesTheOldOne() {
        val file = archive()
        ArchivePasswordCache.put(file, "old".toCharArray())
        ArchivePasswordCache.put(file, "new".toCharArray())
        assertEquals("new", String(ArchivePasswordCache.get(file)!!))
    }
}
