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
        mjpegServerPort: Int
) : WebDriverAgent(
        remote = remote,
        wdaRunnerXctest = wdaRunnerXctest,
        hostApp = wdaRunnerXctest.parentFile.parentFile.absolutePath,
        udid = udid,
        wdaEndpoint = wdaEndpoint,
        mjpegServerPort = mjpegServerPort
) {
    override fun start() {
        installHostApp()
        super.start()
    }

    private fun installHostApp() {
        val result = remote.execIgnoringErrors(listOf("xcrun", "simctl", "install", udid, hostApp))

        if (!result.isSuccess) {
            val errorMessage = "Failed to install WebDriverAgent $hostApp to simulator $udid. Result: $result"
            logger.error(logMarker, errorMessage)
            throw RuntimeException(errorMessage)
        }

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
