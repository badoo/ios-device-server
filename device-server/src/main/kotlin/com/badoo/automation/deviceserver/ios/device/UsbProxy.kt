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
        environment: Map<String, String>,
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

    private var iproxy: ChildProcess? = null
    private var socat: ChildProcess? = null

    fun start() {
        iproxy = childFactory(
            remote.hostName,
            remote.userName,
            listOf(IPROXY_BIN, localPort.toString(), devicePort.toString(), udid),
            false,
            mapOf(),
            { message -> logger.debug(logMarker, "${this}: iproxy <o>: ${message.trim()}") },
            { message -> logger.debug(logMarker, "${this}: iproxy <e>: ${message.trim()}") }
        )

        socat = childFactory(
            remote.hostName,
            remote.userName,
            listOf(SOCAT_BIN, "tcp-listen:$localPort,reuseaddr,fork", "tcp:0.0.0.0:$localPort"),
            false,
            mapOf(),
            { message -> logger.debug(logMarker, "${this}: socat <o>: ${message.trim()}") },
            { message -> logger.debug(logMarker, "${this}: socat <e>: ${message.trim()}") }
        )
    }

    fun isHealthy(): Boolean {
        return (iproxy?.isAlive() ?: false) && (socat?.isAlive() ?: false)
    }

    fun stop() {
        if (iproxy != null) {
            logger.debug(logMarker, "$this — Killing child process $iproxy")
            iproxy!!.kill()
            iproxy = null
        }

        if (socat !=null) {
            logger.debug(logMarker, "$this — Killing child process $socat")
            socat!!.kill()
            socat = null
        }
    }

    companion object {
        const val IPROXY_BIN = "/usr/local/bin/iproxy"
        const val SOCAT_BIN = "/usr/local/bin/socat"
    }
}
