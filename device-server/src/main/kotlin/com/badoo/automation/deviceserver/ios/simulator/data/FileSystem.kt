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
    private val logger = LoggerFactory.getLogger(DataContainer::class.java.simpleName)
    private val deviceRef = deviceRefFromUDID(udid, remote.publicHostName)
    private val logMarker = MapEntriesAppendingMarker(mapOf(
        LogMarkers.HOSTNAME to remote.hostName,
        LogMarkers.UDID to udid,
        LogMarkers.DEVICE_REF to deviceRef
    ))

    fun applicationContainer(bundleId: String): DataContainer {
        return DataContainer(
            remote,
            getContainerPath(bundleId, "app"),
            bundleId
        )
    }

    fun dataContainer(bundleId: String): DataContainer {
        return DataContainer(
            remote,
            getContainerPath(bundleId, "data"),
            bundleId
        )
    }

    fun sharedContainer(sharedResourceDirectory: String): SharedContainer {
        check(!sharedResourceDirectory.isBlank()) {
            "Simulator shared resources directory must not be blank for simulator: $udid"
        }

        return SharedContainer(remote, File(sharedResourceDirectory))
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

        return if (result.isSuccess) {
            File(result.stdOut.trim())
        } else {
            val message = "Failed to get container for $containerType for bundle id $bundleId on simulator $udid"
            logger.error(logMarker, message)
            throw FileNotFoundException(message)
        }
    }
}
