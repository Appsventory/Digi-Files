package com.appsventory.digiapps.nemo.update

/**
 * Minimal semantic-version parser and comparator.
 * Accepts tags with or without a leading "v" (v2.1.0 and 2.1.0 are equal).
 * Pre-release / build metadata after '-' or '+' is ignored for comparison order
 * (a release is never considered a downgrade relative to a plain number).
 */
data class SemVer(
    val major: Int,
    val minor: Int,
    val patch: Int
) : Comparable<SemVer> {

    override fun compareTo(other: SemVer): Int {
        val m = major.compareTo(other.major)
        if (m != 0) return m
        val n = minor.compareTo(other.minor)
        if (n != 0) return n
        return patch.compareTo(other.patch)
    }

    override fun toString(): String = "$major.$minor.$patch"

    companion object {
        private val PATTERN = Regex(
            """^v?(\d+)(?:\.(\d+))?(?:\.(\d+))?(?:[-+].*)?$""",
            RegexOption.IGNORE_CASE
        )

        /**
         * Parse a version string or tag. Returns null for blank or unrecognised input
         * so callers can treat invalid tags safely without crashing.
         */
        fun parse(raw: String?): SemVer? {
            if (raw.isNullOrBlank()) return null
            val m = PATTERN.matchEntire(raw.trim()) ?: return null
            return SemVer(
                major = m.groupValues[1].toIntOrNull() ?: return null,
                minor = m.groupValues[2].toIntOrNull() ?: 0,
                patch = m.groupValues[3].toIntOrNull() ?: 0
            )
        }
    }
}

/** Result of comparing the installed version against a remote tag. */
enum class VersionRelation {
    /** Remote is strictly newer → offer an update. */
    NEWER_AVAILABLE,
    /** Same major.minor.patch → up to date. */
    UP_TO_DATE,
    /** Remote is older or equal in a way that would be a downgrade → do not offer. */
    OLDER_OR_EQUAL,
    /** Either side could not be parsed → treat as unknown, no download action. */
    UNKNOWN
}

fun compareInstalledToRemote(installed: String?, remoteTag: String?): VersionRelation {
    val a = SemVer.parse(installed) ?: return VersionRelation.UNKNOWN
    val b = SemVer.parse(remoteTag) ?: return VersionRelation.UNKNOWN
    return when {
        b > a -> VersionRelation.NEWER_AVAILABLE
        b == a -> VersionRelation.UP_TO_DATE
        else -> VersionRelation.OLDER_OR_EQUAL
    }
}
