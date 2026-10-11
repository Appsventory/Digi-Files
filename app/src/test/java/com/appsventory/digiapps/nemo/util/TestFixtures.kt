package com.appsventory.digiapps.nemo.util

import java.io.File

/** Small archives made with the system tools (tar, zip, gzip...), kept in src/test/resources/fixtures. */
object TestFixtures {
    fun file(name: String): File {
        val url = TestFixtures::class.java.classLoader!!.getResource("fixtures/$name")
            ?: error("Missing test fixture fixtures/$name")
        return File(url.toURI())
    }

    /** What the tar fixtures contain (all files; folders are listed in [DIRS]; the symlink is ignored). */
    val TREE_FILES = mapOf(
        "tree/hello.txt" to "hello\n",
        "tree/empty.txt" to "",
        "tree/sub/world.txt" to "world world\n",
        "tree/" + "d".repeat(60) + "/" + "f".repeat(60) + ".txt" to "long path content\n"
    )

    /** Only in the GNU / PAX fixtures: the path is over 130 characters (ustar can't store it). */
    val VERY_LONG_FILE = "tree/" + "x".repeat(130) + "/v.txt" to "very long\n"

    val DIRS = setOf("tree", "tree/emptydir", "tree/sub", "tree/" + "d".repeat(60))

    /** Every regular file below [root] as relative path -> text. */
    fun filesUnder(root: File): Map<String, String> =
        root.walkTopDown().filter { it.isFile }.associate { it.relativeTo(root).path.replace(File.separatorChar, '/') to it.readText() }

    /** Every folder below [root] (not [root] itself) as relative path. */
    fun dirsUnder(root: File): Set<String> =
        root.walkTopDown().filter { it.isDirectory && it != root }.map { it.relativeTo(root).path.replace(File.separatorChar, '/') }.toSet()
}
