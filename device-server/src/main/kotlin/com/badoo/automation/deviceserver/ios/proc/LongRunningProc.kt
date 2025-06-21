package com.badoo.automation.deviceserver.ios.proc

import com.badoo.automation.deviceserver.LogMarkers
import com.badoo.automation.deviceserver.command.SubProcess
import com.badoo.automation.deviceserver.data.UDID
import com.badoo.automation.deviceserver.util.CustomHttpClient
import net.logstash.logback.marker.MapEntriesAppendingMarker
import org.slf4j.Logger
import org.slf4j.LoggerFactory

abstract class LongRunningProc(udid: UDID, remoteHostName: String) : ILongRunningProc {
    protected val logger: Logger = LoggerFactory.getLogger(javaClass.simpleName)
    protected val logMarker = MapEntriesAppendingMarker(mapOf(
            LogMarkers.HOSTNAME to remoteHostName,
            LogMarkers.UDID to udid,
            LogMarkers.DEVICE_REF to "$udid-$remoteHostName".replace(Regex("[^-\\w]"), "-")
    ))
    @Volatile protected var subProcess: SubProcess? = null
    override val isProcessAlive: Boolean get() = true == subProcess?.isAlive()

    override fun kill() {
        if (subProcess == null) {
            return
        }

        logger.debug(logMarker, "$this — Killing child process $subProcess")
        subProcess?.kill()
        subProcess = null
    }

    override fun isHealthy(): Boolean {
        return isProcessAlive && checkHealth()
    }

    protected val client: CustomHttpClient = CustomHttpClient()

    protected abstract fun checkHealth(): Boolean
}