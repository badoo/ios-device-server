package com.badoo.automation.deviceserver.ios.simulator.data

import com.badoo.automation.deviceserver.data.UDID
import com.badoo.automation.deviceserver.host.IRemote
import com.badoo.automation.deviceserver.util.withDefers
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Paths

class Media(
    private val remote: IRemote,
    private val udid: UDID,
    deviceSetPath: String
) {
    private val mediaPath = Paths.get(deviceSetPath, udid, "data", "Media")
    private val logger = LoggerFactory.getLogger(javaClass.simpleName)

    fun reset() {
        val removeCmd = "rm -rf $mediaPath"

        val result = remote.shell(removeCmd)

        if (!result.isSuccess) {
            throw RuntimeException("Could not reset Media: $result")
        }

        // restart assetsd to prevent fbsimctl upload failing with Error Domain=NSCocoaErrorDomain Code=-1 \"(null)\"
        restartAssetsd()
    }

    fun addMedia(file: File, data: ByteArray) {
        withDefers(logger) {
            val tmpFile = File.createTempFile(file.nameWithoutExtension, ".${file.extension}")
            defer { tmpFile.delete() }
            tmpFile.writeBytes(data)

            val mediaPath: String = if (remote.isLocalhost()) {
                tmpFile.absolutePath
            } else {
                val remoteMediaDir = remote.execIgnoringErrors(listOf("mktemp", "-d")).stdOut.trim()
                defer { remote.execIgnoringErrors(listOf("rm", "-rf", remoteMediaDir)) }
                remote.rsync(tmpFile.absolutePath, remoteMediaDir, setOf("-r", "--delete"))
                File(remoteMediaDir, tmpFile.name).absolutePath
            }

            val result = remote.execIgnoringErrors(listOf("xcrun", "simctl", "addmedia", udid, mediaPath))

            if (!result.isSuccess) {
                throw RuntimeException("Could not add Media to device: $result")
            }
        }
    }

    private fun restartAssetsd() {
        val restartCmd = listOf(
            "xcrun", "simctl", "spawn", udid, "launchctl", "kickstart", "-k", "-p", "system/com.apple.assetsd"
        )

        val result = remote.execIgnoringErrors(restartCmd)

        if (!result.isSuccess) {
            throw RuntimeException("Could not restart assetsd service: $result")
        }
    }
}
