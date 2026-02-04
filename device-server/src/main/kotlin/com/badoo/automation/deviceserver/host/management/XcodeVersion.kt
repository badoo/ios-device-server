package com.badoo.automation.deviceserver.host.management

data class XcodeVersion(val major: Int, val minor: Int):Comparable<XcodeVersion> {
    companion object {
        val REQUIRED_XCODE_VERSION = XcodeVersion(16, 2)
        fun fromXcodeBuildOutput(output: String): XcodeVersion {
            val regex = Regex("Xcode (\\d+)\\.(\\d+)(\\.(\\d+))?")
            val versionLine = output.lines().first { it.startsWith("Xcode ") }
            val match: MatchResult? = regex.matchEntire(versionLine)

            match?.let {
                val major = it.groups[1]?.value?.toIntOrNull()
                    ?: throw IllegalArgumentException("Could not parse major version from $versionLine")
                val minor = it.groups[2]?.value?.toIntOrNull()
                    ?: throw IllegalArgumentException("Could not parse minor version from $versionLine")

                return XcodeVersion(major, minor)
            } ?: throw IllegalArgumentException("Could not parse Xcode version $versionLine")
        }

        private val COMPARATOR =
            Comparator.comparingInt<XcodeVersion> { it.major }
                .thenComparingInt { it.minor }
    }

    override fun equals(other: Any?): Boolean {
        if (other == null || other !is XcodeVersion) {
            return false
        }

        return compareTo(other) == 0
    }

    override operator fun compareTo(other: XcodeVersion): Int {
        return COMPARATOR.compare(this, other)
    }

    override fun toString(): String {
        return "$major.$minor"
    }
}
