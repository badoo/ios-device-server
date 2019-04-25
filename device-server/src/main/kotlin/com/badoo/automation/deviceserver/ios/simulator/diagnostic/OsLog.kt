package com.badoo.automation.deviceserver.ios.simulator.diagnostic

import com.badoo.automation.deviceserver.LogMarkers
import com.badoo.automation.deviceserver.command.ShellUtils
import com.badoo.automation.deviceserver.data.UDID
import com.badoo.automation.deviceserver.host.IRemote
import io.ktor.util.chomp
import net.logstash.logback.marker.MapEntriesAppendingMarker
import org.slf4j.LoggerFactory
import org.slf4j.Marker
import java.io.File

class OsLog(
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

    private var timestamp: String? = null

    fun truncate(): Boolean {
        val date = remote.execIgnoringErrors(listOf("date", "+%s"))

        if (date.isSuccess) {
            timestamp = date.stdOut.lines().first()
        }

        return date.isSuccess
    }

    fun content(process: String?): String {
        val cmd = mutableListOf("xcrun", "simctl", "spawn", udid, "log", "show", "--style", "syslog")

        if (timestamp != null) {
            cmd.addAll(listOf("--start", "@$timestamp"))
        }

        if (process != null) {
            cmd.addAll(listOf("--predicate", remote.escape("process==\"$process\"")))
        }

        val result = remote.execIgnoringErrors(cmd)

        if (!result.isSuccess) {
            val message = "Could not read OS Log. Result stdErr: ${result.stdErr}"
            logger.error(logMarker, message)
            throw RuntimeException(message)
        }

        return result.stdOut
    }
}
