package com.badoo.automation.deviceserver.util

import com.badoo.automation.deviceserver.LogMarkers
import com.badoo.automation.deviceserver.data.UDID
import com.badoo.automation.deviceserver.host.IRemote
import com.badoo.automation.deviceserver.ios.fbsimctl.FBSimctlError
import com.badoo.automation.deviceserver.ios.simulator.data.DataContainerException
import com.badoo.automation.deviceserver.ios.simulator.data.FileSystem
import net.logstash.logback.marker.MapEntriesAppendingMarker
import org.slf4j.LoggerFactory
import org.slf4j.Marker
import java.io.File
import java.io.FileNotFoundException
import java.lang.RuntimeException
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.*
import kotlin.system.measureNanoTime

class AppInstaller(
    private val remote: IRemote, private val installExecutor: ExecutorService = Executors.newCachedThreadPool()
) {
    private val logger = LoggerFactory.getLogger(javaClass.simpleName)

    private val commonLogMarkerDetails = mapOf(
        LogMarkers.HOSTNAME to remote.hostName
    )

    fun installApplication(
        udid: UDID, appUrl: String, appBinaryPath: File, isRealDevice: Boolean, bundleId: String
    ): Future<InstallResult> {
        val logMarker = logMarker(udid)
        logger.info(logMarker, "Installing app $appUrl on device $udid")

        return installExecutor.submit(Callable {
            try {
                return@Callable if (isRealDevice) {
                    performInstallRealDevice(logMarker, udid, appBinaryPath, appUrl, bundleId)
                } else {
                    performInstallSimulator(logMarker, udid, appBinaryPath, appUrl, bundleId)
                }
            } catch (e: RuntimeException) {
                val errorMessage = "Error happened while installing the app $appUrl on $udid. ${e.message}"
                logger.error(logMarker, errorMessage, e)
                return@Callable InstallResult(false, errorMessage)
            }
        })
    }

    fun uninstallApplication(udid: UDID, bundleId: String) {
        val logMarker = logMarker(udid)
        val uninstallTask = installExecutor.submit(Callable {
            try {
                logger.debug(logMarker, "Uninstalling application $bundleId from Simulator $udid")
                val uninstallResult =
                    remote.exec(listOf("/usr/bin/xcrun", "simctl", "uninstall", udid, bundleId), mapOf(), false, 60)
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
        val stopResult = remote.exec(
            listOf(
                "/usr/bin/xcrun", "simctl", "spawn", udid, "launchctl", "stop", "com.apple.containermanagerd"
            ), mapOf(), true, 60
        )
        if (!stopResult.isSuccess) {
            logger.error(logMarker, "Failed to stop com.apple.containermanagerd for $udid")
        }
        val deleteResult = remote.exec(
            listOf("/bin/rm", "-rf", "/Users/qa/$udid/data/Library/Caches/com.apple.containermanagerd"),
            mapOf(),
            true,
            60
        )
        if (!deleteResult.isSuccess) {
            logger.error(logMarker, "Failed to clear cache of com.apple.containermanagerd for $udid")
        }
        val startResult = remote.exec(
            listOf(
                "/usr/bin/xcrun", "simctl", "spawn", udid, "launchctl", "start", "com.apple.containermanagerd"
            ), mapOf(), true, 60
        )
        if (!startResult.isSuccess) {
            logger.error(logMarker, "Failed to start com.apple.containermanagerd for $udid")
        }
    }

    private fun performInstallSimulator(
        logMarker: Marker, udid: UDID, appBinaryPath: File, appUrl: String, bundleId: String
    ): InstallResult {
        logger.debug(logMarker, "Installing application $appUrl on simulator $udid")

        var isAppInstalled = false
        val installTimeNanos = measureNanoTime {
            cleanup(udid, logMarker)
            logger.debug(
                logMarker,
                "Will install application $appUrl on simulator $udid using xcrun simctl install ${appBinaryPath.absolutePath}"
            )
            val result = remote.exec(
                listOf("/usr/bin/xcrun", "simctl", "install", udid, appBinaryPath.absolutePath), mapOf(), true, 90L
            )

            if (!result.isSuccess) {
                val errorMessage = "Failed to install application $appUrl to simulator $udid. Result: $result"
                logger.error(logMarker, errorMessage)
                return InstallResult(false, errorMessage)
            }

            val fileSystem = FileSystem(remote, udid)

            pollFor(
                timeOut = Duration.ofSeconds(60),
                reasonName = "App installation on simulator $udid ${remote.publicHostName}",
                logger = logger,
                marker = logMarker,
            ) {
                try {
                    val applicationContainer = fileSystem.applicationContainer(bundleId)
                    val fileName = applicationContainer.listFiles(Path.of("Info.plist")).first().trim()
                    isAppInstalled = fileName.isNotBlank() && fileName.endsWith("/Info.plist")

                    if (isAppInstalled) {
                        logger.warn(logMarker, "App is not installed on simulator $udid yet ${remote.publicHostName}")
                    }

                    isAppInstalled
                } catch (e: DataContainerException) {
                    logger.error(logMarker, "Error while checking if app is installed on simulator $udid", e)
                    return@pollFor false
                } catch (e: FileNotFoundException) {
                    logger.error(logMarker, "Error while checking if app is installed on simulator $udid", e)
                    return@pollFor false
                }
            }
        }

        val seconds = TimeUnit.NANOSECONDS.toSeconds(installTimeNanos)
        val measurement = mutableMapOf(
            "action_name" to "install_application",
            "duration" to seconds,
            "is_success" to isAppInstalled.toString(),
        )
        measurement.putAll(logMarkerDetails(udid))
        val marker = MapEntriesAppendingMarker(measurement)

        if (isAppInstalled) {
            logger.debug(marker, "Successfully installed application $appUrl on simulator $udid. Took $seconds seconds")
        } else {
            logger.error(marker, "Failed to install application $appUrl on simulator $udid. Took $seconds seconds")
        }

        return InstallResult(isAppInstalled, null)
    }

    private fun performInstallRealDevice(
        logMarker: Marker, udid: UDID, appBinaryPath: File, appUrl: String, bundleId: String
    ): InstallResult {
        logger.debug(logMarker, "Installing application $appUrl on device $udid")

        val nanos = measureNanoTime {
            logger.debug(
                logMarker,
                "Will install application $appUrl on device $udid using fbsimctl install ${appBinaryPath.absolutePath}"
            )
            try {
                remote.fbsimctl.installApp(udid, appBinaryPath)
            } catch (e: FBSimctlError) {
                logger.error(logMarker, "Error happened while installing the app $appUrl on $udid", e)
                return InstallResult(false, e.message)
            }
        }

        val seconds = TimeUnit.NANOSECONDS.toSeconds(nanos)
        val measurement = mutableMapOf(
            "action_name" to "install_application", "duration" to seconds
        )
        measurement.putAll(logMarkerDetails(udid))
        logger.debug(
            MapEntriesAppendingMarker(measurement),
            "Successfully installed application $appUrl on device $udid. Took $seconds seconds"
        )
        return InstallResult(true, null)
    }

    private fun logMarker(udid: UDID) = MapEntriesAppendingMarker(logMarkerDetails(udid))

    private fun logMarkerDetails(udid: UDID): Map<String, String> {
        return commonLogMarkerDetails + mapOf(
            LogMarkers.DEVICE_REF to deviceRefFromUDID(
                udid, remote.publicHostName
            ), LogMarkers.UDID to udid
        )
    }
}
