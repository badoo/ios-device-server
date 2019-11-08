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
    private val installTasksCache: ConcurrentHashMap<String, Future<*>> = ConcurrentHashMap()
    private val appBinariesCache: ConcurrentHashMap<String, File> = ConcurrentHashMap()

    fun installApplicationAsync(udid: UDID, appBundle: ApplicationBundle, appBinaryPath: File) {
        val logMarker = logMarker(udid)
        logger.info(logMarker, "Installing app ${appBundle.bundleId} on device $udid asynchronously")

        val installTask = installTasksCache[udid]

        if (installTask != null) {
            if (installTask.isDone) {
                installTasksCache.remove(udid)
                logger.info(logMarker, "Removed previous installer task on device $udid asynchronously")
            } else {
                val errorMessage = "Install operation is still in progress for $udid"
                logger.error(logMarker, errorMessage)
                throw RuntimeException(errorMessage)
            }
        }

        logger.debug(logMarker, "Scheduling install app ${appBundle.bundleId} on $udid")
        installTasksCache[udid] = executorService.submit(Callable {
            try {
                return@Callable performInstall(logMarker, udid, appBinaryPath, appBundle)
            } catch (e: RuntimeException) {
                logger.error(logMarker, "Error happenned while installing the app ${appBundle.bundleId} on $udid", e)
                return@Callable false
            }
        })
    }

    fun isApplicationInstalled(udid: UDID): Boolean {
        val installTask = installTasksCache[udid]

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
        val task = installTasksCache[udid]

        return when {
            task == null -> InstallProgress.UNKNOWN.status
            !task.isDone -> InstallProgress.INSTALLING.status
            isApplicationInstalled(udid) -> InstallProgress.INSTALLED.status
            task.isDone && !isApplicationInstalled(udid) -> InstallProgress.FAILED.status
            else -> InstallProgress.UNKNOWN.status
        }
    }

    fun uninstallApplication(udid: UDID, bundleId: String) {
        val logMarker = logMarker(udid)
        val installTask = installTasksCache[udid]

        if (installTask != null) {
            if (installTask.isDone) {
                installTasksCache.remove(udid)
            } else {
                logger.error(logMarker, "Error happened while uninstalling $bundleId from $udid")
            }
        }

        remote.exec(listOf("/usr/bin/xcrun", "simctl", "uninstall", udid, bundleId), mapOf(), false, 60)
    }

    private fun performInstall(logMarker: Marker, udid: UDID, appBinaryPath: File, appBundle: ApplicationBundle): Boolean {
        logger.debug(logMarker, "Installing application ${appBundle.bundleId} on simulator $udid")

        lateinit var unpackedBinaryFolder: File

        val cachedBinaryFolder = appBinariesCache[appBundle.appUrl]
        if (cachedBinaryFolder == null) {
            if (appBinaryPath.extension == "zip") {
                if (remote.exec(listOf("/usr/bin/unzip", "-t", appBinaryPath.absolutePath), mapOf(), false, 60L).isSuccess) {
                    logger.debug(logMarker, "Downloaded application archive seems to be good and tested for $udid")
                }

                val unpackFolder = File(appBinaryPath.absolutePath + ".extracted")

                remote.exec(listOf("/bin/mkdir", "-p", unpackFolder.absolutePath), mapOf(), false, 60L)

                remote.exec(listOf("/usr/bin/unzip", appBinaryPath.absolutePath, "-d", unpackFolder.absolutePath), mapOf(), false, 90L)

                val result = remote.exec(listOf("/usr/bin/find",  unpackFolder.absolutePath, "-maxdepth", "1", "-type", "d"), mapOf(), false, 60L)
                if (result.isSuccess) {
                    logger.debug(logMarker(udid), "Found these folders: ${result.stdOut.lines().joinToString("")}")
                    unpackedBinaryFolder = File(result.stdOut.lines().first { it != unpackFolder.absolutePath })
                }
            } else {
                unpackedBinaryFolder = appBinaryPath
            }

            appBinariesCache[appBundle.appUrl] = unpackedBinaryFolder
        } else {
            unpackedBinaryFolder = cachedBinaryFolder
            logger.debug(logMarker, "Using cached unpacked version of application ${appBundle.bundleId} on simulator $udid")
        }

        val nanos = measureNanoTime {
            logger.debug(logMarker, "Will install application ${appBundle.bundleId} on simulator $udid using xcrun simctl install ${unpackedBinaryFolder.absolutePath}")
            val result = remote.exec(listOf("/usr/bin/xcrun", "simctl", "install", udid, unpackedBinaryFolder.absolutePath), mapOf(), true, 90L)

            if (result.isSuccess) {
                logger.debug(logMarker, "Install command completed successfully")
            } else {
                val errorMessage = "Failed to install application ${appBundle.bundleId} to simulator $udid. Result: $result"
                logger.error(logMarker, errorMessage)
                return false
            }

            pollFor(
                Duration.ofSeconds(20),
                "Installing application ${appBundle.bundleId}",
                true,
                Duration.ofSeconds(4),
                logger,
                logMarker
            ) {
                isAppInstalledOnSimulator(udid, appBundle.bundleId)
            }
        }

        val seconds = TimeUnit.NANOSECONDS.toSeconds(nanos)
        val measurement = mutableMapOf(
            "action_name" to "install_application",
            "duration" to seconds
        )
        measurement.putAll(logMarkerDetails(udid))

        logger.debug(MapEntriesAppendingMarker(measurement), "Successfully installed application ${appBundle.bundleId} on simulator $udid. Took $seconds seconds")

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
