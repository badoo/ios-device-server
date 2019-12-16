package com.badoo.automation.deviceserver.ios.simulator

import com.badoo.automation.deviceserver.ApplicationConfiguration
import com.badoo.automation.deviceserver.LogMarkers
import com.badoo.automation.deviceserver.WaitTimeoutError
import com.badoo.automation.deviceserver.command.ShellUtils
import com.badoo.automation.deviceserver.data.*
import com.badoo.automation.deviceserver.host.IRemote
import com.badoo.automation.deviceserver.ios.fbsimctl.FBSimctlDeviceState
import com.badoo.automation.deviceserver.ios.proc.*
import com.badoo.automation.deviceserver.ios.simulator.backup.ISimulatorBackup
import com.badoo.automation.deviceserver.ios.simulator.backup.SimulatorBackup
import com.badoo.automation.deviceserver.ios.simulator.data.DataContainer
import com.badoo.automation.deviceserver.ios.simulator.data.FileSystem
import com.badoo.automation.deviceserver.ios.simulator.data.Media
import com.badoo.automation.deviceserver.ios.simulator.diagnostic.OsLog
import com.badoo.automation.deviceserver.ios.simulator.diagnostic.SystemLog
import com.badoo.automation.deviceserver.ios.simulator.video.MJPEGVideoRecorder
import com.badoo.automation.deviceserver.ios.simulator.video.SimulatorVideoRecorder
import com.badoo.automation.deviceserver.ios.simulator.video.VideoRecorder
import com.badoo.automation.deviceserver.util.AppInstaller
import com.badoo.automation.deviceserver.util.executeWithTimeout
import com.badoo.automation.deviceserver.util.pollFor
import kotlinx.coroutines.experimental.*
import net.logstash.logback.marker.MapEntriesAppendingMarker
import org.slf4j.LoggerFactory
import org.slf4j.Marker
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URI
import java.net.URL
import java.nio.file.Paths
import java.time.Duration
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.system.measureNanoTime

class Simulator(
        private val deviceRef: DeviceRef,
        private val remote: IRemote,
        private val deviceInfo: DeviceInfo,
        private val allocatedPorts: DeviceAllocatedPorts,
        private val deviceSetPath: String,
        wdaRunnerXctest: File,
        private val concurrentBootsPool: ExecutorService,
        headless: Boolean,
        private val useWda: Boolean,
        override val fbsimctlSubject: String,
        private val trustStoreFile: String = ApplicationConfiguration().trustStorePath,
        private val assetsPath: String = ApplicationConfiguration().assetsPath
) : ISimulator
{
    private companion object {
        private val PREPARE_TIMEOUT: Duration = Duration.ofMinutes(10)
        private val RESET_TIMEOUT: Duration = Duration.ofMinutes(5)
        private const val SAFARI_BUNDLE_ID = "com.apple.mobilesafari"
    }

    override val ref = deviceRef
    override val udid: UDID = deviceInfo.udid
    override val fbsimctlEndpoint = URI("http://${remote.publicHostName}:${allocatedPorts.fbsimctlPort}/$udid/")
    override val wdaEndpoint = URI("http://${remote.publicHostName}:${allocatedPorts.wdaPort}/")
    override val userPorts = allocatedPorts
    override val info = deviceInfo
    override val calabashPort: Int = allocatedPorts.calabashPort
    override val mjpegServerPort: Int = allocatedPorts.mjpegServerPort

    private fun createVideoRecorder(): VideoRecorder {
        val recorderClassName = ApplicationConfiguration().videoRecorderClassName

        return when (recorderClassName) {
            SimulatorVideoRecorder::class.qualifiedName -> SimulatorVideoRecorder(
                deviceInfo,
                remote,
                location = Paths.get(deviceSetPath, udid, "video.mp4").toFile()
            )
            MJPEGVideoRecorder::class.qualifiedName -> MJPEGVideoRecorder(
                deviceInfo,
                remote,
                wdaEndpoint,
                mjpegServerPort,
                ref,
                udid
            )
            else -> throw IllegalArgumentException(
                "Wrong class specified as video recorder: $recorderClassName. " +
                        "Available are: [${SimulatorVideoRecorder::class.qualifiedName}, ${MJPEGVideoRecorder::class.qualifiedName}]"
            )
        }
    }
    override val videoRecorder: VideoRecorder = createVideoRecorder()

    override val systemLog = SystemLog(remote, udid)
    override val osLog = OsLog(remote, udid)

    //region instance state variables
    private val deviceLock = ReentrantLock()
    @Volatile private var deviceState: DeviceState = DeviceState.NONE // writing from separate thread
    @Volatile private var lastException: Exception? = null // writing from separate thread

    private val fbsimctlProc: FbsimctlProc = FbsimctlProc(remote, deviceInfo.udid, fbsimctlEndpoint, headless)
    private val simulatorProcess = SimulatorProcess(remote, udid, deviceRef)

    private val webDriverAgent: IWebDriverAgent

    init {
        val wdaClassName = ApplicationConfiguration().simulatorWdaClassName

        webDriverAgent = when (wdaClassName) {
            SimulatorWebDriverAgent::class.qualifiedName -> SimulatorWebDriverAgent(
                remote,
                wdaRunnerXctest,
                deviceInfo.udid,
                wdaEndpoint,
                mjpegServerPort,
                deviceRef
            )
            SimulatorXcrunWebDriverAgent::class.qualifiedName -> SimulatorXcrunWebDriverAgent(
                remote,
                wdaRunnerXctest,
                deviceInfo.udid,
                wdaEndpoint,
                mjpegServerPort,
                deviceRef
            )
            else -> throw IllegalArgumentException(
                "Wrong class specified as WDA for Simulator: $wdaClassName. " +
                        "Available are: [${SimulatorWebDriverAgent::class.qualifiedName}, ${SimulatorXcrunWebDriverAgent::class.qualifiedName}]"
            )
        }
    }

    private val backup: ISimulatorBackup = SimulatorBackup(remote, udid, deviceSetPath)
    private val logger = LoggerFactory.getLogger(javaClass.simpleName)
    private val commonLogMarkerDetails = mapOf(
            LogMarkers.DEVICE_REF to deviceRef,
            LogMarkers.UDID to udid,
            LogMarkers.HOSTNAME to remote.hostName
    )
    private val logMarker: Marker = MapEntriesAppendingMarker(commonLogMarkerDetails)
    private val fileSystem = FileSystem(remote, udid)
    @Volatile private var healthChecker: Job? = null
    //endregion

    override val media: Media = Media(remote, udid, deviceSetPath)

    //region properties from ruby with backing mutable field
    override val state get() = deviceState
    override val lastError get() = lastException
    //endregion

    override fun toString() = "<Simulator: $deviceRef>"

    //region prepareAsync
    override fun prepareAsync() {
        executeCritical {
            if (deviceState == DeviceState.CREATING || deviceState == DeviceState.RESETTING) {
                throw java.lang.IllegalStateException("Simulator $udid is already in state $deviceState")
            }
            deviceState = DeviceState.CREATING

            val nanos = measureNanoTime {
                try {
                    stopPeriodicHealthCheck()
                    prepare(clean = true)
                } catch (e: Exception) { // catching most wide exception
                    deviceState = DeviceState.FAILED
                    logger.error(logMarker, "Failed to prepare device ${this@Simulator}", e)
                    throw e
                }
            }

            val seconds = TimeUnit.NANOSECONDS.toSeconds(nanos)
            val measurement = mutableMapOf(
                "action_name" to "prepareAsync",
                "duration" to seconds
            )
            measurement.putAll(commonLogMarkerDetails)

            logger.info(MapEntriesAppendingMarker(measurement), "Device ${this@Simulator} ready in $seconds seconds")
        }
    }

    private fun prepare(timeout: Duration = PREPARE_TIMEOUT, clean: Boolean) {
        logger.info(logMarker, "Starting to prepare ${this@Simulator}. Will wait for ${timeout.seconds} seconds")
        lastException = null
        webDriverAgent.kill()
        shutdown()

        //FIXME: add checks for cancellation of criticalAsyncPromise
        executeWithTimeout(timeout, "Preparing simulator") {
            // erase simulator if there is no existing backup, this is to ensure backup is created from a clean state
            logger.info(logMarker, "Launch prepare sequence for ${this@Simulator} asynchronously")

            if (backup.isExist()) {
                if (clean) {
                    backup.restore()
                }
            } else {
                eraseSimulatorAndCreateBackup()
            }

            logTiming("simulator boot") { boot() }

            if (useWda) {
                logTiming("starting WebDriverAgent") { startWdaWithRetry() }
            }

            logger.info(logMarker, "Finished preparing $this")
            deviceState = DeviceState.CREATED
            startPeriodicHealthCheck()
        }
    }

    private fun startPeriodicHealthCheck() {
        stopPeriodicHealthCheck()

        var fbsimctlFailCount = 0
        var wdaFailCount = 0
        val maxFailCount = 4
        val healthCheckInterval = Duration.ofSeconds(15).toMillis()

        healthChecker = launch {
            while (isActive) {
                if (fbsimctlProc.isHealthy()) {
                    fbsimctlFailCount = 0
                } else {
                    fbsimctlFailCount += 1

                    if (fbsimctlFailCount >= maxFailCount) {
                        deviceState = DeviceState.FAILED
                        val message = "Fbsimctl health check failed $fbsimctlFailCount times. Setting device state to $deviceState"
                        logger.error(logMarker, message)
                        throw RuntimeException("${this@Simulator} $message. Stopping health check")
                    }
                }

                if (webDriverAgent.isHealthy()) {
                    wdaFailCount = 0
                } else {
                    wdaFailCount += 1

                    if (wdaFailCount >= maxFailCount) {
                        deviceState = DeviceState.FAILED
                        val message = "WebDriverAgent health check failed $wdaFailCount times. Setting device state to $deviceState"
                        logger.error(logMarker, message)
                        throw RuntimeException("${this@Simulator} $message. Stopping health check")
                    }
                }

                delay(healthCheckInterval)
            }
        }
    }

    private fun stopPeriodicHealthCheck() {
        healthChecker?.cancel()
    }

    private fun startWdaWithRetry(pollTimeout: Duration = Duration.ofSeconds(10), retryInterval: Duration = Duration.ofSeconds(1)) {
        val maxRetries = 7

        for (attempt in 1..maxRetries) {
            try {
                logger.info(logMarker, "Starting WebDriverAgent on ${this@Simulator}")

                webDriverAgent.kill()
                webDriverAgent.start()

                Thread.sleep(2000)

                pollFor(
                    pollTimeout,
                    reasonName = "${this@Simulator} WebDriverAgent health check",
                    retryInterval = retryInterval,
                    logger = logger,
                    marker = logMarker
                ) {
                    webDriverAgent.isHealthy()
                }

                logger.info(logMarker, "Started WebDriverAgent on ${this@Simulator}")

                return
            }
            catch (e: RuntimeException) {
                logger.warn(logMarker, "Attempt $attempt to start WebDriverAgent for ${this@Simulator} failed: $e")
                if (attempt == maxRetries) {
                    throw e
                }
            }
        }
    }

    private fun eraseSimulatorAndCreateBackup() {
        logger.info(logMarker, "Erasing simulator ${this@Simulator} before creating a backup")
        remote.fbsimctl.eraseSimulator(udid)

        if (trustStoreFile.isNotEmpty()) {
            copyTrustStore()
        }

        logger.info(logMarker, "Booting ${this@Simulator} before creating a backup")
        logTiming("initial boot") { boot() }

        if (assetsPath.isNotEmpty()) {
            copyMediaAssets()
        }

        if (useWda) {
            webDriverAgent.installHostApp()
        }

        logger.info(logMarker, "Shutting down ${this@Simulator} before creating a backup")
        shutdown()

        backup.create()
    }

    private fun copyTrustStore() {
        logger.debug(logMarker, "Copying trust store to ${this@Simulator}")
        val keyChainLocation = Paths.get(deviceSetPath, udid, "data", "Library", "Keychains").toFile().absolutePath
        remote.shell("mkdir -p $keyChainLocation", returnOnFailure = false)

        if (remote.isLocalhost()) {
            remote.shell("cp $trustStoreFile $keyChainLocation", returnOnFailure = false)
        } else {
            remote.scpToRemoteHost(trustStoreFile, keyChainLocation)
        }

        logger.info(logMarker, "Copied trust store to ${this@Simulator}")
    }

    private fun copyMediaAssets() {
        logger.debug(logMarker, "Copying assets to ${this@Simulator}")
        media.reset()
        File(assetsPath).walk().filter { it.isFile }.forEach {
           media.addMedia(it, it.readBytes())
        }

        logger.info(logMarker, "Copied assets to ${this@Simulator}")
    }

    private fun listDevices(): String {
        return remote.shell("/usr/bin/xcrun simctl list devices", returnOnFailure = true).stdOut
    }

    private fun isSimulatorShutdown(): Boolean {
        return listDevices().lines().filter { it.contains(udid) && it.contains("(Shutdown)") }.any()
    }

    private fun shutdown() {
        logger.info(logMarker, "Shutting down ${this@Simulator}")
        val result = remote.fbsimctl.shutdown(udid)

        if (!result.isSuccess && !result.stdErr.contains("current state: Shutdown")) {
            logger.debug(logMarker, "Error occured while shutting down simulator $udid. Command exit code: ${result.exitCode}. Result stdErr: ${result.stdErr}")
        }

        ignoringErrors { fbsimctlProc.kill() }

        pollFor(
            timeOut = Duration.ofSeconds(60),
            retryInterval = Duration.ofSeconds(10),
            reasonName = "${this@Simulator} to shutdown",
            logger = logger,
            marker = logMarker
        ) {
            isSimulatorShutdown()
        }

        logger.info(logMarker, "Successfully shut down ${this@Simulator}")
    }

    private fun boot() {
        logger.info(logMarker, "Booting ${this@Simulator} asynchronously")
        truncateSystemLogIfExists()
        logger.info(logMarker, "Starting fbsimctl on ${this@Simulator}")
        var lastState: String? = null
        var systemLogPath = ""

        val bootProc = {
            fbsimctlProc.start() // boots simulator

            try {
                pollFor(
                    Duration.ofSeconds(90),
                    retryInterval = Duration.ofSeconds(10),
                    reasonName = "${this@Simulator} initial boot",
                    shouldReturnOnTimeout = false,
                    logger = logger,
                    marker = logMarker
                ) {
                    val simulatorInfo = remote.fbsimctl.listDevice(udid)
                    lastState = simulatorInfo?.state
                    lastState == FBSimctlDeviceState.BOOTED.value
                }
            } catch (e: WaitTimeoutError) {
                throw WaitTimeoutError("${e.message}. Simulator is in wrong state of $lastState", e)
            }

            pollFor(
                Duration.ofSeconds(60),
                retryInterval = Duration.ofSeconds(10),
                reasonName = "${this@Simulator} system log appeared",
                shouldReturnOnTimeout = true,
                logger = logger,
                marker = logMarker
            ) {
                val diagnosticInfo = remote.fbsimctl.diagnose(udid)
                val location = diagnosticInfo.sysLogLocation
                if (location != null) {
                    systemLogPath = location
                    logger.info(logMarker, "Device ${this@Simulator} system log appeared")
                    true
                } else {
                    logger.warn(logMarker, "Device ${this@Simulator} system log NOT appeared")
                    false
                }
            }

        }
        val bootTask = concurrentBootsPool.submit(bootProc)
        bootTask.get()

        pollFor(Duration.ofSeconds(60),
            retryInterval = Duration.ofSeconds(10),
            reasonName = "${this@Simulator} to be sufficiently booted",
            shouldReturnOnTimeout = true,
            logger = logger,
            marker = logMarker
        ) {
            if (!systemLogPath.isBlank()) {
                remote.execIgnoringErrors(listOf("grep", "-m1", "SpringBoard", systemLogPath)).isSuccess
            } else {
                false
            }
        }

        val requiredRecords = listOf(
            "GDRRequestMadeForRelayRepair",
            "com.apple.fileproviderd"
        )

        pollFor(Duration.ofMinutes(2),
            retryInterval = Duration.ofSeconds(15),
            reasonName = "${this@Simulator} to load required services",
            shouldReturnOnTimeout = true,
            logger = logger,
            marker = logMarker
        ) {
            logger.debug(logMarker, "Checking that defaults contain values $requiredRecords")
            val simulatorDefaults = readSimulatorDefaults()
            requiredRecords.all { simulatorDefaults.contains(it) }
        }

        val osVersion = Regex("[0-9.]+").find(deviceInfo.os)?.value?.toFloat()

        if (osVersion != null && osVersion >= 13) {
            val requiredRecordsIos13 = listOf(
                "com.apple.parsecd",
                "PredictiveGenerationLastRun"
            )
            pollFor(Duration.ofMinutes(2),
                retryInterval = Duration.ofSeconds(15),
                reasonName = "${this@Simulator} to load required services for iOS 13",
                shouldReturnOnTimeout = true,
                logger = logger,
                marker = logMarker
            ) {
                logger.debug(logMarker, "Checking that defaults contain values $requiredRecordsIos13")
                val simulatorDefaults = readSimulatorDefaults()
                requiredRecordsIos13.all { simulatorDefaults.contains(it) }
            }

            logger.info(logMarker, "Saving Preference that Continuous Path Introduction was shown")
            writeSimulatorDefaults("com.apple.Preferences DidShowContinuousPathIntroduction -bool true")
        }

        pollFor(
            Duration.ofSeconds(60),
            retryInterval = Duration.ofSeconds(10),
            reasonName = "${this@Simulator} FbSimCtl health check",
            logger = logger,
            marker = logMarker
        ) {
            fbsimctlProc.isHealthy()
        }

        logger.info(logMarker, "Device ${this@Simulator} is sufficiently booted")
    }

    private fun writeSimulatorDefaults(setting: String) {
        remote.shell("/usr/bin/xcrun simctl spawn $udid defaults write $setting", true)
    }

    private fun readSimulatorDefaults(): String {
        return remote.execIgnoringErrors("/usr/bin/xcrun simctl spawn $udid defaults read".split(" ")).stdOut
    }

    private fun truncateSystemLogIfExists() {
        val sysLog = remote.fbsimctl.diagnose(udid).sysLogLocation ?: return

        if (remote.isLocalhost()) {
            try {
                FileOutputStream(sysLog).channel.use {
                    it.truncate(0)
                }
            } catch(e: IOException) {
                logger.error(logMarker, "Error truncating sysLog $this", e)
            }
        } else {
            try {
                remote.shell("echo -n > $sysLog", returnOnFailure = true)
                logger.debug(logMarker, "Truncated syslog of simulator ${this@Simulator}")
            } catch (e: RuntimeException) {
                logger.error(logMarker, "Error while truncating syslog of simulator ${this@Simulator}", e)
            }
        }
    }

    private fun logTiming(actionName: String, action: () -> Unit) {
        logger.info(logMarker, "Device ${this@Simulator} starting action <$actionName>")
        val nanos = measureNanoTime(action)
        val seconds = TimeUnit.NANOSECONDS.toSeconds(nanos)
        val measurement = mutableMapOf(
                "action_name" to actionName,
                "duration" to seconds
        )
        measurement.putAll(commonLogMarkerDetails)
        logger.info(MapEntriesAppendingMarker(measurement), "Device ${this@Simulator} action <$actionName> took $seconds seconds")
    }
    //endregion

    //region reset async
    override fun resetAsync() {
        if (deviceState == DeviceState.CREATING || deviceState == DeviceState.RESETTING) {
            throw java.lang.IllegalStateException("Simulator $udid is already in state $deviceState")
        }

        executeCritical {
            deviceState = DeviceState.RESETTING

            val nanos = measureNanoTime {
                resetFromBackup()
                try {
                    stopPeriodicHealthCheck()
                    prepare(clean = false) // simulator is already clean as it was restored from backup in resetFromBackup
                } catch (e: Exception) { // catching most wide exception
                    deviceState = DeviceState.FAILED
                    logger.error(logMarker, "Failed to reset and prepare device ${this@Simulator}", e)
                    throw e
                }
            }

            val seconds = TimeUnit.NANOSECONDS.toSeconds(nanos)

            val measurement = mutableMapOf(
                "action_name" to "resetAsync",
                "duration" to seconds
            )
            measurement.putAll(commonLogMarkerDetails)

            logger.info(MapEntriesAppendingMarker(measurement), "Device ${this@Simulator} reset and ready in $seconds seconds")
        }
    }

    private fun resetFromBackup(timeout: Duration = RESET_TIMEOUT) {
        logger.info(logMarker, "Starting to reset $this")

        executeWithTimeout(timeout, "Resetting simulator") {
            disposeResources()
            shutdown()

            if (!backup.isExist()) {
                logger.error(logMarker, "Could not find backup for $this")
                throw SimulatorError("Could not find backup for $this")
            }

            logTiming("replacing with backup") {
                backup.restore()
            }
        }

        logger.info(logMarker, "Finished to reset $this")
    }
    //endregion

    //region helper functions — execute critical and async
    private fun executeCritical(action: () -> Unit) {
        deviceLock.withLock {
            try {
                action()
            } catch (e: RuntimeException) {
                deviceState = DeviceState.FAILED
                lastException = e
                // FIXME: force shutdown failed sim
                logger.error(logMarker, "Execute critical block finished with exception. Message: [${e.message}]", e)
            }
        }
    }
    //endregion

    //region simulator status
    override fun status(): SimulatorStatusDTO {
        var isFbsimctlReady = false
        var isWdaReady = false

        if (deviceState == DeviceState.CREATED) {
            isFbsimctlReady = fbsimctlProc.isHealthy()
            isWdaReady = (if (useWda) { webDriverAgent.isHealthy() } else true)
        }

        val isSimulatorReady = deviceState == DeviceState.CREATED && isFbsimctlReady && isWdaReady

        return SimulatorStatusDTO(
                ready = isSimulatorReady,
                wda_status = isWdaReady,
                fbsimctl_status = isFbsimctlReady,
                state = deviceState.value,
                last_error = lastException?.toDTO()
        )
    }

    private fun Exception.toDTO(): ExceptionDTO {

        return ExceptionDTO(
            type = this.javaClass.name,
            message = this.message ?: "",
            stackTrace = stackTrace.map { it.toString() }
        )
    }
    //endregion

    override fun endpointFor(port: Int): URL {
        val ports = allocatedPorts.toSet()
        require(ports.contains(port)) { "Port $port is not in user ports range $ports" }

        return URL("http://${remote.publicHostName}:$port/")
    }

    //region approveAccess

    override fun approveAccess(bundleId: String, locationPermissionsLock: ReentrantLock) {
        val permissions = SimulatorPermissions(remote, deviceSetPath, this)

        val set = PermissionSet()

        set.putAll(mapOf(
            PermissionType.Camera to PermissionAllowed.Yes,
            PermissionType.Microphone to PermissionAllowed.Yes,
            PermissionType.Photos to PermissionAllowed.Yes,
            PermissionType.Contacts to PermissionAllowed.Yes
        ))

        permissions.setPermissions(bundleId, set, locationPermissionsLock)
    }

    override fun setPermissions(bundleId: String, permissions: PermissionSet, locationPermissionsLock: ReentrantLock) {
        val manager = SimulatorPermissions(remote, deviceSetPath, this)

        manager.setPermissions(bundleId, permissions, locationPermissionsLock)
    }

    //endregion

    //region release
    override fun release(reason: String) {
        stopPeriodicHealthCheck()
        logger.info(logMarker, "Releasing device $this because $reason")
        disposeResources()
        shutdown()
        logger.info(logMarker, "Released device $this")
    }

    private fun disposeResources() {
        ignoringErrors({ videoRecorder.dispose() })
        ignoringErrors({ webDriverAgent.kill() })
    }

    private fun ignoringErrors(action: () -> Unit?) {
        try {
            action()
        } catch (e: Throwable) { // FIXME: RuntimeError, SystemCallError in Ruby
            logger.warn(logMarker, "Ignoring $this release error: $e")
        }
    }
    //endregion

    /**
     * [see Deleting-Safari-Cookies-in-iOS-Simulator.html](http://www.ryanchapin.com/fv-b-4-744/Deleting-Safari-Cookies-in-iOS-Simulator.html)
     */
    private val cookieJars = listOf(
        "Cookies.binarycookies", // pre iOS 12
        "com.apple.SafariViewService.binarycookies" // iOS 12
    )

    override fun clearSafariCookies(): Map<String, String> {
        val apps = remote.fbsimctl.listApps(udid)
        check(!apps.isEmpty()) { "Could not list apps for $this" }

        val safari = apps.find { SAFARI_BUNDLE_ID == it.bundle.bundle_id }

        if (safari == null) {
            throw IllegalStateException("$SAFARI_BUNDLE_ID not found in $apps for $this")
        }

        // Have to kill Simulator's SafariViewService process as it holds cookies loaded
        simulatorProcess.terminateChildProcess("SafariViewService")

        val cookieJarPaths = cookieJars.map { cookieJar ->
            File(safari.data_container, listOf("Library", "Cookies", cookieJar).joinToString(File.separator)).absolutePath
        }

        val cmd = mutableListOf("rm", "-f")
        cmd.addAll(cookieJarPaths)
        val result = remote.execIgnoringErrors(cmd)
        check(result.isSuccess) { "Failed to remove safari cookies ($cookieJarPaths on $remote for $this: $result" }

        return mapOf("status" to "true")
    }

    override fun shake() : Boolean {
        val command = listOf("xcrun", "simctl", "notify_post", udid, "com.apple.UIKit.SimulatorShake")
        val result = remote.execIgnoringErrors(command)
        return result.isSuccess
    }

    override fun openUrl(url: String) : Boolean {
        val urlString = if (remote.isLocalhost()) { url } else { "\"$url\"" }

        val command = listOf("xcrun", "simctl", "openurl", udid, urlString)
        val result = remote.execIgnoringErrors(command)
        return result.isSuccess
    }

    //region last crash log

    @Deprecated("Will be removed in favor of crashLogs. Note that crashLogs does not delete old crashes")
    override fun lastCrashLog(): CrashLog {
        val crashLog = crashLogs(pastMinutes = null).firstOrNull() ?: CrashLog("no crash logs found", "")

        deleteCrashLogs() // FIXME: this will delete current crash log which makes method non-idempotent

        return crashLog
    }

    override fun crashLogs(pastMinutes: Long?): List<CrashLog> {
        val crashLogFiles = listCrashLogs(pastMinutes)

        val crashLogs = crashLogFiles.map {
            val rv = remote.execIgnoringErrors(listOf("cat", it))

            if (rv.isSuccess) {
                CrashLog(filename = File(it).name, content = rv.stdOut)
            } else {
                logger.warn(logMarker, "Cannot read  crash log file $it")
                null
            }
        }

        return crashLogs.filterNotNull()
    }

    /**
     * Returns list of crashes (file names) since {@code pastMinutes} sorted by most recent first
     * @param pastMinutes optional duration to search from, for example Duration.ofMinutes(5)
     */
    private fun listCrashLogs(pastMinutes: Long? = null):List<String> {
        if (pastMinutes != null && pastMinutes < 0) {
            throw IllegalArgumentException("pastMinutes should be positive ")
        }

        val predicate = when (pastMinutes) {
            null -> ""
            else -> "-mmin -$pastMinutes"
        }

        // `ls -1t` is for backwards compatibility with lastCrashLog as it expects this method to return most recent crashes first
        val cmd = "find \$HOME/Library/Logs/DiagnosticReports $predicate -type f -name \\*.crash -print0 | " +
                "xargs -0 grep --files-with-matches --null $udid | " +
                "xargs -0 ls -1t"

        val result = remote.shell(cmd, returnOnFailure = true)
        if (!result.isSuccess) {
            SimulatorError("Failed to list crash logs for $this: $result")
        }
        return result.stdOut
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toList()
    }

    override fun deleteCrashLogs(): Boolean {
        val cmd = "find \$HOME/Library/Logs/DiagnosticReports -type f -name \\*.crash -print0 | " +
                "xargs -0 grep --files-with-matches --null $udid | " +
                "xargs -0 rm"

        val result = remote.shell(cmd, returnOnFailure = true)

        return result.isSuccess
    }

    override fun dataContainer(bundleId: String): DataContainer {
        return fileSystem.dataContainer(bundleId)
    }

    override fun applicationContainer(bundleId: String): DataContainer {
        return fileSystem.applicationContainer(bundleId)
    }

    //endregion
    override fun uninstallApplication(bundleId: String, appInstaller: AppInstaller) {
        appInstaller.uninstallApplication(udid, bundleId)
    }

    override fun setEnvironmentVariables(envs: Map<String, String>) {
        if (envs.isEmpty()) {
            logger.debug(logMarker, "Passed empty list of environment variables for Simulator $this")
            return
        }

        logger.debug(logMarker, "Setting environment variables $envs for Simulator $this")
        val envsArguments = mutableListOf<String>()
        envs.keys.forEach {
            envsArguments.addAll(listOf(it, ShellUtils.escape(envs.getValue(it))))
        }
        remote.shell("xcrun simctl spawn $udid launchctl setenv ${envsArguments.joinToString(" ")}")
    }
}
