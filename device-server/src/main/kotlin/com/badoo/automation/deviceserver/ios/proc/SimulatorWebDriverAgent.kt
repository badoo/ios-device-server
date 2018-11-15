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
        wdaEndpoint: URI,
        debugXCTest: Boolean
) : WebDriverAgent(
        remote = remote,
        wdaRunnerXctest = wdaRunnerXctest,
        hostApp = wdaRunnerXctest.parentFile.parentFile.absolutePath,
        udid = udid,
        wdaEndpoint = wdaEndpoint,
        debugXCTest = debugXCTest
) {
    override fun start() {
        installHostApp()
        super.start()
    }

    override fun kill() {
        remote.fbsimctl.terminateApp(udid, "com.apple.test.WebDriverAgentRunner-Runner", false)
        super.kill()
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

        Thread.sleep(5000) // 5 should be ok
    }

    private fun isHostAppInstalled(): Boolean {
        return remote.fbsimctl.listApps(udid)
            .any { it.bundle.bundle_id.contains("WebDriverAgentRunner-Runner") }
    }
}