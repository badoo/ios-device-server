package com.badoo.automation.deviceserver.ios.simulator.diagnostic

import com.badoo.automation.deviceserver.LogMarkers
import com.badoo.automation.deviceserver.command.ShellUtils
import com.badoo.automation.deviceserver.data.UDID
import com.badoo.automation.deviceserver.host.IRemote
import net.logstash.logback.marker.MapEntriesAppendingMarker
import org.slf4j.LoggerFactory
import org.slf4j.Marker
import java.io.File

class SystemLog(
    private val remote: IRemote,
    private val udid: UDID
) {
    private val logger = LoggerFactory.getLogger(javaClass.simpleName)
    private val logMarker: Marker = MapEntriesAppendingMarker(
        mapOf(
            LogMarkers.UDID to udid,
            LogMarkers.HOSTNAME to remote.hostName
        )
    )

    fun truncate(): Boolean {
        val path = remote.fbsimctl.diagnose(udid).sysLogLocation ?: return false

        val rv = remote.shell("echo > ${ShellUtils.escape(path)}", returnOnFailure = true)

        return rv.isSuccess
    }

    fun content(): String {
        val path = remote.fbsimctl.diagnose(udid).sysLogLocation
                ?: throw RuntimeException("Could not determine System Log path")

        val result = remote.captureFile(File(path))

        if (!result.isSuccess) {
            val message = "Could not read System Log: $result"
            logger.error(logMarker, message)
            throw RuntimeException(message)
        }

        return result.stdOut
    }
}
