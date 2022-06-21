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
        terminateMobileSlideshowApp()
        val imagesPath = mediaPath.resolve("DCIM").toString()
        val photoDataPath = mediaPath.resolve("PhotoData").toString()
        val removeCmd = "rm -rf $imagesPath; rm -rf $photoDataPath; mkdir -p $imagesPath; mkdir -p $photoDataPath"

        val result = remote.shell(removeCmd)

        if (!result.isSuccess) {
            throw RuntimeException("Could not reset Media: $result")
        }

        // restart assetsd to prevent fbsimctl upload failing with Error Domain=NSCocoaErrorDomain Code=-1 \"(null)\"
        restartAssetsd()

        // starting MobileSlideshow app is essential for initializing PhotoData databases
        startMobileSlideshowApp()
        Thread.sleep(5000) // to make sure app started
    }

    fun listPhotoData() : List<String> {
        val photoDataPath = mediaPath.resolve("PhotoData/Photos.sqlite").toString()
        val tables: List<String> = remote.shell("sqlite3 $photoDataPath \".tables\"").stdOut.lines().map(String::trim)

        val tableName = when {
            tables.contains("ZGENERICASSET") -> "ZGENERICASSET"
            tables.contains("ZASSET") -> "ZASSET"
            else -> throw RuntimeException("Unable to find table with photos")
        }

        val sql = "\"select ZFILENAME from $tableName;\""
        val sqlCmd = "sqlite3 $photoDataPath $sql"
        return remote.shell(sqlCmd).stdOut.lines().filter(String::isNotBlank)
    }

    fun list() : List<String> {
        val listCmd = listOf("ls", "-1", "$mediaPath/DCIM/100APPLE")
        return remote.execIgnoringErrors(listCmd).stdOut.lines().filter(String::isNotBlank)
    }

    fun addMedia(media: List<File>) {
        withDefers(logger) {
            val mediaPaths = if (remote.isLocalhost()) {
                media.joinToString(" ")
            } else {
                val remoteMediaDir = remote.execIgnoringErrors(listOf("/usr/bin/mktemp", "-d")).stdOut.trim()
                defer { remote.execIgnoringErrors(listOf("/bin/rm", "-rf", remoteMediaDir)) }
                media.forEach { remote.scpToRemoteHost(it.absolutePath, remoteMediaDir) }
                media.joinToString(" ") { File(remoteMediaDir, it.name).absolutePath }
            }

            val result = remote.shell("/usr/bin/xcrun simctl addmedia $udid $mediaPaths")

            if (!result.isSuccess) {
                throw RuntimeException("Could not add Media to device: $result")
            }
        }
    }

    fun addMedia(file: File, data: ByteArray) {
        withDefers(logger) {
            val tmpFile = File.createTempFile(file.nameWithoutExtension, ".${file.extension}")
            defer { tmpFile.delete() }
            tmpFile.writeBytes(data)

            val mediaPath: String = if (remote.isLocalhost()) {
                tmpFile.absolutePath
            } else {
                val remoteMediaDir = remote.execIgnoringErrors(listOf("/usr/bin/mktemp", "-d")).stdOut.trim()
                defer { remote.execIgnoringErrors(listOf("/bin/rm", "-rf", remoteMediaDir)) }
                remote.scpToRemoteHost(tmpFile.absolutePath, remoteMediaDir)
                File(remoteMediaDir, tmpFile.name).absolutePath
            }

            val result = remote.execIgnoringErrors(listOf("/usr/bin/xcrun", "simctl", "addmedia", udid, mediaPath))

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

    private fun startMobileSlideshowApp() {
        val appStartResult = remote.execIgnoringErrors(
            listOf(
                "xcrun", "simctl", "launch", udid, "com.apple.mobileslideshow"
            )
        )

        if (!appStartResult.isSuccess) {
            throw RuntimeException("Could not start Mobile Slideshow app: $appStartResult")
        }
    }

    private val notRunningMessage = "app is not currently running"

    private fun terminateMobileSlideshowApp() {
        val appTerminateResult = remote.execIgnoringErrors(
            listOf(
                "xcrun", "simctl", "terminate", udid, "com.apple.mobileslideshow"
            )
        )

        // The 'notRunningMessage' can be in stdOut when over SSH and in stdErr when local run
        if (appTerminateResult.isSuccess || appTerminateResult.stdOut.contains(notRunningMessage) || appTerminateResult.stdErr.contains(notRunningMessage)) {
            logger.debug("Successfully terminated the Mobile Slideshow app")
        } else {
            throw RuntimeException("Could not terminate Mobile Slideshow app: $appTerminateResult")
        }
    }
}
