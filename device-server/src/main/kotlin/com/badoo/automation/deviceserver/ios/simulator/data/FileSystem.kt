package com.badoo.automation.deviceserver.ios.simulator.data

import com.badoo.automation.deviceserver.LogMarkers
import com.badoo.automation.deviceserver.data.UDID
import com.badoo.automation.deviceserver.host.IRemote
import com.badoo.automation.deviceserver.util.deviceRefFromUDID
import net.logstash.logback.marker.MapEntriesAppendingMarker
import org.slf4j.LoggerFactory
import java.io.File
import java.io.FileNotFoundException

class FileSystem(
    private val remote: IRemote,
    private val udid: UDID
) {
    private val logger = LoggerFactory.getLogger(javaClass.simpleName)
    private val deviceRef = deviceRefFromUDID(udid, remote.publicHostName)
    val logMarkerData = mapOf(
        LogMarkers.HOSTNAME to remote.hostName,
        LogMarkers.UDID to udid,
        LogMarkers.DEVICE_REF to deviceRef
    )

    fun applicationContainer(bundleId: String): DataContainer {
        return DataContainer(
            remote,
            getContainerPathWithRetry(bundleId, "app"),
            bundleId
        )
    }

    fun dataContainer(bundleId: String): DataContainer {
        return DataContainer(
            remote,
            getContainerPathWithRetry(bundleId, "data"),
            bundleId
        )
    }

    fun sharedContainer(sharedResourceDirectory: String): SharedContainer {
        check(!sharedResourceDirectory.isBlank()) {
            "Simulator shared resources directory must not be blank for simulator: $udid"
        }

        return SharedContainer(remote, File(sharedResourceDirectory))
    }

    private fun getContainerPathWithRetry(bundleId: String, containerType: String): File {
        1.rangeTo(3).forEach { attempt ->
            try {
                return getContainerPath(bundleId, containerType)
            } catch (e: FileNotFoundException) {
                val metaData = HashMap(logMarkerData)
                metaData.put("method", "getContainerPath")
                metaData.put("is_success", "false")
                val logMarker = MapEntriesAppendingMarker(metaData)
                logger.warn(logMarker, "Attempt $attempt to get container path for bundle id $bundleId on simulator $udid failed: ${e.message}")
                if (attempt == 3) {
                    logger.error(logMarker, "Failed to get container path for $containerType for bundle id $bundleId on simulator $udid failed: ${e.message}")
                    throw e
                }
            }
        }
        return File("")
    }

    private fun getContainerPath(bundleId: String, containerType: String): File {
        val result = remote.exec(
            command = listOf(
                "/usr/bin/xcrun",
                "simctl",
                "get_app_container",
                udid,
                bundleId,
                containerType
            ),
            env = mapOf(),
            returnFailure = true,
            timeOutSeconds = 30
        )

        val metaData = HashMap(logMarkerData)
        metaData.put("method", "getContainerPath")
        val logMarker = MapEntriesAppendingMarker(metaData)

        return if (result.isSuccess) {
            val stdOut = result.stdOut.trim()
            metaData.put("get_app_container", stdOut)
            if (stdOut.isBlank()) {
                metaData.put("is_success", "false")
                val message = "getContainerPath Failed to get container for $containerType for bundle id $bundleId on simulator $udid -> $stdOut"
                logger.error(logMarker, message)
                throw FileNotFoundException(message)
            } else {
                metaData.put("is_success", "true")
                val message = "getContainerPath Got container for $containerType for bundle id $bundleId on simulator $udid -> $stdOut"
                logger.info(logMarker, message)
                File(stdOut)
            }
        } else {
            metaData.put("is_success", "false")
            val message = "Failed to get container for $containerType for bundle id $bundleId on simulator $udid. " +
                    "Exit code: ${result.exitCode}, " +
                    "StdOut: ${result.stdOut}, " +
                    "StdErr: ${result.stdErr}"
            logger.error(logMarker, message)
            throw FileNotFoundException(message)
        }
    }
}
