package com.appsventory.digiapps.nemo.util

import java.io.File

/**
 * Passwords typed for archives, kept in memory only so "View" followed by "Extract" doesn't ask twice.
 *
 * - memory only: nothing is written to disk, and the process dying (or the app closing) forgets everything
 * - a password is forgotten after [TTL_MS] without being used
 * - it only fits the archive it was typed for: if the file changed (size / date) it is dropped
 * - callers get a copy, so wiping their array (tasks do that) never damages the stored one
 */
object ArchivePasswordCache {
    /** 10 minutes since the last use. */
    const val TTL_MS = 10 * 60 * 1000L

    private class Saved(val password: CharArray, val size: Long, val modified: Long, var lastUsed: Long)

    private val saved = HashMap<String, Saved>()

    private fun key(file: File): String = runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)

    /** The remembered password of [file] (a copy), or null. [now] is only for tests. */
    @Synchronized
    fun get(file: File, now: Long = System.currentTimeMillis()): CharArray? {
        purge(now)
        val k = key(file)
        val entry = saved[k] ?: return null
        if (entry.size != file.length() || entry.modified != file.lastModified()) {
            saved.remove(k)?.password?.fill('\u0000')
            return null
        }
        entry.lastUsed = now
        return entry.password.copyOf()
    }

    /** Remembers [password] for [file] (stores its own copy). */
    @Synchronized
    fun put(file: File, password: CharArray, now: Long = System.currentTimeMillis()) {
        saved.put(key(file), Saved(password.copyOf(), file.length(), file.lastModified(), now))?.password?.fill('\u0000')
    }

    /** E.g. after a "wrong password": the remembered one is no good. */
    @Synchronized
    fun forget(file: File) {
        saved.remove(key(file))?.password?.fill('\u0000')
    }

    @Synchronized
    fun clear() {
        saved.values.forEach { it.password.fill('\u0000') }
        saved.clear()
    }

    private fun purge(now: Long) {
        val it = saved.entries.iterator()
        while (it.hasNext()) {
            val e = it.next().value
            if (now - e.lastUsed > TTL_MS) {
                e.password.fill('\u0000')
                it.remove()
            }
        }
    }
}
