package com.badoo.automation.deviceserver.host.management;

class XcodeVersion(val major: Int, val minor: Int):Comparable<XcodeVersion> {
    companion object {
        fun fromXcodeBuildOutput(output: String): XcodeVersion {
            val regex = Regex("Xcode (\\d+)\\.(\\d+)(\\.(\\d+))?")
            val versionLine = output.lines().first()
            val match = regex.matchEntire(versionLine)

            match?.destructured?.let {
                val major = match.groups[1]!!.value.toInt()
                val minor = match.groups[2]!!.value.toInt()

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
}
