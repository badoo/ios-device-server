package com.badoo.automation.deviceserver.host.management;

class XcodeVersion(val major: Int, val minor: Int) {
    companion object {
        fun fromXcodeBuildOutput(output: String): XcodeVersion {
            val regex = Regex("Xcode (\\d+)\\.(\\d+)")
            val versionLine = output.lines().first()
            val match = regex.matchEntire(versionLine)

            match?.destructured?.let {
                val major = match.groups[1]!!.value.toInt()
                val minor = match.groups[2]!!.value.toInt()

                return XcodeVersion(major, minor)
            } ?: throw IllegalArgumentException("Could not parse Xcode version $versionLine")
        }
    }
}
