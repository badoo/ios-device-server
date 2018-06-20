package com.badoo.automation.deviceserver.ios.device

import com.badoo.automation.deviceserver.LogMarkers
import com.badoo.automation.deviceserver.command.ChildProcess
import com.badoo.automation.deviceserver.data.UDID
import com.badoo.automation.deviceserver.host.IRemote
import net.logstash.logback.marker.MapEntriesAppendingMarker
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class UsbProxy(
    private val udid: UDID,
    private val remote: IRemote,
    val localPort: Int,
    val devicePort: Int,
    private val childFactory: (
        remoteHost: String,
        userName: String,
        cmd: List<String>,
        isInteractiveShell: Boolean,
        out_reader: (line: String) -> Unit,
        err_reader: (line: String) -> Unit
    ) -> ChildProcess = ChildProcess.Companion::fromCommand
) {
    private val logger: Logger = LoggerFactory.getLogger(javaClass.simpleName)
    private val logMarker = MapEntriesAppendingMarker(
        mapOf(
            LogMarkers.HOSTNAME to remote.hostName,
            LogMarkers.UDID to udid
        )
    )

    override fun toString(): String = "<iproxy $localPort $devicePort $udid>"

    private var childProcess: ChildProcess? = null

    fun start() {
        childProcess = childFactory(
            remote.hostName,
            remote.userName,
            listOf("iproxy", localPort.toString(), devicePort.toString(), udid),
            false,
            { message -> logger.info(logMarker, "${this}: iproxy <o>: ${message.trim()}") },
            { message -> logger.warn(logMarker, "${this}: iproxy <e>: ${message.trim()}") }
        )
    }

    fun isHealthy(): Boolean {
        return childProcess?.isAlive() ?: false
    }

    fun stop() {
        if (childProcess == null) {
            return
        }

        logger.debug(logMarker, "$this — Killing child process $childProcess")
        childProcess!!.kill()
        childProcess = null
    }
}
