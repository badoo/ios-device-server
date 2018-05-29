package com.badoo.automation.deviceserver.ios.device

import com.badoo.automation.deviceserver.data.UDID
import com.badoo.automation.deviceserver.host.IRemote
import com.badoo.automation.deviceserver.ios.proc.WebDriverAgent
import java.net.URI
import java.nio.file.Paths

class DeviceWebDriverAgent(
    remote: IRemote,
    wdaPath: String,
    udid: UDID,
    wdaEndpoint: URI
) : WebDriverAgent(
    remote = remote,
    wdaPath = wdaPath,
    hostApp = Paths.get(wdaPath).parent.parent.toString(),
    udid = udid,
    wdaEndpoint = wdaEndpoint
) {
    override fun terminateHostApp() {
        remote.fbsimctl.uninstallApp(udid, "com.apple.test.WebDriverAgentRunner-Runner")
        Thread.sleep(1000)
    }
}