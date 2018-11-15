package com.badoo.automation.deviceserver.ios.device

import com.badoo.automation.deviceserver.command.ChildProcess
import com.badoo.automation.deviceserver.host.IRemote
import com.badoo.automation.deviceserver.ios.fbsimctl.FBSimctl
import com.badoo.automation.deviceserver.ios.proc.FbsimctlProc
import java.net.URI

class DeviceFbsimctlProc(
    remote: IRemote,
    udid: String,
    fbsimctlEndpoint: URI,
    headless: Boolean,
    childFactory: (
        remoteHost: String,
        username: String,
        cmd: List<String>,
        isInteractiveShell: Boolean,
        environment: Map<String, String>,
        out_reader: (line: String) -> Unit,
        err_reader: (line: String) -> Unit
    ) -> ChildProcess = ChildProcess.Companion::fromCommand
) : FbsimctlProc(remote, udid, fbsimctlEndpoint, headless, childFactory) {

    override fun getFbsimctlCommand(headless: Boolean): List<String> {

        return listOf(
            FBSimctl.FBSIMCTL_BIN,
            udid,
            "listen",
            "--http",
            fbsimctlEndpoint.port.toString()
        )
    }

}