package com.badoo.automation.deviceserver.ios.device

import com.badoo.automation.deviceserver.data.UDID
import com.badoo.automation.deviceserver.host.IRemote
import com.badoo.automation.deviceserver.ios.proc.WebDriverAgent
import java.net.URI
import java.io.File

class DeviceWebDriverAgent(
    remote: IRemote,
    wdaRunnerXctest: File,
    udid: UDID,
    wdaEndpoint: URI,
    port: Int,
    hostApp: String = wdaRunnerXctest.parentFile.parentFile.absolutePath
) : WebDriverAgent(
    remote = remote,
    wdaRunnerXctest = wdaRunnerXctest,
    hostApp = hostApp,
    udid = udid,
    wdaEndpoint = wdaEndpoint,
    port = port,
    debugXCTest = true
) {
    override fun terminateHostApp() {
        remote.fbsimctl.uninstallApp(udid, hostApp)
        Thread.sleep(1000)
    }
}