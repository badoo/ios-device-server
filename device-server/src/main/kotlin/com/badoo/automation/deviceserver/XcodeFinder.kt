package com.badoo.automation.deviceserver

import com.badoo.automation.deviceserver.host.IRemote
import com.badoo.automation.deviceserver.host.management.Xcode
import com.badoo.automation.deviceserver.host.management.XcodeVersion
import java.io.File

class XcodeFinder(private val remote: IRemote) {
    /**
     * Uses Spotlight to find installed versions of Xcode
     * mdfind "kMDItemCFBundleIdentifier == 'com.apple.dt.Xcode'"
     *
     * @return List of installed Xcode applications
     */
    fun findInstalledXcodeApplications(): List<Xcode> {
        val command = listOf("mdfind", "kMDItemCFBundleIdentifier == 'com.apple.dt.Xcode'")
        val result = remote.exec(command, mapOf(), false, 30)
        return result.stdOut
            .lines()
            .asSequence()
            .filterNot { it.isBlank() }
            .map { xcodePath ->
                val developerDir = File(xcodePath.trim(), Xcode.CONTENTS_FOLDER)
                val version = getXcodeVersion(developerDir)
                Xcode(version = version, developerDir = developerDir)
            }
            .toList()
    }

    private fun getXcodeVersion(developerDir: File): XcodeVersion {
        val environment = mapOf(Xcode.DEVELOPER_DIR to developerDir.absolutePath)
        val xcodeBuildOut = remote.exec(listOf("xcodebuild", "-version"), environment, false, 30).stdOut
        return XcodeVersion.fromXcodeBuildOutput(xcodeBuildOut)
    }
}
