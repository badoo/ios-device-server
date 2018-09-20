package com.badoo.automation.deviceserver.ios.proc

import com.badoo.automation.deviceserver.data.UDID
import com.badoo.automation.deviceserver.host.IRemote
import com.badoo.automation.deviceserver.util.pollFor
import java.io.File
import java.net.URI
import java.time.Duration

class SimulatorWebDriverAgent(
        remote: IRemote,
        wdaRunnerXctest: File,
        udid: UDID,
        wdaEndpoint: URI
) : WebDriverAgent(
        remote = remote,
        wdaRunnerXctest = wdaRunnerXctest,
        hostApp = wdaRunnerXctest.parentFile.parentFile.absolutePath,
        udid = udid,
        wdaEndpoint = wdaEndpoint
) {
    override fun start() {
        installHostApp()
        super.start()
    }

    private fun installHostApp() {
        remote.fbsimctl.installApp(udid, File(hostApp))

        pollFor(
            Duration.ofSeconds(20),
            "Installing WDA host application $hostApp",
            true,
            Duration.ofSeconds(2),
            logger,
            logMarker
        ) {
            isHostAppInstalled()
        }
    }

    private fun isHostAppInstalled(): Boolean {
        return remote.fbsimctl.listApps(udid)
            .any { it.bundle.bundle_id.contains("WebDriverAgentRunner-Runner") }
    }
}