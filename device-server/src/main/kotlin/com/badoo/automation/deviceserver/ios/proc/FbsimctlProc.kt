package com.badoo.automation.deviceserver.ios.proc

import com.badoo.automation.deviceserver.command.ChildProcess
import com.badoo.automation.deviceserver.host.IRemote
import com.badoo.automation.deviceserver.ios.fbsimctl.FBSimctl
import com.badoo.automation.deviceserver.util.ensure
import com.badoo.automation.deviceserver.util.uriWithPath
import java.net.URI

open class FbsimctlProc(
    private val remote: IRemote,
    protected val udid: String,
    protected val fbsimctlEndpoint: URI,
    val headless: Boolean,
    private val childFactory: (
        remoteHost: String,
        username: String,
        cmd: List<String>,
        isInteractiveShell: Boolean,
        out_reader: (line: String) -> Unit,
        err_reader: (line: String) -> Unit
    ) -> ChildProcess = ChildProcess.Companion::fromCommand
) : LongRunningProc(udid, remote.hostName) {
    private val uri: URI = uriWithPath(fbsimctlEndpoint, "list")

    override fun toString(): String = "<$udid at ${remote.hostName}:${fbsimctlEndpoint.port}>"

    override fun start() {
        ensure(childProcess == null) { FbsimctlProcError("Previous fbsimctl process $childProcess has not been killed") }
        logger.debug(logMarker, "$this — Starting child process")

        childProcess = childFactory(
                remote.hostName,
                remote.userName,
                getFbsimctlCommand(headless),
                true,
                { logger.info(logMarker, "${this@FbsimctlProc}: FbSimCtl <o>: ${it.trim()}") },
                { logger.warn(logMarker, "${this@FbsimctlProc}: FbSimCtl <e>: ${it.trim()}") }
        )

        logger.debug(logMarker, "$this FBSimCtl: $childProcess")
    }

    override fun checkHealth(): Boolean {
        return try {
            val result = client.get(uri.toURL())

            return if (result.isSuccess) {
                true
            } else {
                logger.debug(logMarker, "Failed fbsimctl health check. Response: $result")
                false
            }
        } catch (e: RuntimeException) {
            logger.warn(logMarker, "Failed to determine fbsimctl device state. Exception: $e")
            false
        }
    }

    protected open fun getFbsimctlCommand(headless: Boolean): List<String> {
        val cmd = mutableListOf(
            FBSimctl.FBSIMCTL_BIN,
            udid,
            "boot"
        )

        if (headless) {
            cmd.add("--direct-launch")
        }

        cmd.addAll(listOf(
            "--",
            "listen",
            "--http",
            fbsimctlEndpoint.port.toString()
        ))

        return cmd
    }
}
