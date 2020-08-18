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
    private val remote: IRemote,
    private val installExecutor: ExecutorService = Executors.newCachedThreadPool()
) {
    private val logger = LoggerFactory.getLogger(javaClass.simpleName)

    private val commonLogMarkerDetails = mapOf(
        LogMarkers.HOSTNAME to remote.hostName
    )

    fun installApplication(udid: UDID, appUrl: String, appBinaryPath: File): Future<Boolean> {
        val logMarker = logMarker(udid)
        logger.info(logMarker, "Installing app $appUrl on device $udid")

        val installTask = installExecutor.submit(Callable {
            try {
                return@Callable performInstall(logMarker, udid, appBinaryPath, appUrl)
            } catch (e: RuntimeException) {
                logger.error(logMarker, "Error happened while installing the app $appUrl on $udid", e)
                return@Callable false
            }
        })

        return installTask
    }

    fun uninstallApplication(udid: UDID, bundleId: String) {
        val logMarker = logMarker(udid)
        val uninstallTask = installExecutor.submit(Callable {
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

    private fun cleanup(udid: String, logMarker: Marker) {
        val stopResult = remote.exec(listOf("/usr/bin/xcrun", "simctl", "spawn", udid, "launchctl", "stop", "com.apple.containermanagerd"), mapOf(), true, 60)
        if (!stopResult.isSuccess){
            logger.error(logMarker, "Failed to stop com.apple.containermanagerd for $udid")
        }
        val deleteResult = remote.exec(listOf("/bin/rm", "-rf", "/Users/qa/$udid/data/Library/Caches/com.apple.containermanagerd"), mapOf(), true, 60)
        if (!deleteResult.isSuccess){
            logger.error(logMarker, "Failed to clear cache of com.apple.containermanagerd for $udid")
        }
        val startResult = remote.exec(listOf("/usr/bin/xcrun", "simctl", "spawn", udid, "launchctl", "start", "com.apple.containermanagerd"), mapOf(), true, 60)
        if (!startResult.isSuccess){
            logger.error(logMarker, "Failed to start com.apple.containermanagerd for $udid")
        }
    }

    private fun performInstall(logMarker: Marker, udid: UDID, appBinaryPath: File, appUrl: String): Boolean {
        logger.debug(logMarker, "Installing application $appUrl on simulator $udid")

        val nanos = measureNanoTime {
            cleanup(udid, logMarker)
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
            LogMarkers.DEVICE_REF to deviceRefFromUDID(
                udid,
                remote.publicHostName
            ), LogMarkers.UDID to udid
        )
    }

    fun isAppInstalledOnSimulator(udid: UDID, bundleId: String): Boolean {
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
