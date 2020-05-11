package com.badoo.automation.deviceserver.ios.proc

import com.badoo.automation.deviceserver.LogMarkers
import com.badoo.automation.deviceserver.data.DeviceRef
import com.badoo.automation.deviceserver.data.UDID
import com.badoo.automation.deviceserver.host.IRemote
import com.badoo.automation.deviceserver.util.pollFor
import net.logstash.logback.marker.MapEntriesAppendingMarker
import java.io.File
import java.net.URI
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.system.measureNanoTime

class SimulatorWebDriverAgent(
    remote: IRemote,
    wdaRunnerXctest: File,
    udid: UDID,
    wdaEndpoint: URI,
    mjpegServerPort: Int,
    deviceRef: DeviceRef
) : WebDriverAgent(
        remote = remote,
        wdaRunnerXctest = wdaRunnerXctest,
        hostApp = wdaRunnerXctest.parentFile.parentFile.absolutePath,
        udid = udid,
        wdaEndpoint = wdaEndpoint,
        mjpegServerPort = mjpegServerPort
) {
    private val commonLogMarkerDetails = mapOf(
        LogMarkers.DEVICE_REF to deviceRef,
        LogMarkers.UDID to udid,
        LogMarkers.HOSTNAME to remote.hostName
    )

    override fun start() {
        installHostApp()
        super.start()
    }

    override fun installHostApp() {
        logger.debug(logMarker, "Installing WDA $hostApp on Simulator with xcrun simctl")

        val nanos = measureNanoTime {
            val result = remote.execIgnoringErrors(listOf("xcrun", "simctl", "install", udid, hostApp))

            if (!result.isSuccess) {
                val errorMessage = "Failed to install WebDriverAgent $hostApp to simulator $udid. Result: $result"
                logger.error(logMarker, errorMessage)
                throw RuntimeException(errorMessage)
            }

            pollFor(
                Duration.ofSeconds(30),
                "Installing WDA host application $hostApp",
                true,
                Duration.ofSeconds(2),
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

    private fun isHostAppInstalled(): Boolean {
        return remote.fbsimctl.listApps(udid)
            .any { it.bundle.bundle_id.contains(wdaRunnerXctest.parentFile.parentFile.nameWithoutExtension) }
    }
}
