package com.badoo.automation.deviceserver.ios.proc

import com.badoo.automation.deviceserver.LogMarkers
import com.badoo.automation.deviceserver.command.ChildProcess
import com.badoo.automation.deviceserver.data.UDID
import com.badoo.automation.deviceserver.util.CustomHttpClient
import net.logstash.logback.marker.MapEntriesAppendingMarker
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.time.Duration

abstract class LongRunningProc(udid: UDID, remoteHostName: String) : ILongRunningProc {
    private val checkInterval: Duration = Duration.ofSeconds(10)
    private val checkAttempts: Int = 6
    protected val logger: Logger = LoggerFactory.getLogger(javaClass.simpleName)
    protected val logMarker = MapEntriesAppendingMarker(mapOf(
            LogMarkers.HOSTNAME to remoteHostName,
            LogMarkers.UDID to udid
    ))
    @Volatile protected var childProcess: ChildProcess? = null
    override val isProcessAlive: Boolean get() {
        val alive = childProcess?.isAlive()
        logger.debug(logMarker, "$this — Child process is null [${childProcess == null}] child process is alive [$alive]")

        return true == alive
    }

    override fun kill() {
        if (childProcess == null) {
            return
        }

        logger.debug(logMarker, "$this — Killing child process $childProcess")
        childProcess!!.kill()
        childProcess = null
    }

    override fun isHealthy(): Boolean {
        return isProcessAlive && checkHealth()
    }

    protected val client: CustomHttpClient = CustomHttpClient()

    protected abstract fun checkHealth(): Boolean
}