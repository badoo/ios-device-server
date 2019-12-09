package com.badoo.automation.deviceserver.util

import com.badoo.automation.deviceserver.LogMarkers
import com.badoo.automation.deviceserver.data.UDID
import com.badoo.automation.deviceserver.host.IRemote
import net.logstash.logback.marker.MapEntriesAppendingMarker
import org.slf4j.LoggerFactory
import org.slf4j.Marker
import java.io.File
import java.lang.RuntimeException
import java.util.concurrent.*
import kotlin.system.measureNanoTime

class AppInstaller(
    private val executorService: ExecutorService,
    private val remote: IRemote
) {
    private val logger = LoggerFactory.getLogger(javaClass.simpleName)

    private val commonLogMarkerDetails = mapOf(
        LogMarkers.HOSTNAME to remote.hostName
    )

    fun installApplication(udid: UDID, appUrl: String, appBinaryPath: File): Boolean {
        val logMarker = logMarker(udid)
        logger.info(logMarker, "Installing app $appUrl on device $udid")

        val installTask = executorService.submit(Callable {
            try {
                return@Callable performInstall(logMarker, udid, appBinaryPath, appUrl)
            } catch (e: RuntimeException) {
                logger.error(logMarker, "Error happened while installing the app $appUrl on $udid", e)
                return@Callable false
            }
        })

        val result = installTask.get()
        if (!result) {
            logger.error(logMarker, "Install application $appUrl to $udid was unsuccessful.")
        }
        return result
    }

    private fun terminateApplication(logMarker: Marker, bundleId: String, udid: UDID) {
        val terminateResult = remote.execIgnoringErrors(listOf("xcrun", "simctl", "terminate", udid, bundleId))

        if (!terminateResult.isSuccess) {
            logger.error(logMarker, "Terminating application $bundleId was unsuccessful. Result $terminateResult")
        }
    }

    fun uninstallApplication(udid: UDID, bundleId: String) {
        val logMarker = logMarker(udid)

        if (!isAppInstalledOnSimulator(udid, bundleId)) {
            logger.debug(logMarker, "Application $bundleId is not installed on Simulator $udid")
            return
        }

        terminateApplication(logMarker, bundleId, udid)

        val uninstallTask = executorService.submit(Callable {
            try {
                logger.debug(logMarker, "Uninstalling application $bundleId from Simulator $udid")
                val uninstallResult = remote.exec(listOf("/usr/bin/xcrun", "simctl", "uninstall", udid, bundleId), mapOf(), false, 60)
                return@Callable uninstallResult.isSuccess
            } catch (e: RuntimeException) {
                logger.error(logMarker, "Error occured while uninstalling the app $bundleId on $udid", e)
                return@Callable false
            }
        })

        val result = uninstallTask.get()
        if (!result) {
            logger.error(logMarker, "Uninstall application $bundleId was unsuccessful.")
        }
    }

    private fun performInstall(logMarker: Marker, udid: UDID, appBinaryPath: File, appUrl: String): Boolean {
        logger.debug(logMarker, "Installing application $appUrl on simulator $udid")

        val nanos = measureNanoTime {
            logger.debug(logMarker, "Will install application $appUrl on simulator $udid using xcrun simctl install ${appBinaryPath.absolutePath}")
            val result = remote.exec(listOf("/usr/bin/xcrun", "simctl", "install", udid, appBinaryPath.absolutePath), mapOf(), true, 90L)

            if (!result.isSuccess) {
                val errorMessage = "Failed to install application $appUrl to simulator $udid. Result: $result"
                logger.error(logMarker, errorMessage)
                return false
            }
        }

        val seconds = TimeUnit.NANOSECONDS.toSeconds(nanos)
        val measurement = mutableMapOf(
            "action_name" to "install_application",
            "duration" to seconds
        )
        measurement.putAll(logMarkerDetails(udid))
        logger.debug(MapEntriesAppendingMarker(measurement), "Successfully installed application $appUrl on simulator $udid. Took $seconds seconds")
        return true
    }

    private fun logMarker(udid: UDID) = MapEntriesAppendingMarker(logMarkerDetails(udid))

    private fun logMarkerDetails(udid: UDID): Map<String, String> {
        return commonLogMarkerDetails + mapOf(
            LogMarkers.DEVICE_REF to newDeviceRef(
                udid,
                remote.publicHostName
            ), LogMarkers.UDID to udid
        )
    }

    private fun isAppInstalledOnSimulator(udid: UDID, bundleId: String): Boolean {
        val result = remote.execIgnoringErrors(listOf(
            "/usr/bin/xcrun",
            "simctl",
            "get_app_container",
            udid,
            bundleId
        ))

        return when(result.exitCode) {
            0 -> true
            2 -> false
            else -> throw RuntimeException("Failed to check if app is installed. Result: $result")
        }
    }
}
