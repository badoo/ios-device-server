package com.badoo.automation.deviceserver.ios.simulator.diagnostic

import com.badoo.automation.deviceserver.LogMarkers
import com.badoo.automation.deviceserver.command.ShellUtils
import com.badoo.automation.deviceserver.data.UDID
import com.badoo.automation.deviceserver.host.IRemote
import com.badoo.automation.deviceserver.ios.simulator.data.DataContainerException
import net.logstash.logback.marker.MapEntriesAppendingMarker
import org.slf4j.LoggerFactory
import org.slf4j.Marker
import java.io.File
import java.nio.charset.StandardCharsets

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

        // Following command can be used to correctly rotate syslog.
        // newsyslog -R "Log file rotated" $path
        // But it requires privilege and we don't actually care about correct rotation
        // (as we consider simulators and their logs to be ephemeral).
        // All we want is to truncate log.
        val rv = remote.shell("echo > ${ShellUtils.escape(path)}", returnOnFailure = true)

        return rv.isSuccess
    }

    fun content(): String {
        val path = remote.fbsimctl.diagnose(udid).sysLogLocation
                ?: throw RuntimeException("Could not determine System Log path")

        try {
            return String(remote.captureFile(File(path)), StandardCharsets.UTF_8)
        } catch (e: RuntimeException) {
            val message = "Could not read System Log. Cause: ${e.message}"
            logger.error(logMarker, message)
            throw RuntimeException(message, e)
        }
    }
}
