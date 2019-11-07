package com.badoo.automation.deviceserver.util

import com.badoo.automation.deviceserver.LogMarkers
import com.badoo.automation.deviceserver.data.UDID
import com.badoo.automation.deviceserver.host.IRemote
import com.badoo.automation.deviceserver.host.management.ApplicationBundle
import net.logstash.logback.marker.MapEntriesAppendingMarker
import org.slf4j.LoggerFactory
import org.slf4j.Marker
import java.io.File
import java.lang.RuntimeException
import java.time.Duration
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
    private val installTasks: MutableMap<String, Future<*>> = ConcurrentHashMap()


    fun installApplicationAsync(udid: UDID, appBundle: ApplicationBundle, appBinaryPath: File) {
        val logMarker = logMarker(udid)

        if (installTasks.contains(udid)) {
            val installTask = installTasks[udid]!!

            if (installTask.isDone) {
                val errorMessage = "Install operation is completed for $udid, but install task is not removed"
                logger.error(logMarker, errorMessage)
                throw RuntimeException(errorMessage)
                // TODO: probably remove this crap. to GC it is ok.
            } else {
                val errorMessage = "Install operation is still in progress for $udid"
                logger.error(logMarker, errorMessage)
                throw RuntimeException(errorMessage)
            }
        }

        installTasks[udid] = executorService.submit(Callable {
            performInstall(logMarker, udid, appBinaryPath, appBundle.bundleId)
        })
    }

    fun isApplicationInstalled(udid: UDID): Boolean {
        val installTask = installTasks[udid]

        return try {
            installTask != null
                    && installTask.isDone
                    && !installTask.isCancelled
                    && installTask.get() as Boolean
        } catch (e: ExecutionException) {
            logger.error(logMarker(udid), "Failed to install application. ExecutionException Exception during installation.", e)
            false
        } catch (e: InterruptedException) {
            logger.error(logMarker(udid), "Failed to install application. InterruptedException Exception during installation.", e)
            false
        }
    }

    private enum class InstallProgress(val status: String) {
        INSTALLING("installing"),
        INSTALLED("installed"),
        UNKNOWN("unknown"),
        FAILED("failed")
    }

    fun installProgress(udid: UDID): String {
        val task = installTasks[udid]

        return when {
            task == null -> InstallProgress.UNKNOWN.status
            !task.isDone -> InstallProgress.INSTALLING.status
            isApplicationInstalled(udid) -> InstallProgress.INSTALLED.status
            task.isDone && !isApplicationInstalled(udid) -> InstallProgress.FAILED.status
            else -> InstallProgress.UNKNOWN.status
        }
    }

    fun uninstallApplication(udid: UDID, bundleId: String) {
        val installTask = installTasks[udid]

        if (installTask != null) {
            if (!installTask.isDone) {
                installTask.cancel(true)
            }

            installTasks.remove(udid)
        }

        remote.exec(listOf("/usr/bin/xcrun", "simctl", "uninstall", udid, bundleId), mapOf(), false, 60)
    }

    private fun performInstall(logMarker: Marker, udid: UDID, appBinaryPath: File, bundleId: String): Boolean {
        logger.debug(logMarker, "Installing application $bundleId on simulator $udid")

        val nanos = measureNanoTime {
            val result = remote.execIgnoringErrors(listOf("/usr/bin/xcrun", "simctl", "install", udid, appBinaryPath.absolutePath), timeOutSeconds = 120)

            if (!result.isSuccess) {
                val errorMessage = "Failed to install application $bundleId to simulator $udid. Result: $result"
                logger.error(logMarker, errorMessage)
                return false
            }

            pollFor(
                Duration.ofSeconds(60),
                "Installing application $bundleId",
                false,
                Duration.ofSeconds(10),
                logger,
                logMarker
            ) {
                isAppInstalledOnSimulator(udid, bundleId)
            }
        }

        val seconds = TimeUnit.NANOSECONDS.toSeconds(nanos)
        val measurement = mutableMapOf(
            "action_name" to "install_application",
            "duration" to seconds
        )
        measurement.putAll(logMarkerDetails(udid))

        logger.debug(MapEntriesAppendingMarker(measurement), "Successfully installed application $bundleId on simulator $udid. Took $seconds seconds")

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
