package com.badoo.automation.deviceserver.ios.proc

import com.badoo.automation.deviceserver.LogMarkers
import com.badoo.automation.deviceserver.data.DeviceRef
import com.badoo.automation.deviceserver.data.UDID
import com.badoo.automation.deviceserver.host.IRemote
import com.badoo.automation.deviceserver.ios.simulator.SimulatorProcess
import com.badoo.automation.deviceserver.util.CustomHttpClient
import com.badoo.automation.deviceserver.util.pollFor
import com.badoo.automation.deviceserver.util.uriWithPath
import net.logstash.logback.marker.MapEntriesAppendingMarker
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.File
import java.lang.RuntimeException
import java.net.URI
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.system.measureNanoTime

class SimulatorXcrunWebDriverAgent(
    private val remote: IRemote,
    wdaRunnerXctest: File,
    private val udid: UDID,
    private val wdaEndpoint: URI,
    private val mjpegServerPort: Int,
    private val simulatorProcess: SimulatorProcess,
    deviceRef: DeviceRef
) : IWebDriverAgent {
    private val logger: Logger = LoggerFactory.getLogger(javaClass.simpleName)
    private val logMarker = MapEntriesAppendingMarker(mapOf(
        LogMarkers.HOSTNAME to remote.publicHostName,
        LogMarkers.UDID to udid
    ))
    private val commonLogMarkerDetails = mapOf(
        LogMarkers.DEVICE_REF to deviceRef,
        LogMarkers.UDID to udid,
        LogMarkers.HOSTNAME to remote.hostName
    )


    private val hostApp = wdaRunnerXctest.parentFile.parentFile.absolutePath

    private val wdaBundleId = "com.facebook.WebDriverAgentRunner.dev2.xctrunner"
    private val uri: URI = uriWithPath(wdaEndpoint, "status")
    private val client: CustomHttpClient = CustomHttpClient()

    override fun isHealthy(): Boolean {
        return try {
            val result = client.get(uri.toURL())
            result.isSuccess
        } catch (e: RuntimeException) {
            logger.warn(logMarker, "Failed to determine WDA driver state. Exception: $e")
            false
        }
    }

    override fun kill() {
        remote.execIgnoringErrors(
            listOf(
                "/usr/bin/xcrun",
                "simctl",
                "terminate",
                udid,
                wdaBundleId
            )
        )
    }

    override fun start() {
        if (!isHostAppInstalled()) {
            installHostApp()
        }

        val cmd = listOf(
            "/usr/bin/xcrun",
            "simctl",
            "launch",
            udid,
            wdaBundleId,
            "--port", wdaEndpoint.port.toString(),
            "--mjpeg-server-port", mjpegServerPort.toString(),
            "--mjpeg-server-frame-rate", 4.toString()
        )

        val result = remote.execIgnoringErrors(cmd, timeOutSeconds = 90)

        if (!result.isSuccess) {
            logger.error(logMarker, "Failed to start WDA on Simulator with xcrun simctl. Stderr: ${result.stdErr}")
            throw RuntimeException("Failed to start WDA on Simulator with xcrun simctl")
        }
    }

    // /usr/bin/xcrun simctl get_app_container BA8B8CD8-06A0-45CB-A746-A897A3746DCB com.facebook.WebDriverAgentRunner.dev2.xctrunner

    private fun uninstallHostApp() {
        remote.execIgnoringErrors(listOf("/usr/bin/xcrun", "simctl", "uninstall", udid, wdaBundleId))
    }

    override fun installHostApp() {
        logger.debug(logMarker, "Installing WDA on Simulator with xcrun simctl")

        val nanos = measureNanoTime {
            val result = remote.execIgnoringErrors(listOf("xcrun", "simctl", "install", udid, hostApp), timeOutSeconds = 120)

            if (!result.isSuccess) {
                val errorMessage = "Failed to install WebDriverAgent $hostApp to simulator $udid. Result: $result"
                logger.error(logMarker, errorMessage)
                throw RuntimeException(errorMessage)
            }

            pollFor(
                Duration.ofSeconds(30),
                "Installing WDA host application $hostApp",
                true,
                Duration.ofSeconds(5),
                logger,
                logMarker
            ) {
                isHostAppInstalled()
            }
        }

        val seconds = TimeUnit.NANOSECONDS.toSeconds(nanos)
        val measurement = mutableMapOf(
            "action_name" to "install_WDA",
            "duration" to seconds
        )
        measurement.putAll(commonLogMarkerDetails)

        logger.debug(MapEntriesAppendingMarker(measurement), "Successfully installed WDA on Simulator with xcrun simctl. Took $seconds seconds")
    }

    private fun zisHostAppInstalled(): Boolean {
        return remote.fbsimctl.listApps(udid).any { it.bundle.bundle_id.contains("WebDriverAgentRunner-Runner") }
    }
    private fun isHostAppInstalled(): Boolean {
        val result = remote.execIgnoringErrors(listOf(
            "/usr/bin/xcrun",
            "simctl",
            "get_app_container",
            udid,
            wdaBundleId
        ))

        return result.isSuccess
    }
}